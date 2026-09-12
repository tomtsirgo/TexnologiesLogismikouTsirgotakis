package gr.aegean.cinema.service.impl;

import gr.aegean.cinema.dto.program.*;
import gr.aegean.cinema.exception.BadRequestException;
import gr.aegean.cinema.exception.ConflictException;
import gr.aegean.cinema.exception.ForbiddenException;
import gr.aegean.cinema.exception.NotFoundException;
import gr.aegean.cinema.model.entity.Program;
import gr.aegean.cinema.model.entity.ProgramRole;
import gr.aegean.cinema.model.entity.Screening;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.PermanentRole;
import gr.aegean.cinema.model.enums.ProgramRoleType;
import gr.aegean.cinema.model.enums.ProgramState;
import gr.aegean.cinema.model.enums.ScreeningState;
import gr.aegean.cinema.repository.ProgramRepository;
import gr.aegean.cinema.repository.ProgramRoleRepository;
import gr.aegean.cinema.repository.ScreeningRepository;
import gr.aegean.cinema.repository.UserRepository;
import gr.aegean.cinema.repository.spec.ProgramSpecifications;
import gr.aegean.cinema.redaction.RedactionService;
import gr.aegean.cinema.security.AuditLog;
import gr.aegean.cinema.security.AuthorizationService;
import gr.aegean.cinema.security.CurrentUserContext;
import gr.aegean.cinema.service.ProgramService;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implements the whole "Program management" module: creation, update, role
 * management (PROGRAMMER / STAFF), deletion, and the forward-only state
 * machine with its side effects.
 *
 * <h2>Invariants enforced here, once each</h2>
 * <ul>
 *   <li>Every write method starts with
 *       {@link AuthorizationService#requireCinemaActor()} — the single gate that
 *       rejects ADMIN with 403 {@code ADMIN_NOT_ALLOWED} and blocks inactive
 *       accounts (ROLE-14/15/16). Adding a method without it regresses those
 *       rows, so the call is deliberately the first statement every time.</li>
 *   <li>Only a PROGRAMMER <em>of that program</em> may manage or advance it;
 *       anybody else gets a plain 403 {@code NOT_PROGRAMMER} (ROLE-07,
 *       ROLE-20). The authorization check always runs BEFORE the legality
 *       check, so an outsider attempting an illegal transition still sees 403
 *       and never leaks the program's current state through a 409.</li>
 *   <li>Only the immediately following state of {@link #ORDERED_STATES} is
 *       reachable. Skipping ahead, rolling back, or moving out of the terminal
 *       ANNOUNCED state is a 409 {@code INVALID_STATE_TRANSITION}
 *       (FR-PRG-T8) — never a 400, which the migrated code used
 *       (PROJECT_STATE.md, M0 finding #3).</li>
 *   <li>A user holds at most ONE role per program (ROLE-17) but may hold
 *       different roles in different programs (ROLE-18): the check lives in the
 *       single private {@link #grantRole} helper that both
 *       {@link #addProgrammer} and {@link #addStaff} delegate to, backed by the
 *       {@code uk_user_program} database constraint.</li>
 * </ul>
 *
 * <p>Search, view and role-based redaction (FR-PRG-15 … FR-PRG-23) live at the
 * bottom of this class, but they own no mapping or visibility logic of their
 * own: both delegate to the single central
 * {@link gr.aegean.cinema.redaction.RedactionService} (FR-RED-01), so the
 * program and screening domains cannot drift apart in what they disclose.
 */
@Service
public class ProgramServiceImpl implements ProgramService {

    /**
     * The strict, forward-only order of the program state machine
     * (DOMAIN_RULES.md, "Program lifecycle"). A transition is legal if and only
     * if the target sits at exactly {@code currentIndex + 1}.
     */
    private static final List<ProgramState> ORDERED_STATES = List.of(
            ProgramState.CREATED,
            ProgramState.SUBMISSION,
            ProgramState.ASSIGNMENT,
            ProgramState.REVIEW,
            ProgramState.SCHEDULING,
            ProgramState.FINAL_SUBMISSION,
            ProgramState.DECISION,
            ProgramState.ANNOUNCED
    );

    /**
     * FR-PRG-21 / FR-PRG-22: "sorted by date, then by name". Declared once, as a
     * constant, so that the search cannot accidentally be given a different
     * ordering later. Null dates sort last defensively; the column is
     * {@code NOT NULL}, so in practice the comparison is total.
     */
    private static final Comparator<Program> BY_DATE_THEN_NAME =
            Comparator.comparing(Program::getStartDate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(Program::getName, Comparator.nullsLast(Comparator.naturalOrder()));

    /** Recorded on every screening the system rejects by itself on entering DECISION (FR-PRG-T6). */
    static final String AUTO_REJECTION_REASON =
            "Automatically rejected by the system on entering DECISION: the screening was APPROVED "
                    + "but was never finally submitted during the FINAL_SUBMISSION phase";

    private final ProgramRepository programRepository;
    private final ProgramRoleRepository programRoleRepository;
    private final ScreeningRepository screeningRepository;
    private final UserRepository userRepository;
    private final AuthorizationService authorizationService;
    private final RedactionService redactionService;

    public ProgramServiceImpl(ProgramRepository programRepository, ProgramRoleRepository programRoleRepository,
                               ScreeningRepository screeningRepository, UserRepository userRepository,
                               AuthorizationService authorizationService, RedactionService redactionService) {
        this.programRepository = programRepository;
        this.programRoleRepository = programRoleRepository;
        this.screeningRepository = screeningRepository;
        this.userRepository = userRepository;
        this.authorizationService = authorizationService;
        this.redactionService = redactionService;
    }

    // ==================================================================
    // Creation / update / deletion
    // ==================================================================

    /**
     * FR-PRG-01 … FR-PRG-06 and ROLE-04: any authenticated, active, non-ADMIN
     * account may create a program. The name must be unique (FR-PRG-01,
     * case-sensitive per ASSUMPTIONS.md #12), id and creation date are
     * generated by the persistence layer (FR-PRG-03/04), the initial state is
     * CREATED (FR-PRG-05) and the creator is recorded and immediately granted
     * the PROGRAMMER role (FR-PRG-06).
     */
    @Override
    @Transactional
    public ProgramResponse createProgram(ProgramCreateRequest request) {
        User requester = authorizationService.requireCinemaActor();

        if (programRepository.existsByName(request.getName())) {
            throw new ConflictException(
                    "A program named '" + request.getName() + "' already exists", "PROGRAM_NAME_TAKEN");
        }
        requireOrderedDates(request.getStartDate(), request.getEndDate());

        Program program = Program.builder()
                .name(request.getName())
                .description(request.getDescription())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .state(ProgramState.CREATED)
                .creator(requester)
                .build();
        program = programRepository.save(program);

        // FR-PRG-06: the creator is a PROGRAMMER from the very first moment.
        ProgramRole role = ProgramRole.builder()
                .user(requester).program(program).role(ProgramRoleType.PROGRAMMER).build();
        programRoleRepository.save(role);
        program.getProgramRoles().add(role);

        AuditLog.programCreated(program.getId(), program.getName(), requester.getUsername());
        AuditLog.programRoleAdded(program.getId(), ProgramRoleType.PROGRAMMER.name(),
                requester.getUsername(), requester.getUsername());
        return toFullResponse(program);
    }

    /**
     * FR-PRG-07 and FR-PRG-09: a PROGRAMMER of the program may change its name,
     * description and dates, but only while the program has not yet been
     * ANNOUNCED — after that everything is frozen (FR-PRG-T9).
     */
    @Override
    @Transactional
    public ProgramResponse updateProgram(Long programId, ProgramUpdateRequest request) {
        User requester = authorizationService.requireCinemaActor();
        Program program = findProgramOrThrow(programId);
        authorizationService.requireProgrammerOf(requester, program);
        authorizationService.requireNotAnnounced(program, "updating the program");

        if (request.getName() != null && !request.getName().equals(program.getName())) {
            if (programRepository.existsByName(request.getName())) {
                throw new ConflictException(
                        "A program named '" + request.getName() + "' already exists", "PROGRAM_NAME_TAKEN");
            }
            program.setName(request.getName());
        }
        if (request.getDescription() != null) {
            program.setDescription(request.getDescription());
        }
        if (request.getStartDate() != null) {
            program.setStartDate(request.getStartDate());
        }
        if (request.getEndDate() != null) {
            program.setEndDate(request.getEndDate());
        }
        requireOrderedDates(program.getStartDate(), program.getEndDate());

        program = programRepository.save(program);
        AuditLog.programUpdated(program.getId(), requester.getUsername(), program.getState().name());
        return toFullResponse(program);
    }

    /**
     * FR-PRG-24 / FR-PRG-25: deletion is allowed only while the program is
     * still in CREATED, and only to a PROGRAMMER of that program. The actor
     * check runs first, so a stranger gets 403 rather than learning the
     * program's state from a 409.
     */
    @Override
    @Transactional
    public void deleteProgram(Long programId) {
        User requester = authorizationService.requireCinemaActor();
        Program program = findProgramOrThrow(programId);
        authorizationService.requireProgrammerOf(requester, program);

        if (program.getState() != ProgramState.CREATED) {
            throw new ConflictException(
                    "A program can be deleted only while it is in the CREATED state; this one is "
                            + program.getState(),
                    "PROGRAM_NOT_DELETABLE");
        }

        String name = program.getName();
        programRepository.delete(program);
        AuditLog.programDeleted(programId, name, requester.getUsername());
    }

    // ==================================================================
    // Role management (ROLE-17 / ROLE-18)
    // ==================================================================

    /**
     * FR-PRG-10 / FR-PRG-11: only an existing PROGRAMMER of the program may add
     * another PROGRAMMER, and the target must not already hold any role in it.
     * The PROGRAMMERS set is NOT frozen by the SUBMISSION state — only the
     * STAFF set is (DOMAIN_RULES.md) — so this stays open until ANNOUNCED.
     */
    @Override
    @Transactional
    public ProgramResponse addProgrammer(Long programId, AddRoleRequest request) {
        User requester = authorizationService.requireCinemaActor();
        Program program = findProgramOrThrow(programId);
        authorizationService.requireProgrammerOf(requester, program);
        authorizationService.requireNotAnnounced(program, "changing the PROGRAMMERS set");

        return grantRole(program, request.getUsername(), ProgramRoleType.PROGRAMMER, requester);
    }

    /**
     * FR-PRG-12 / FR-PRG-13 / FR-PRG-14: only an existing PROGRAMMER may add a
     * STAFF member, the target must hold no other role in the program, and the
     * STAFF set is frozen the moment the program leaves CREATED.
     */
    @Override
    @Transactional
    public ProgramResponse addStaff(Long programId, AddRoleRequest request) {
        User requester = authorizationService.requireCinemaActor();
        Program program = findProgramOrThrow(programId);
        authorizationService.requireProgrammerOf(requester, program);
        requireStaffSetOpen(program);

        return grantRole(program, request.getUsername(), ProgramRoleType.STAFF, requester);
    }

    /**
     * FR-PRG-08: a PROGRAMMER may be removed from the program by another
     * PROGRAMMER, EXCEPT the creator, who can never be removed from the
     * PROGRAMMERS set under any circumstance.
     */
    @Override
    @Transactional
    public ProgramResponse removeProgrammer(Long programId, String username) {
        User requester = authorizationService.requireCinemaActor();
        Program program = findProgramOrThrow(programId);
        authorizationService.requireProgrammerOf(requester, program);
        authorizationService.requireNotAnnounced(program, "changing the PROGRAMMERS set");

        User target = findUserOrThrow(username);

        // FR-PRG-08. Modelled as 403 rather than 409 for the same reason as the
        // non-deletable ADMIN account (ASSUMPTIONS.md #26): no later request can
        // ever make it succeed, so it is a permanent prohibition, not a
        // transient conflict with the current data state.
        if (program.getCreator() != null && program.getCreator().getId().equals(target.getId())) {
            AuditLog.accessDenied(requester.getUsername(), "PROGRAM_ROLE_REMOVE:" + programId,
                    "CREATOR_NOT_REMOVABLE");
            throw new ForbiddenException(
                    "The creator of a program can never be removed from its PROGRAMMERS set",
                    "CREATOR_NOT_REMOVABLE");
        }

        revokeRole(program, target, ProgramRoleType.PROGRAMMER, requester);
        return toFullResponse(program);
    }

    /**
     * FR-PRG-14: removing a STAFF member is subject to exactly the same freeze
     * as adding one — DOMAIN_RULES.md says "no additions or removals after the
     * SUBMISSION state is reached".
     */
    @Override
    @Transactional
    public ProgramResponse removeStaff(Long programId, String username) {
        User requester = authorizationService.requireCinemaActor();
        Program program = findProgramOrThrow(programId);
        authorizationService.requireProgrammerOf(requester, program);
        requireStaffSetOpen(program);

        User target = findUserOrThrow(username);
        revokeRole(program, target, ProgramRoleType.STAFF, requester);
        return toFullResponse(program);
    }

    // ==================================================================
    // State machine (FR-PRG-T1 … FR-PRG-T9)
    // ==================================================================

    @Override
    @Transactional
    public ProgramResponse updateState(Long programId, ProgramStateUpdateRequest request) {
        return advance(programId, request.getTargetState());
    }

    @Override
    @Transactional
    public ProgramResponse startSubmission(Long programId) {
        return advance(programId, ProgramState.SUBMISSION);
    }

    @Override
    @Transactional
    public ProgramResponse startAssignment(Long programId) {
        return advance(programId, ProgramState.ASSIGNMENT);
    }

    @Override
    @Transactional
    public ProgramResponse startReview(Long programId) {
        return advance(programId, ProgramState.REVIEW);
    }

    @Override
    @Transactional
    public ProgramResponse startScheduling(Long programId) {
        return advance(programId, ProgramState.SCHEDULING);
    }

    @Override
    @Transactional
    public ProgramResponse startFinalSubmission(Long programId) {
        return advance(programId, ProgramState.FINAL_SUBMISSION);
    }

    @Override
    @Transactional
    public ProgramResponse startDecision(Long programId) {
        return advance(programId, ProgramState.DECISION);
    }

    @Override
    @Transactional
    public ProgramResponse announce(Long programId) {
        return advance(programId, ProgramState.ANNOUNCED);
    }

    /**
     * The single implementation of one forward step, shared by the generic
     * {@link #updateState} and by all seven named transition functions.
     *
     * <p>Order of the checks matters and is part of the specification:
     * <ol>
     *   <li>ADMIN / inactive gate (403 {@code ADMIN_NOT_ALLOWED} /
     *       {@code ACCOUNT_INACTIVE});</li>
     *   <li>the program must exist (404);</li>
     *   <li>the actor must be a PROGRAMMER of it — ROLE-20, 403
     *       {@code NOT_PROGRAMMER}, which is a DIFFERENT row of the checklist
     *       from the next one and must not collapse into it;</li>
     *   <li>only then is the transition's legality judged — FR-PRG-T8, 409
     *       {@code INVALID_STATE_TRANSITION}.</li>
     * </ol>
     * Because the whole method is {@code @Transactional}, the DECISION side
     * effect and the program's own state change either both persist or neither
     * does.
     */
    private ProgramResponse advance(Long programId, ProgramState target) {
        User requester = authorizationService.requireCinemaActor();
        Program program = findProgramOrThrow(programId);
        authorizationService.requireProgrammerOf(requester, program);

        ProgramState current = program.getState();
        if (!isLegalStep(current, target)) {
            AuditLog.programTransitionRejected(programId, current.name(), String.valueOf(target),
                    requester.getUsername(), "INVALID_STATE_TRANSITION");
            throw new ConflictException(illegalTransitionMessage(current, target), "INVALID_STATE_TRANSITION");
        }

        if (current == ProgramState.FINAL_SUBMISSION && target == ProgramState.DECISION) {
            autoRejectUnfinishedApprovedScreenings(program);
        }

        program.setState(target);
        program = programRepository.save(program);

        AuditLog.programTransition(programId, current.name(), target.name(), requester.getUsername());
        return toFullResponse(program);
    }

    /**
     * FR-PRG-T8: forward-only, one step at a time. Everything else — a skip
     * ahead, any rollback, a no-op onto the current state, and any attempt to
     * leave the terminal ANNOUNCED state (FR-PRG-T9) — is illegal, because for
     * ANNOUNCED there simply is no {@code currentIndex + 1}.
     */
    private boolean isLegalStep(ProgramState current, ProgramState target) {
        if (target == null) {
            return false;
        }
        int currentIndex = ORDERED_STATES.indexOf(current);
        int targetIndex = ORDERED_STATES.indexOf(target);
        return currentIndex >= 0 && targetIndex == currentIndex + 1;
    }

    private String illegalTransitionMessage(ProgramState current, ProgramState target) {
        if (current == ProgramState.ANNOUNCED) {
            return "ANNOUNCED is the terminal state of a program; no further transition is possible";
        }
        ProgramState onlyLegal = ORDERED_STATES.get(ORDERED_STATES.indexOf(current) + 1);
        return "Illegal program state transition " + current + " -> " + target
                + "; the only legal next state is " + onlyLegal;
    }

    /**
     * FR-PRG-T6 / DOMAIN_RULES.md: "on entering DECISION, every APPROVED
     * screening without a final submission is auto-REJECTED, with the rejection
     * reason recorded". Runs inside the transition's transaction, so a failure
     * anywhere leaves neither the rejections nor the new program state behind.
     */
    private void autoRejectUnfinishedApprovedScreenings(Program program) {
        List<Screening> approved = screeningRepository.findByProgramAndState(program, ScreeningState.APPROVED);
        for (Screening screening : approved) {
            if (screening.isFinallySubmitted()) {
                continue;
            }
            screening.setState(ScreeningState.REJECTED);
            screening.setRejectionReason(AUTO_REJECTION_REASON);
            screeningRepository.save(screening);
            AuditLog.screeningAutoRejected(screening.getId(), program.getId(), AUTO_REJECTION_REASON);
        }
    }

    // ==================================================================
    // Search / view (FR-PRG-15 … FR-PRG-23)
    // ==================================================================

    /**
     * FR-PRG-15 … FR-PRG-22. The five optional filters of DOMAIN_RULES.md — name,
     * description, dates, film title, auditorium — are each turned into a partial
     * {@link Specification} and chained with {@code and}, so:
     * <ul>
     *   <li>only the criteria actually supplied take part, which is both the AND
     *       semantics of FR-PRG-16 and the "no criteria → everything I may see"
     *       of FR-PRG-17;</li>
     *   <li>each text filter is case-insensitive and demands EVERY word entered
     *       (FR-PRG-18/19), because all of them go through the shared
     *       {@code TextSearch.containsAllWords} helper.</li>
     * </ul>
     *
     * <p>The order of the three stream stages is itself specified: role filtering
     * happens FIRST (FR-PRG-20, via the central redaction layer), the surviving
     * programs are then sorted by date and then by name (FR-PRG-21/22), and only
     * then is each one redacted for this particular requester. Sorting after
     * filtering — rather than the other way round — is what keeps a hidden
     * program from influencing the visible ordering.
     *
     * <p>{@code readOnly} transactional so that the lazy associations the
     * redaction layer reads stay reachable while the response is built
     * ({@code spring.jpa.open-in-view=false}).
     */
    @Override
    @Transactional(readOnly = true)
    public List<ProgramResponse> searchPrograms(String name, String description, LocalDate startFrom, LocalDate endTo,
                                                 String filmTitle, String auditorium) {
        Specification<Program> spec = Specification.where(null);
        if (name != null && !name.isBlank()) {
            spec = spec.and(ProgramSpecifications.nameContains(name));
        }
        if (description != null && !description.isBlank()) {
            spec = spec.and(ProgramSpecifications.descriptionContains(description));
        }
        if (startFrom != null) {
            spec = spec.and(ProgramSpecifications.startsFrom(startFrom));
        }
        if (endTo != null) {
            spec = spec.and(ProgramSpecifications.endsBefore(endTo));
        }
        if (filmTitle != null && !filmTitle.isBlank()) {
            spec = spec.and(ProgramSpecifications.hasFilmTitle(filmTitle));
        }
        if (auditorium != null && !auditorium.isBlank()) {
            spec = spec.and(ProgramSpecifications.hasAuditorium(auditorium));
        }

        User requester = CurrentUserContext.get(); // null for an anonymous VISITOR

        List<ProgramResponse> results = programRepository.findAll(spec).stream()
                .filter(program -> redactionService.isProgramVisible(program, requester))   // FR-PRG-20
                .sorted(BY_DATE_THEN_NAME)                                                  // FR-PRG-21/22
                .map(program -> redactionService.program(program, requester))               // FR-PRG-23
                .collect(Collectors.toList());

        AuditLog.searchExecuted("PROGRAM", actorName(requester), results.size());
        return results;
    }

    /**
     * FR-PRG-23: one program by id, with exactly the fields the requester's role
     * allows. A program the requester may not see at all is reported as a 404
     * rather than a 403, so that the mere existence of a programme still being
     * prepared is not disclosed to an outsider.
     */
    @Override
    @Transactional(readOnly = true)
    public ProgramResponse viewProgram(Long programId) {
        Program program = findProgramOrThrow(programId);
        User requester = CurrentUserContext.get();

        if (!redactionService.isProgramVisible(program, requester)) {
            // Do not reveal the existence of a non-announced program to an outsider.
            throw new NotFoundException("No program exists with id " + programId, "PROGRAM_NOT_FOUND");
        }

        return redactionService.program(program, requester);
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /**
     * The ONE place a program role is created. Keeping it single-sourced is what
     * makes ROLE-17 ("at most one role per program") impossible to forget when a
     * new role-granting entry point is added, and it is also where ADMIN is kept
     * out of the cinema domain as a TARGET rather than as an actor
     * (ASSUMPTIONS.md #13).
     *
     * <p>ROLE-18 falls out for free: the uniqueness check is scoped to
     * {@code (user, program)}, so the same user may hold a different role in a
     * different program.
     */
    private ProgramResponse grantRole(Program program, String username, ProgramRoleType roleType, User actor) {
        User target = findUserOrThrow(username);

        if (target.getPermanentRole() == PermanentRole.ADMIN) {
            throw new ForbiddenException(
                    "An ADMIN account cannot hold a role inside a program", "ADMIN_NOT_ALLOWED");
        }
        // ROLE-17 / FR-PRG-11 / FR-PRG-13. The uk_user_program database constraint
        // backs this up; the check is here so the caller gets a specific code
        // instead of a constraint-violation 500.
        if (programRoleRepository.existsByUserAndProgram(target, program)) {
            throw new ConflictException(
                    "User '" + username + "' already holds a role in this program; "
                            + "a user may hold at most one role per program",
                    "ROLE_ALREADY_ASSIGNED");
        }

        ProgramRole role = ProgramRole.builder().user(target).program(program).role(roleType).build();
        programRoleRepository.save(role);
        program.getProgramRoles().add(role);

        AuditLog.programRoleAdded(program.getId(), roleType.name(), target.getUsername(), actor.getUsername());
        return toFullResponse(program);
    }

    /** The counterpart of {@link #grantRole}: the one place a program role is revoked. */
    private void revokeRole(Program program, User target, ProgramRoleType expectedRole, User actor) {
        ProgramRole role = programRoleRepository.findByUserAndProgram(target, program)
                .filter(pr -> pr.getRole() == expectedRole)
                .orElseThrow(() -> new NotFoundException(
                        "User '" + target.getUsername() + "' does not hold the " + expectedRole
                                + " role in this program",
                        "ROLE_NOT_ASSIGNED"));

        programRoleRepository.delete(role);
        program.getProgramRoles().removeIf(pr -> pr.getId() != null && pr.getId().equals(role.getId()));

        AuditLog.programRoleRemoved(program.getId(), expectedRole.name(), target.getUsername(), actor.getUsername());
    }

    /**
     * FR-PRG-14. DOMAIN_RULES.md: "STAFF set is frozen once the program leaves
     * CREATED for SUBMISSION — no additions or removals after the SUBMISSION
     * state is reached". The freeze therefore holds in EVERY state after
     * CREATED, not only in SUBMISSION itself.
     */
    private void requireStaffSetOpen(Program program) {
        if (program.getState() != ProgramState.CREATED) {
            throw new ConflictException(
                    "The STAFF set is frozen once the program leaves CREATED; the program is now in "
                            + program.getState(),
                    "STAFF_SET_FROZEN");
        }
    }

    private void requireOrderedDates(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new BadRequestException(
                    "The end date of a program cannot precede its start date", "INVALID_PROGRAM_DATES");
        }
    }

    private Program findProgramOrThrow(Long id) {
        return programRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("No program exists with id " + id, "PROGRAM_NOT_FOUND"));
    }

    private User findUserOrThrow(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException(
                        "No registered user exists with username " + username, "USER_NOT_FOUND"));
    }

    private static String actorName(User requester) {
        return requester == null ? "VISITOR" : requester.getUsername();
    }

    /**
     * Every response DTO this service returns is built by the ONE central
     * redaction component (FR-RED-01). The write paths use the full shape
     * directly, because each of them has already proven its caller to be a
     * PROGRAMMER of the program (FR-RED-05); the read paths ask the component to
     * decide. No mapping code of any kind lives in this class any more.
     */
    private ProgramResponse toFullResponse(Program program) {
        return redactionService.fullProgram(program);
    }
}
