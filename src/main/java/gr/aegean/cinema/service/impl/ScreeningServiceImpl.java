package gr.aegean.cinema.service.impl;

import gr.aegean.cinema.dto.screening.*;
import gr.aegean.cinema.exception.BadRequestException;
import gr.aegean.cinema.exception.ConflictException;
import gr.aegean.cinema.exception.NotFoundException;
import gr.aegean.cinema.model.entity.Program;
import gr.aegean.cinema.model.entity.Screening;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.ProgramState;
import gr.aegean.cinema.model.enums.ScreeningState;
import gr.aegean.cinema.repository.ProgramRepository;
import gr.aegean.cinema.repository.ScreeningRepository;
import gr.aegean.cinema.repository.UserRepository;
import gr.aegean.cinema.repository.spec.ScreeningSpecifications;
import gr.aegean.cinema.redaction.RedactionService;
import gr.aegean.cinema.security.AuditLog;
import gr.aegean.cinema.security.AuthorizationService;
import gr.aegean.cinema.security.CurrentUserContext;
import gr.aegean.cinema.service.ScreeningService;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implements the whole "Screening management" module: creation, update,
 * submission, withdrawal, handler assignment, review, approval, manual
 * rejection, final submission and acceptance, together with the screening state
 * machine that ties them together.
 *
 * <h2>Invariants enforced here, once each</h2>
 * <ul>
 *   <li>Every write method starts with
 *       {@link AuthorizationService#requireCinemaActor()} — the single gate that
 *       rejects ADMIN with 403 {@code ADMIN_NOT_ALLOWED} and blocks inactive
 *       accounts (ROLE-14/15/16). Adding a method without it regresses those
 *       rows, so the call is deliberately the first statement every time.</li>
 *   <li>Every write method that mutates a screening also passes through
 *       {@link AuthorizationService#requireNotAnnounced} — in ANNOUNCED
 *       "everything is frozen: no updates to program or screenings"
 *       (FR-PRG-T9).</li>
 *   <li><b>Actor before state.</b> The role check always runs BEFORE any check
 *       on the program's or the screening's current state, exactly as in
 *       {@code ProgramServiceImpl.advance}. An outsider therefore always sees
 *       403 and never learns the screening's state from a 409.</li>
 *   <li>Every state change goes through the single private
 *       {@link #advance}, which is the ONLY place
 *       {@link Screening#setState(ScreeningState)} is called. It refuses every
 *       edge that is not in {@link #LEGAL_TRANSITIONS} (FR-SCR-T8: SCHEDULED
 *       and REJECTED have no outgoing edge at all) and writes the audit line for
 *       both the accepted and the refused case.</li>
 * </ul>
 *
 * <h2>Status codes (DOMAIN_RULES.md + ASSUMPTIONS.md #30)</h2>
 * <ul>
 *   <li><b>403</b> — the wrong actor: not the SUBMITTER, not a PROGRAMMER of the
 *       program, not the assigned handler, or an ADMIN/inactive account.</li>
 *   <li><b>409</b> — the right actor, a well-formed request, but a conflict with
 *       the CURRENT program or screening state. This includes ROLE-19, which
 *       DOMAIN_RULES.md pins to 409 explicitly.</li>
 *   <li><b>400</b> — genuinely malformed input. DOMAIN_RULES.md names exactly one
 *       such case for screenings, FR-SCR-10 ({@code end_time − start_time} below
 *       the film duration), and it is honoured here as a 400 even though every
 *       neighbouring refusal is a 409.</li>
 * </ul>
 *
 * <p>Search, timetable sorting and role-based redaction (FR-SCR-37 … FR-SCR-46)
 * live at the bottom of this class and own no mapping or visibility logic of
 * their own: both delegate to the single central
 * {@link gr.aegean.cinema.redaction.RedactionService} (FR-RED-02), shared with
 * the program domain.
 */
@Service
public class ScreeningServiceImpl implements ScreeningService {

    /**
     * The complete screening state machine (FR-SCR-T1 … FR-SCR-T8). A state that
     * is absent from this map, or mapped to an empty set, has NO outgoing
     * transition — which is precisely how SCHEDULED and REJECTED are made
     * terminal (FR-SCR-T8). Withdrawal (FR-SCR-T2) is not an edge because it
     * deletes the row instead of moving it to another state.
     */
    private static final Map<ScreeningState, Set<ScreeningState>> LEGAL_TRANSITIONS = Map.of(
            ScreeningState.CREATED, EnumSet.of(ScreeningState.SUBMITTED),
            ScreeningState.SUBMITTED, EnumSet.of(ScreeningState.REVIEWED),
            ScreeningState.REVIEWED, EnumSet.of(ScreeningState.APPROVED, ScreeningState.REJECTED),
            ScreeningState.APPROVED, EnumSet.of(ScreeningState.SCHEDULED, ScreeningState.REJECTED),
            ScreeningState.SCHEDULED, EnumSet.noneOf(ScreeningState.class),
            ScreeningState.REJECTED, EnumSet.noneOf(ScreeningState.class)
    );

    /** FR-SCR-T8: the two states a screening can never leave again. */
    private static final Set<ScreeningState> TERMINAL_STATES =
            EnumSet.of(ScreeningState.SCHEDULED, ScreeningState.REJECTED);

    /**
     * ASSUMPTIONS.md #21: a screening may be created while the program is still
     * drafting its line-up (CREATED) or while submissions are open (SUBMISSION),
     * and not afterwards.
     */
    private static final Set<ProgramState> SCREENING_CREATION_WINDOW =
            EnumSet.of(ProgramState.CREATED, ProgramState.SUBMISSION);

    /**
     * FR-SCR-43 / FR-SCR-44: the ordinary (non-timetable) search result order —
     * "sorted by genre, then by film title". Declared once so that the two
     * orderings of FR-SCR-43/44/45 cannot drift.
     */
    private static final Comparator<Screening> BY_GENRE_THEN_TITLE =
            Comparator.comparing((Screening s) -> nullToEmpty(s.getFilmGenres()))
                    .thenComparing(s -> nullToEmpty(s.getFilmTitle()));

    /** FR-SCR-45: "for timetable views sort by start_time instead". */
    private static final Comparator<Screening> BY_START_TIME =
            Comparator.comparing(Screening::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()));

    private final ScreeningRepository screeningRepository;
    private final ProgramRepository programRepository;
    private final UserRepository userRepository;
    private final AuthorizationService authorizationService;
    private final RedactionService redactionService;

    public ScreeningServiceImpl(ScreeningRepository screeningRepository, ProgramRepository programRepository,
                                 UserRepository userRepository, AuthorizationService authorizationService,
                                 RedactionService redactionService) {
        this.screeningRepository = screeningRepository;
        this.programRepository = programRepository;
        this.userRepository = userRepository;
        this.authorizationService = authorizationService;
        this.redactionService = redactionService;
    }

    // ==================================================================
    // Creation / update / withdrawal
    // ==================================================================

    /**
     * FR-SCR-01 … FR-SCR-06 and ROLE-05: any authenticated, active, non-ADMIN
     * account may create a screening. The program link is mandatory and comes
     * from the path (FR-SCR-05), id and creation date are generated by the
     * persistence layer (FR-SCR-03/04), the initial state is CREATED
     * (FR-SCR-06), the creator becomes the screening's SUBMITTER (FR-SCR-02) and
     * the handler is left empty until assignment (FR-SCR-19).
     *
     * <p>ROLE-19 is enforced here as a <b>409</b>: a PROGRAMMER of this very
     * program must not submit screenings into it, and creation is the moment the
     * creator would become its SUBMITTER.
     */
    @Override
    @Transactional
    public ScreeningResponse createScreening(Long programId, ScreeningCreateRequest request) {
        User requester = authorizationService.requireCinemaActor();
        Program program = findProgramOrThrow(programId);

        // FR-PRG-T9: in ANNOUNCED everything is frozen, screenings included.
        authorizationService.requireNotAnnounced(program, "creating a screening");
        requireNotProgrammerOfOwnProgram(requester, program);

        if (!SCREENING_CREATION_WINDOW.contains(program.getState())) {
            throw new ConflictException(
                    "New screenings are accepted only while the program is in CREATED or SUBMISSION; "
                            + "this one is in " + program.getState(),
                    "PROGRAM_NOT_ACCEPTING_SCREENINGS");
        }

        Screening screening = Screening.builder()
                .program(program)                 // FR-SCR-05: mandatory, set once, never reassigned
                .submitter(requester)             // FR-SCR-02
                .state(ScreeningState.CREATED)    // FR-SCR-06
                .filmTitle(request.getFilmTitle())
                .filmCast(request.getFilmCast())
                .filmGenres(request.getFilmGenres())
                .filmDurationMinutes(request.getFilmDurationMinutes())
                .auditoriumName(request.getAuditoriumName())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .build();

        validateTiming(screening);
        screening = screeningRepository.save(screening);

        AuditLog.screeningCreated(screening.getId(), program.getId(), requester.getUsername());
        return toFullResponse(screening);
    }

    /**
     * FR-SCR-07 / FR-SCR-08 / FR-SCR-09 / FR-SCR-33 and ROLE-12: only the
     * SUBMITTER may update their own screening, only while the screening is
     * still in CREATED, and only the film/auditorium/timing fields.
     *
     * <p>FR-SCR-05: {@link ScreeningUpdateRequest} deliberately carries no
     * program field, so the program link cannot be changed by any update — the
     * immutability is structural rather than a runtime check that could be
     * forgotten.
     *
     * <p>FR-SCR-32: a screening that has been finally submitted is frozen, and
     * gets its own error code so the freeze is distinguishable from the ordinary
     * "not in CREATED" refusal.
     */
    @Override
    @Transactional
    public ScreeningResponse updateScreening(Long screeningId, ScreeningUpdateRequest request) {
        User requester = authorizationService.requireCinemaActor();
        Screening screening = findScreeningOrThrow(screeningId);
        authorizationService.requireSubmitterOf(requester, screening);

        authorizationService.requireNotAnnounced(screening.getProgram(), "updating a screening");
        requireNotTerminal(screening, "updating a screening");
        requireDetailsNotFrozen(screening);

        if (screening.getState() != ScreeningState.CREATED) {
            throw new ConflictException(
                    "Regular updates are allowed only while the screening is in CREATED; this one is "
                            + screening.getState(),
                    "SCREENING_NOT_UPDATABLE");
        }

        applyUpdatableFields(screening, request);
        validateTiming(screening);

        screening = screeningRepository.save(screening);
        AuditLog.screeningUpdated(screening.getId(), requester.getUsername(), screening.getState().name());
        return toFullResponse(screening);
    }

    /**
     * FR-SCR-11 / FR-SCR-12 / FR-SCR-13 and FR-SCR-T1: CREATED → SUBMITTED, by
     * the SUBMITTER, while the program is in SUBMISSION, and only if the
     * screening carries every required detail.
     *
     * <p>ROLE-19 is re-checked here and not only at creation: a user may have
     * created a screening and only afterwards been granted the PROGRAMMER role
     * in that same program, and the rule is about submitting, not about the
     * moment the draft row appeared.
     */
    @Override
    @Transactional
    public ScreeningResponse submitScreening(Long screeningId) {
        User requester = authorizationService.requireCinemaActor();
        Screening screening = findScreeningOrThrow(screeningId);
        authorizationService.requireSubmitterOf(requester, screening);

        Program program = screening.getProgram();
        authorizationService.requireNotAnnounced(program, "submitting a screening");
        requireNotProgrammerOfOwnProgram(requester, program);
        requireProgramState(screening, program, ProgramState.SUBMISSION, requester,
                ScreeningState.SUBMITTED, "PROGRAM_NOT_IN_SUBMISSION",
                "Submission is allowed only while the program is in SUBMISSION; this one is ");

        if (!isComplete(screening)) {
            AuditLog.screeningTransitionRejected(screeningId, screening.getState().name(),
                    ScreeningState.SUBMITTED.name(), requester.getUsername(), "SCREENING_NOT_COMPLETE");
            throw new ConflictException(
                    "The screening is incomplete: film title, cast, genres, duration, auditorium, "
                            + "start time and end time are all required before it can be submitted",
                    "SCREENING_NOT_COMPLETE");
        }
        // FR-SCR-10 is re-validated at the gate, so an incomplete-then-completed
        // draft can never be submitted with an impossible slot.
        validateTiming(screening);

        advance(screening, ScreeningState.SUBMITTED, requester, "SUBMIT");
        screening = screeningRepository.save(screening);
        return toFullResponse(screening);
    }

    /**
     * FR-SCR-14 / FR-SCR-15 and FR-SCR-T2: only the SUBMITTER, only while the
     * screening is still in CREATED. "On withdrawal it is deleted, since it has
     * not entered the formal review" — so this is a deletion and not a state.
     */
    @Override
    @Transactional
    public void withdrawScreening(Long screeningId) {
        User requester = authorizationService.requireCinemaActor();
        Screening screening = findScreeningOrThrow(screeningId);
        authorizationService.requireSubmitterOf(requester, screening);

        authorizationService.requireNotAnnounced(screening.getProgram(), "withdrawing a screening");
        requireNotTerminal(screening, "withdrawing a screening");

        if (screening.getState() != ScreeningState.CREATED) {
            AuditLog.screeningTransitionRejected(screeningId, screening.getState().name(), "WITHDRAWN",
                    requester.getUsername(), "SCREENING_NOT_WITHDRAWABLE");
            throw new ConflictException(
                    "Withdrawal is allowed only while the screening is in CREATED; this one is "
                            + screening.getState(),
                    "SCREENING_NOT_WITHDRAWABLE");
        }

        Long programId = screening.getProgram().getId();
        screeningRepository.delete(screening);
        AuditLog.screeningWithdrawn(screeningId, programId, requester.getUsername());
    }

    // ==================================================================
    // Handler assignment (FR-SCR-16 … FR-SCR-19, ROLE-09)
    // ==================================================================

    /**
     * FR-SCR-16 / FR-SCR-17 / FR-SCR-18: only a PROGRAMMER of the program, only
     * during ASSIGNMENT, and exactly ONE STAFF member — who must actually hold
     * the STAFF role in THIS program.
     *
     * <p>Assignment moves no state, so it does not go through {@link #advance};
     * it is audited through its own line instead.
     * ASSUMPTIONS.md #11 allows re-assignment while the program is still in
     * ASSIGNMENT; #17 keeps a SUBMITTER from handling their own screening.
     */
    @Override
    @Transactional
    public ScreeningResponse assignHandler(Long screeningId, HandlerAssignRequest request) {
        User requester = authorizationService.requireCinemaActor();
        Screening screening = findScreeningOrThrow(screeningId);
        Program program = screening.getProgram();
        authorizationService.requireProgrammerOf(requester, program);

        authorizationService.requireNotAnnounced(program, "assigning a handler");
        requireNotTerminal(screening, "assigning a handler");
        if (program.getState() != ProgramState.ASSIGNMENT) {
            throw new ConflictException(
                    "Handler assignment is allowed only while the program is in ASSIGNMENT; this one is "
                            + program.getState(),
                    "PROGRAM_NOT_IN_ASSIGNMENT");
        }

        User staff = findUserOrThrow(request.getStaffUsername());

        // FR-SCR-18: the named user must really be STAFF of THIS program.
        if (!authorizationService.isStaffOf(staff, program)) {
            throw new ConflictException(
                    "User '" + staff.getUsername() + "' does not hold the STAFF role in this program",
                    "NOT_PROGRAM_STAFF");
        }
        // ASSUMPTIONS.md #17: impartiality — nobody reviews their own submission.
        if (staff.getId().equals(screening.getSubmitter().getId())) {
            throw new ConflictException(
                    "The SUBMITTER of a screening cannot be assigned as its own handler",
                    "HANDLER_IS_SUBMITTER");
        }

        // FR-SCR-18: a single-valued association, so "exactly one" is structural.
        screening.setHandler(staff);
        screening = screeningRepository.save(screening);

        AuditLog.screeningHandlerAssigned(screening.getId(), staff.getUsername(), requester.getUsername());
        return toFullResponse(screening);
    }

    // ==================================================================
    // Review / approval / rejection (FR-SCR-20 … FR-SCR-29, ROLE-08/10)
    // ==================================================================

    /**
     * FR-SCR-20 / FR-SCR-21 / FR-SCR-22 and FR-SCR-T3: SUBMITTED → REVIEWED, by
     * the assigned STAFF handler only, while the program is in REVIEW, recording
     * a numeric score and comments.
     */
    @Override
    @Transactional
    public ScreeningResponse reviewScreening(Long screeningId, ReviewRequest request) {
        User requester = authorizationService.requireCinemaActor();
        Screening screening = findScreeningOrThrow(screeningId);
        authorizationService.requireHandlerOf(requester, screening);

        Program program = screening.getProgram();
        authorizationService.requireNotAnnounced(program, "reviewing a screening");
        requireProgramState(screening, program, ProgramState.REVIEW, requester,
                ScreeningState.REVIEWED, "PROGRAM_NOT_IN_REVIEW",
                "A review is allowed only while the program is in REVIEW; this one is ");

        // advance() runs FIRST so that a refused transition leaves the screening
        // completely untouched — no half-written review on a rolled-back attempt.
        advance(screening, ScreeningState.REVIEWED, requester, "REVIEW");
        screening.setReviewScore(request.getScore());
        screening.setReviewComments(request.getComments());

        screening = screeningRepository.save(screening);
        return toFullResponse(screening);
    }

    /**
     * FR-SCR-23 / FR-SCR-24 / FR-SCR-25 and FR-SCR-T4: REVIEWED → APPROVED, only
     * while the program is in SCHEDULING and <b>only by a PROGRAMMER of the
     * program</b>.
     *
     * <p>The migrated code required the SUBMITTER here (PROJECT_STATE.md, M0
     * finding #2), which contradicts both DOMAIN_RULES.md ("Approval: only in
     * program state SCHEDULING … by a PROGRAMMER") and the assignment's own roles
     * table, and would have let a submitter approve their own submission.
     * ASSUMPTIONS.md #2 resolves it in favour of PROGRAMMER; this is that fix.
     */
    @Override
    @Transactional
    public ScreeningResponse approveScreening(Long screeningId, ApprovalRequest request) {
        User requester = authorizationService.requireCinemaActor();
        Screening screening = findScreeningOrThrow(screeningId);
        Program program = screening.getProgram();
        authorizationService.requireProgrammerOf(requester, program);

        authorizationService.requireNotAnnounced(program, "approving a screening");
        requireProgramState(screening, program, ProgramState.SCHEDULING, requester,
                ScreeningState.APPROVED, "PROGRAM_NOT_IN_SCHEDULING",
                "Approval is allowed only while the program is in SCHEDULING; this one is ");

        advance(screening, ScreeningState.APPROVED, requester, "APPROVE");
        // FR-SCR-25: conditional notes for any required final changes, optional.
        screening.setApprovalNotes(request == null ? null : request.getApprovalNotes());

        screening = screeningRepository.save(screening);
        return toFullResponse(screening);
    }

    /**
     * FR-SCR-26 / FR-SCR-27 / FR-SCR-29 and FR-SCR-T5: manual rejection by a
     * PROGRAMMER — in SCHEDULING on the strength of the review, or in DECISION
     * when the final submission failed to address the required changes.
     *
     * <p>FR-SCR-29 is made structurally unavoidable: the reason is written by
     * {@link #rejectWithReason}, which is the ONLY path to
     * {@link ScreeningState#REJECTED} in this class and refuses a blank reason
     * before touching the state.
     */
    @Override
    @Transactional
    public ScreeningResponse rejectScreening(Long screeningId, RejectionRequest request) {
        User requester = authorizationService.requireCinemaActor();
        Screening screening = findScreeningOrThrow(screeningId);
        Program program = screening.getProgram();
        authorizationService.requireProgrammerOf(requester, program);

        authorizationService.requireNotAnnounced(program, "rejecting a screening");
        requireNotTerminal(screening, "rejecting a screening");

        ProgramState programState = program.getState();
        if (programState != ProgramState.SCHEDULING && programState != ProgramState.DECISION) {
            AuditLog.screeningTransitionRejected(screeningId, screening.getState().name(),
                    ScreeningState.REJECTED.name(), requester.getUsername(), "REJECTION_NOT_ALLOWED_IN_STATE");
            throw new ConflictException(
                    "A manual rejection is allowed only while the program is in SCHEDULING or DECISION; "
                            + "this one is in " + programState,
                    "REJECTION_NOT_ALLOWED_IN_STATE");
        }
        // In DECISION only an APPROVED screening is still open to a manual
        // rejection; a REVIEWED one never reached the final-submission phase.
        if (programState == ProgramState.DECISION && screening.getState() != ScreeningState.APPROVED) {
            AuditLog.screeningTransitionRejected(screeningId, screening.getState().name(),
                    ScreeningState.REJECTED.name(), requester.getUsername(), "REJECTION_NOT_ALLOWED_IN_STATE");
            throw new ConflictException(
                    "In DECISION only an APPROVED screening can be rejected; this one is " + screening.getState(),
                    "REJECTION_NOT_ALLOWED_IN_STATE");
        }

        rejectWithReason(screening, request == null ? null : request.getReason(), requester);

        screening = screeningRepository.save(screening);
        return toFullResponse(screening);
    }

    // ==================================================================
    // Final submission and acceptance (FR-SCR-30 … FR-SCR-36, ROLE-08/12)
    // ==================================================================

    /**
     * FR-SCR-30 / FR-SCR-31 / FR-SCR-32: only the SUBMITTER, only while the
     * program is in FINAL_SUBMISSION, only for an APPROVED screening — and after
     * it succeeds the details are frozen for good.
     *
     * <p>The final submission is not an edge of the state machine: the screening
     * stays APPROVED and merely gains a final-submission date, which is what
     * FR-SCR-36 and the DECISION auto-rejection sweep (FR-SCR-28) both read.
     * Calling it twice is refused by the freeze, which also makes the function
     * non-repeatable in the sense the idempotency rule asks for.
     */
    @Override
    @Transactional
    public ScreeningResponse finalSubmitScreening(Long screeningId, ScreeningUpdateRequest request) {
        User requester = authorizationService.requireCinemaActor();
        Screening screening = findScreeningOrThrow(screeningId);
        authorizationService.requireSubmitterOf(requester, screening);

        Program program = screening.getProgram();
        authorizationService.requireNotAnnounced(program, "finally submitting a screening");
        requireNotTerminal(screening, "finally submitting a screening");
        requireDetailsNotFrozen(screening);

        if (program.getState() != ProgramState.FINAL_SUBMISSION) {
            throw new ConflictException(
                    "The final submission is allowed only while the program is in FINAL_SUBMISSION; this one is "
                            + program.getState(),
                    "PROGRAM_NOT_IN_FINAL_SUBMISSION");
        }
        if (screening.getState() != ScreeningState.APPROVED) {
            throw new ConflictException(
                    "Only an APPROVED screening can be finally submitted; this one is " + screening.getState(),
                    "SCREENING_NOT_APPROVED");
        }

        // The final bundle of changes the approval notes may have asked for.
        applyUpdatableFields(screening, request);
        validateTiming(screening);

        screening.setFinalSubmissionDate(LocalDateTime.now());
        screening = screeningRepository.save(screening);

        AuditLog.screeningFinallySubmitted(screening.getId(), requester.getUsername());
        return toFullResponse(screening);
    }

    /**
     * FR-SCR-34 / FR-SCR-35 / FR-SCR-36 and FR-SCR-T7: APPROVED → SCHEDULED, only
     * in DECISION, only by a PROGRAMMER of the program, and only for a screening
     * that is BOTH approved and finally submitted.
     */
    @Override
    @Transactional
    public ScreeningResponse acceptScreening(Long screeningId) {
        User requester = authorizationService.requireCinemaActor();
        Screening screening = findScreeningOrThrow(screeningId);
        Program program = screening.getProgram();
        authorizationService.requireProgrammerOf(requester, program);

        authorizationService.requireNotAnnounced(program, "accepting a screening");
        requireProgramState(screening, program, ProgramState.DECISION, requester,
                ScreeningState.SCHEDULED, "PROGRAM_NOT_IN_DECISION",
                "Acceptance is allowed only while the program is in DECISION; this one is ");

        // FR-SCR-36. The APPROVED half is judged by advance(); the missing final
        // submission gets its own code so the two reasons stay distinguishable.
        if (screening.getState() == ScreeningState.APPROVED && !screening.isFinallySubmitted()) {
            AuditLog.screeningTransitionRejected(screeningId, screening.getState().name(),
                    ScreeningState.SCHEDULED.name(), requester.getUsername(), "SCREENING_NOT_FINALLY_SUBMITTED");
            throw new ConflictException(
                    "Only an APPROVED screening that was also finally submitted can be scheduled",
                    "SCREENING_NOT_FINALLY_SUBMITTED");
        }

        advance(screening, ScreeningState.SCHEDULED, requester, "ACCEPT");
        screening = screeningRepository.save(screening);
        return toFullResponse(screening);
    }

    // ==================================================================
    // The screening state machine (FR-SCR-T1 … FR-SCR-T8)
    // ==================================================================

    /**
     * The single implementation of one step of the screening state machine, and
     * the only place {@link Screening#setState(ScreeningState)} is called.
     *
     * <p>Every caller has already performed, in this order: the ADMIN/inactive
     * gate, the lookup (404), the actor check (403) and the program-state check
     * (409). What is left for this method is the screening's own legality:
     * <ol>
     *   <li>a terminal state (SCHEDULED / REJECTED) has no outgoing edge at all —
     *       409 {@code SCREENING_TERMINAL_STATE} (FR-SCR-T8);</li>
     *   <li>any other edge missing from {@link #LEGAL_TRANSITIONS} — 409
     *       {@code INVALID_SCREENING_STATE_TRANSITION}.</li>
     * </ol>
     * Both the accepted step and the refusal are audited, so the audit trail
     * shows attempts as well as successes.
     */
    private void advance(Screening screening, ScreeningState target, User actor, String trigger) {
        ScreeningState current = screening.getState();

        if (TERMINAL_STATES.contains(current)) {
            AuditLog.screeningTransitionRejected(screening.getId(), current.name(), target.name(),
                    actor.getUsername(), "SCREENING_TERMINAL_STATE");
            throw new ConflictException(
                    current + " is a terminal state of a screening; no further transition is possible",
                    "SCREENING_TERMINAL_STATE");
        }
        if (!LEGAL_TRANSITIONS.getOrDefault(current, Set.of()).contains(target)) {
            AuditLog.screeningTransitionRejected(screening.getId(), current.name(), target.name(),
                    actor.getUsername(), "INVALID_SCREENING_STATE_TRANSITION");
            throw new ConflictException(
                    "Illegal screening state transition " + current + " -> " + target,
                    "INVALID_SCREENING_STATE_TRANSITION");
        }

        screening.setState(target);
        AuditLog.screeningTransition(screening.getId(), current.name(), target.name(), actor.getUsername(), trigger);
    }

    /**
     * FR-SCR-29: the ONE path to REJECTED inside this service. A rejection
     * without a recorded reason is impossible because the reason is validated
     * here, before the state moves. (The automatic sweep on entering DECISION is
     * the system's own path and records
     * {@code ProgramServiceImpl.AUTO_REJECTION_REASON}; FR-SCR-28 / FR-SCR-T6
     * are implemented there and deliberately not duplicated here.)
     */
    private void rejectWithReason(Screening screening, String reason, User actor) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(
                    "A rejection reason must be recorded for every rejection", "REJECTION_REASON_REQUIRED");
        }
        // The reason is validated before the state moves and written after it, so
        // REJECTED is unreachable without one AND a refused attempt overwrites
        // nothing — including the reason of an earlier, successful rejection.
        advance(screening, ScreeningState.REJECTED, actor, "REJECT");
        screening.setRejectionReason(reason);
    }

    /**
     * The shared program-state gate. Kept as one helper so that the refusal is
     * always a 409 with its own code AND always audited as a refused transition,
     * for every one of the five functions that needs one.
     */
    private void requireProgramState(Screening screening, Program program, ProgramState required, User actor,
                                     ScreeningState intendedTarget, String errorCode, String messagePrefix) {
        if (program.getState() != required) {
            AuditLog.screeningTransitionRejected(screening.getId(), screening.getState().name(),
                    intendedTarget.name(), actor.getUsername(), errorCode);
            throw new ConflictException(messagePrefix + program.getState(), errorCode);
        }
    }

    /**
     * FR-SCR-T8, applied to the functions that do not end in {@link #advance}
     * (update, withdrawal, handler assignment): nothing at all may be done to a
     * SCHEDULED or REJECTED screening.
     */
    private void requireNotTerminal(Screening screening, String what) {
        if (TERMINAL_STATES.contains(screening.getState())) {
            throw new ConflictException(
                    "The screening is " + screening.getState() + ", a terminal state; " + what
                            + " is no longer possible",
                    "SCREENING_TERMINAL_STATE");
        }
    }

    /** FR-SCR-32: once finally submitted, the screening's details never change again. */
    private void requireDetailsNotFrozen(Screening screening) {
        if (screening.isFinallySubmitted()) {
            throw new ConflictException(
                    "The screening was finally submitted on " + screening.getFinalSubmissionDate()
                            + "; its details are frozen",
                    "SCREENING_DETAILS_FROZEN");
        }
    }

    /**
     * ROLE-19 / DOMAIN_RULES.md: "a PROGRAMMER MUST NOT submit screenings in
     * their own program → 409". This is deliberately a {@link ConflictException}
     * and not a {@link gr.aegean.cinema.exception.ForbiddenException}: the rule
     * is pinned to 409 by DOMAIN_RULES.md itself, and it is its own checklist row
     * with its own error code, so it must not collapse into the ordinary 403
     * authorization failures around it.
     */
    private void requireNotProgrammerOfOwnProgram(User requester, Program program) {
        if (authorizationService.isProgrammerOf(requester, program)) {
            AuditLog.accessDenied(requester.getUsername(), "SCREENING_SUBMIT:" + program.getId(),
                    "PROGRAMMER_CANNOT_SUBMIT_IN_OWN_PROGRAM");
            throw new ConflictException(
                    "A PROGRAMMER of this program cannot submit screenings into it",
                    "PROGRAMMER_CANNOT_SUBMIT_IN_OWN_PROGRAM");
        }
    }

    // ==================================================================
    // Search / view (FR-SCR-37 … FR-SCR-46)
    // ==================================================================

    /**
     * FR-SCR-37 … FR-SCR-45. The four optional filters of DOMAIN_RULES.md — film
     * title, cast, genre and a start-time range — are each turned into a partial
     * {@link Specification} and chained with {@code and} on top of the mandatory
     * "belongs to THIS program" predicate, so:
     * <ul>
     *   <li>the search is always scoped to one program (FR-SCR-37);</li>
     *   <li>only the supplied criteria take part, which is both the AND semantics
     *       of FR-SCR-38 and the "no filters → every screening of this program I
     *       may see" of FR-SCR-41;</li>
     *   <li>each text filter is case-insensitive and demands EVERY word entered
     *       (FR-SCR-39/40) through the shared {@code TextSearch} helper.</li>
     * </ul>
     *
     * <p>As on the program side, role filtering runs BEFORE sorting (FR-SCR-42):
     * the visible screenings are selected first, then ordered — by genre and
     * then film title normally (FR-SCR-43/44), or by start time when the caller
     * asks for the timetable view (FR-SCR-45) — and only then redacted for this
     * particular requester (FR-SCR-46).
     */
    @Override
    @Transactional(readOnly = true)
    public List<ScreeningResponse> searchScreenings(Long programId, String title, String cast, String genre,
                                                      LocalDateTime dateFrom, LocalDateTime dateTo, boolean timetable) {
        Program program = findProgramOrThrow(programId);

        Specification<Screening> spec = Specification.where(ScreeningSpecifications.belongsToProgram(program));
        if (title != null && !title.isBlank()) {
            spec = spec.and(ScreeningSpecifications.textFieldContainsAllWords("filmTitle", title));
        }
        if (cast != null && !cast.isBlank()) {
            spec = spec.and(ScreeningSpecifications.textFieldContainsAllWords("filmCast", cast));
        }
        if (genre != null && !genre.isBlank()) {
            spec = spec.and(ScreeningSpecifications.textFieldContainsAllWords("filmGenres", genre));
        }
        if (dateFrom != null) {
            spec = spec.and(ScreeningSpecifications.startTimeFrom(dateFrom));
        }
        if (dateTo != null) {
            spec = spec.and(ScreeningSpecifications.startTimeTo(dateTo));
        }

        User requester = CurrentUserContext.get(); // null for an anonymous VISITOR

        List<ScreeningResponse> results = screeningRepository.findAll(spec).stream()
                .filter(s -> redactionService.isScreeningVisible(s, requester))   // FR-SCR-42
                .sorted(timetable ? BY_START_TIME : BY_GENRE_THEN_TITLE)          // FR-SCR-43/44/45
                .map(s -> redactionService.screening(s, requester))               // FR-SCR-46
                .collect(Collectors.toList());

        AuditLog.searchExecuted(timetable ? "SCREENING_TIMETABLE" : "SCREENING",
                requester == null ? "VISITOR" : requester.getUsername(), results.size());
        return results;
    }

    /**
     * FR-SCR-46: one screening by id, with exactly the fields the requester's
     * role allows. A screening the requester may not see at all is reported as a
     * 404 rather than a 403, so that a draft or rejected submission is not
     * disclosed to an outsider by the choice of status code.
     */
    @Override
    @Transactional(readOnly = true)
    public ScreeningResponse viewScreening(Long screeningId) {
        Screening screening = findScreeningOrThrow(screeningId);
        User requester = CurrentUserContext.get();

        if (!redactionService.isScreeningVisible(screening, requester)) {
            // Do not reveal the existence of a non-public screening to an outsider.
            throw new NotFoundException("No screening exists with id " + screeningId, "SCREENING_NOT_FOUND");
        }
        return redactionService.screening(screening, requester);
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private Program findProgramOrThrow(Long id) {
        return programRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("No program exists with id " + id, "PROGRAM_NOT_FOUND"));
    }

    private Screening findScreeningOrThrow(Long id) {
        return screeningRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("No screening exists with id " + id, "SCREENING_NOT_FOUND"));
    }

    private User findUserOrThrow(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException(
                        "No registered user exists with username " + username, "USER_NOT_FOUND"));
    }

    /**
     * FR-SCR-09: the fields a screening update may change. Note what is NOT here
     * — the program link (FR-SCR-05), the submitter, the state, the review, the
     * approval notes and the final-submission date — none of which a SUBMITTER
     * can reach through an update.
     */
    private void applyUpdatableFields(Screening screening, ScreeningUpdateRequest request) {
        if (request == null) {
            return;
        }
        if (request.getFilmTitle() != null) screening.setFilmTitle(request.getFilmTitle());
        if (request.getFilmCast() != null) screening.setFilmCast(request.getFilmCast());
        if (request.getFilmGenres() != null) screening.setFilmGenres(request.getFilmGenres());
        if (request.getFilmDurationMinutes() != null) screening.setFilmDurationMinutes(request.getFilmDurationMinutes());
        if (request.getAuditoriumName() != null) screening.setAuditoriumName(request.getAuditoriumName());
        if (request.getStartTime() != null) screening.setStartTime(request.getStartTime());
        if (request.getEndTime() != null) screening.setEndTime(request.getEndTime());
    }

    /**
     * FR-SCR-10: "end_time − start_time must be ≥ the film duration, else 400".
     * DOMAIN_RULES.md pins this one refusal to 400 explicitly, so it stays a
     * {@link BadRequestException} even though every state-driven refusal around
     * it is a 409: the numbers in the request itself are wrong, which is what
     * 400 means.
     */
    private void validateTiming(Screening screening) {
        if (screening.getStartTime() == null || screening.getEndTime() == null) {
            return;
        }
        if (!screening.getEndTime().isAfter(screening.getStartTime())) {
            throw new BadRequestException(
                    "The end time of a screening must be after its start time", "INVALID_SCREENING_TIMES");
        }
        if (screening.getFilmDurationMinutes() != null) {
            long slotMinutes = Duration.between(screening.getStartTime(), screening.getEndTime()).toMinutes();
            if (slotMinutes < screening.getFilmDurationMinutes()) {
                throw new BadRequestException(
                        "The slot of " + slotMinutes + " minutes is shorter than the film duration of "
                                + screening.getFilmDurationMinutes() + " minutes",
                        "SCREENING_DURATION_TOO_SHORT");
            }
        }
    }

    /**
     * FR-SCR-13: "complete" means every detail DOMAIN_RULES.md lists for the
     * submission gate — film title, cast, genres, duration, auditorium, start
     * time and end time.
     */
    private boolean isComplete(Screening screening) {
        return isFilled(screening.getFilmTitle())
                && isFilled(screening.getFilmCast())
                && isFilled(screening.getFilmGenres())
                && screening.getFilmDurationMinutes() != null
                && isFilled(screening.getAuditoriumName())
                && screening.getStartTime() != null
                && screening.getEndTime() != null;
    }

    private boolean isFilled(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Every response DTO this service returns is built by the ONE central
     * redaction component (FR-RED-02). The write paths use the full shape
     * directly, because each of them has already proven its caller to be a
     * PROGRAMMER of the program, the screening's SUBMITTER or its assigned
     * handler — the three roles FR-RED-06/07/08 grant a full view to. The read
     * paths let the component decide. No mapping and no visibility logic of any
     * kind lives in this class any more; the migrated private visitor-level
     * mapper, which leaked the lifecycle {@code state} to VISITORs
     * (INVENTORY.md), is gone with it.
     */
    private ScreeningResponse toFullResponse(Screening screening) {
        return redactionService.fullScreening(screening);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
