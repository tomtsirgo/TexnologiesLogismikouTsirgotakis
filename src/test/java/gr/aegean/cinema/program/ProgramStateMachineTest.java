package gr.aegean.cinema.program;

import gr.aegean.cinema.dto.program.ProgramResponse;
import gr.aegean.cinema.dto.program.ProgramStateUpdateRequest;
import gr.aegean.cinema.exception.ConflictException;
import gr.aegean.cinema.exception.ForbiddenException;
import gr.aegean.cinema.exception.NotFoundException;
import gr.aegean.cinema.model.entity.Program;
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
import gr.aegean.cinema.redaction.RedactionService;
import gr.aegean.cinema.security.AuthorizationService;
import gr.aegean.cinema.security.CurrentUserContext;
import gr.aegean.cinema.security.TokenService;
import gr.aegean.cinema.service.impl.ProgramServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The forward-only program state machine: FR-PRG-T1 … FR-PRG-T9 plus ROLE-20.
 *
 * <p>Two rejections that look similar but are different checklist rows are kept
 * apart on purpose here: an UNAUTHORIZED actor attempting any transition is a
 * 403 {@code NOT_PROGRAMMER} (ROLE-20), while an AUTHORIZED PROGRAMMER
 * attempting an ILLEGAL transition is a 409 {@code INVALID_STATE_TRANSITION}
 * (FR-PRG-T8). Collapsing the two would make one of the rows untested.
 */
class ProgramStateMachineTest {

    /** The one legal path through the machine, in order. */
    private static final List<ProgramState> PATH = List.of(
            ProgramState.CREATED, ProgramState.SUBMISSION, ProgramState.ASSIGNMENT, ProgramState.REVIEW,
            ProgramState.SCHEDULING, ProgramState.FINAL_SUBMISSION, ProgramState.DECISION, ProgramState.ANNOUNCED);

    private ProgramRepository programRepository;
    private ProgramRoleRepository programRoleRepository;
    private ScreeningRepository screeningRepository;
    private ProgramServiceImpl programService;

    private User alice;    // PROGRAMMER of the program under test
    private User carol;    // an outsider
    private User admin;
    private Program program;

    @BeforeEach
    void setUp() {
        CurrentUserContext.clear();
        programRepository = mock(ProgramRepository.class);
        programRoleRepository = mock(ProgramRoleRepository.class);
        screeningRepository = mock(ScreeningRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        AuthorizationService authorizationService = new AuthorizationService(
                programRoleRepository, userRepository, mock(TokenService.class));
        RedactionService redactionService =
                new RedactionService(authorizationService, programRoleRepository, screeningRepository);
        programService = new ProgramServiceImpl(programRepository, programRoleRepository, screeningRepository,
                userRepository, authorizationService, redactionService);

        alice = user(1L, "alice01", PermanentRole.USER);
        carol = user(3L, "carol_x", PermanentRole.USER);
        admin = user(99L, "admin1", PermanentRole.ADMIN);

        program = Program.builder()
                .id(10L).name("Summer Fest").description("A summer season")
                .startDate(LocalDate.of(2026, 6, 1)).endDate(LocalDate.of(2026, 6, 30))
                .state(ProgramState.CREATED).creator(alice)
                .build();

        when(programRepository.findById(10L)).thenReturn(Optional.of(program));
        when(programRepository.save(any(Program.class))).thenAnswer(inv -> inv.getArgument(0));
        when(programRoleRepository.findByProgramAndRole(any(), any())).thenReturn(List.of());
        when(programRoleRepository.existsByUserAndProgramAndRole(alice, program, ProgramRoleType.PROGRAMMER))
                .thenReturn(true);
        when(screeningRepository.findByProgramAndState(any(), any())).thenReturn(List.of());
        when(screeningRepository.save(any(Screening.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
    }

    private static User user(long id, String username, PermanentRole role) {
        return User.builder().id(id).username(username).password("H").fullName(username)
                .permanentRole(role).active(true).build();
    }

    private ProgramResponse transitionTo(ProgramState target) {
        ProgramStateUpdateRequest request = new ProgramStateUpdateRequest();
        request.setTargetState(target);
        return programService.updateState(10L, request);
    }

    /** The named function of the assignment that performs each step. */
    private Function<Long, ProgramResponse> namedFunctionFor(ProgramState target) {
        return switch (target) {
            case SUBMISSION -> programService::startSubmission;
            case ASSIGNMENT -> programService::startAssignment;
            case REVIEW -> programService::startReview;
            case SCHEDULING -> programService::startScheduling;
            case FINAL_SUBMISSION -> programService::startFinalSubmission;
            case DECISION -> programService::startDecision;
            case ANNOUNCED -> programService::announce;
            case CREATED -> id -> {
                throw new IllegalArgumentException("CREATED is the initial state, not a transition target");
            };
        };
    }

    private Screening screening(long id, ScreeningState state, boolean finallySubmitted) {
        return Screening.builder()
                .id(id).program(program).submitter(carol).state(state).filmTitle("Film " + id)
                .finalSubmissionDate(finallySubmitted ? LocalDateTime.of(2026, 5, 1, 12, 0) : null)
                .build();
    }

    // ==================================================================
    // FR-PRG-T1 … FR-PRG-T7 — the seven legal steps
    // ==================================================================

    @Test
    @DisplayName("FR-PRG-T1: CREATED -> SUBMISSION is allowed via the submission-start function")
    void t1_createdToSubmission() {
        CurrentUserContext.set(alice);
        program.setState(ProgramState.CREATED);

        assertThat(programService.startSubmission(10L).getState()).isEqualTo(ProgramState.SUBMISSION);
        assertThat(program.getState()).isEqualTo(ProgramState.SUBMISSION);
    }

    @Test
    @DisplayName("FR-PRG-T2: SUBMISSION -> ASSIGNMENT is allowed via the handler-assignment-start function")
    void t2_submissionToAssignment() {
        CurrentUserContext.set(alice);
        program.setState(ProgramState.SUBMISSION);

        assertThat(programService.startAssignment(10L).getState()).isEqualTo(ProgramState.ASSIGNMENT);
    }

    @Test
    @DisplayName("FR-PRG-T3: ASSIGNMENT -> REVIEW is allowed via the review-start function")
    void t3_assignmentToReview() {
        CurrentUserContext.set(alice);
        program.setState(ProgramState.ASSIGNMENT);

        assertThat(programService.startReview(10L).getState()).isEqualTo(ProgramState.REVIEW);
    }

    @Test
    @DisplayName("FR-PRG-T4: REVIEW -> SCHEDULING is allowed via the schedule-making function")
    void t4_reviewToScheduling() {
        CurrentUserContext.set(alice);
        program.setState(ProgramState.REVIEW);

        assertThat(programService.startScheduling(10L).getState()).isEqualTo(ProgramState.SCHEDULING);
    }

    @Test
    @DisplayName("FR-PRG-T5: SCHEDULING -> FINAL_SUBMISSION is allowed via the final-submission-start function")
    void t5_schedulingToFinalSubmission() {
        CurrentUserContext.set(alice);
        program.setState(ProgramState.SCHEDULING);

        assertThat(programService.startFinalSubmission(10L).getState()).isEqualTo(ProgramState.FINAL_SUBMISSION);
    }

    @Test
    @DisplayName("FR-PRG-T6: FINAL_SUBMISSION -> DECISION is allowed via the decision-making function")
    void t6_finalSubmissionToDecision() {
        CurrentUserContext.set(alice);
        program.setState(ProgramState.FINAL_SUBMISSION);

        assertThat(programService.startDecision(10L).getState()).isEqualTo(ProgramState.DECISION);
    }

    @Test
    @DisplayName("FR-PRG-T7: DECISION -> ANNOUNCED is allowed via the announcement function")
    void t7_decisionToAnnounced() {
        CurrentUserContext.set(alice);
        program.setState(ProgramState.DECISION);

        assertThat(programService.announce(10L).getState()).isEqualTo(ProgramState.ANNOUNCED);
    }

    @Test
    @DisplayName("FR-PRG-T1..T7: the whole seven-step path runs end to end, one step at a time")
    void allSevenStepsInSequence() {
        CurrentUserContext.set(alice);
        program.setState(ProgramState.CREATED);

        for (int i = 1; i < PATH.size(); i++) {
            ProgramState target = PATH.get(i);
            ProgramResponse response = namedFunctionFor(target).apply(10L);
            assertThat(response.getState()).isEqualTo(target);
        }

        assertThat(program.getState()).isEqualTo(ProgramState.ANNOUNCED);
        verify(programRepository, times(7)).save(program);
    }

    @Test
    @DisplayName("FR-PRG-T1..T7: the generic state endpoint walks exactly the same seven steps")
    void theGenericEndpointWalksTheSamePath() {
        CurrentUserContext.set(alice);
        program.setState(ProgramState.CREATED);

        for (int i = 1; i < PATH.size(); i++) {
            assertThat(transitionTo(PATH.get(i)).getState()).isEqualTo(PATH.get(i));
        }

        assertThat(program.getState()).isEqualTo(ProgramState.ANNOUNCED);
    }

    // ==================================================================
    // FR-PRG-T6 — the DECISION auto-rejection side effect
    // ==================================================================

    @Test
    @DisplayName("FR-PRG-T6: entering DECISION auto-rejects every APPROVED screening with no final submission")
    void t6_autoRejectsApprovedScreeningsWithoutAFinalSubmission() {
        CurrentUserContext.set(alice);
        program.setState(ProgramState.FINAL_SUBMISSION);
        Screening unfinished = screening(1L, ScreeningState.APPROVED, false);
        when(screeningRepository.findByProgramAndState(program, ScreeningState.APPROVED))
                .thenReturn(List.of(unfinished));

        programService.startDecision(10L);

        assertThat(unfinished.getState()).isEqualTo(ScreeningState.REJECTED);
        verify(screeningRepository).save(unfinished);
    }

    @Test
    @DisplayName("FR-PRG-T6: the automatic rejection records a rejection reason on the screening")
    void t6_autoRejectionRecordsAReason() {
        CurrentUserContext.set(alice);
        program.setState(ProgramState.FINAL_SUBMISSION);
        Screening unfinished = screening(1L, ScreeningState.APPROVED, false);
        when(screeningRepository.findByProgramAndState(program, ScreeningState.APPROVED))
                .thenReturn(List.of(unfinished));

        programService.startDecision(10L);

        assertThat(unfinished.getRejectionReason()).isNotBlank();
        assertThat(unfinished.getRejectionReason()).contains("DECISION");
    }

    @Test
    @DisplayName("FR-PRG-T6: an APPROVED screening that WAS finally submitted survives the DECISION step")
    void t6_finallySubmittedScreeningsAreLeftAlone() {
        CurrentUserContext.set(alice);
        program.setState(ProgramState.FINAL_SUBMISSION);
        Screening finished = screening(1L, ScreeningState.APPROVED, true);
        Screening unfinished = screening(2L, ScreeningState.APPROVED, false);
        when(screeningRepository.findByProgramAndState(program, ScreeningState.APPROVED))
                .thenReturn(List.of(finished, unfinished));

        programService.startDecision(10L);

        assertThat(finished.getState()).isEqualTo(ScreeningState.APPROVED);
        assertThat(finished.getRejectionReason()).isNull();
        assertThat(unfinished.getState()).isEqualTo(ScreeningState.REJECTED);
        verify(screeningRepository, never()).save(finished);
    }

    @Test
    @DisplayName("FR-PRG-T6: the auto-rejection sweep runs on no other transition than the one into DECISION")
    void t6_theSweepRunsOnlyOnTheDecisionStep() {
        CurrentUserContext.set(alice);

        program.setState(ProgramState.CREATED);
        programService.startSubmission(10L);
        programService.startAssignment(10L);
        programService.startReview(10L);
        programService.startScheduling(10L);
        programService.startFinalSubmission(10L);

        verify(screeningRepository, never()).findByProgramAndState(any(), any());

        programService.startDecision(10L);

        verify(screeningRepository).findByProgramAndState(program, ScreeningState.APPROVED);
    }

    // ==================================================================
    // FR-PRG-T8 — illegal transitions
    // ==================================================================

    @ParameterizedTest
    @CsvSource({
            "CREATED,ASSIGNMENT",
            "CREATED,ANNOUNCED",
            "SUBMISSION,REVIEW",
            "ASSIGNMENT,SCHEDULING",
            "REVIEW,FINAL_SUBMISSION",
            "SCHEDULING,DECISION",
            "FINAL_SUBMISSION,ANNOUNCED"
    })
    @DisplayName("FR-PRG-T8: a skip-ahead transition is rejected with 409 and leaves the state untouched")
    void t8_skipAheadIsRejected(ProgramState from, ProgramState to) {
        CurrentUserContext.set(alice);
        program.setState(from);

        assertThatThrownBy(() -> transitionTo(to))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_STATE_TRANSITION");

        assertThat(program.getState()).isEqualTo(from);
        verify(programRepository, never()).save(any(Program.class));
    }

    @ParameterizedTest
    @CsvSource({
            "SUBMISSION,CREATED",
            "ASSIGNMENT,SUBMISSION",
            "REVIEW,ASSIGNMENT",
            "SCHEDULING,REVIEW",
            "FINAL_SUBMISSION,SCHEDULING",
            "DECISION,FINAL_SUBMISSION",
            "ANNOUNCED,DECISION",
            "DECISION,CREATED"
    })
    @DisplayName("FR-PRG-T8: a rollback transition is rejected with 409 and leaves the state untouched")
    void t8_rollbackIsRejected(ProgramState from, ProgramState to) {
        CurrentUserContext.set(alice);
        program.setState(from);

        assertThatThrownBy(() -> transitionTo(to))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_STATE_TRANSITION");

        assertThat(program.getState()).isEqualTo(from);
        verify(programRepository, never()).save(any(Program.class));
    }

    @ParameterizedTest
    @EnumSource(ProgramState.class)
    @DisplayName("FR-PRG-T8: re-entering the state the program is already in is rejected with 409")
    void t8_aNoOpTransitionIsRejected(ProgramState state) {
        CurrentUserContext.set(alice);
        program.setState(state);

        assertThatThrownBy(() -> transitionTo(state))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_STATE_TRANSITION");
    }

    @Test
    @DisplayName("FR-PRG-T8: an illegal transition rolls nothing forward — the DECISION sweep never runs")
    void t8_anIllegalTransitionTriggersNoSideEffect() {
        CurrentUserContext.set(alice);
        program.setState(ProgramState.SCHEDULING);

        assertThatThrownBy(() -> transitionTo(ProgramState.DECISION))
                .isInstanceOf(ConflictException.class);

        verify(screeningRepository, never()).findByProgramAndState(any(), any());
        verify(screeningRepository, never()).save(any(Screening.class));
    }

    @Test
    @DisplayName("FR-PRG-T8: transitioning a program that does not exist is a 404, not a 409")
    void t8_unknownProgramIsNotFound() {
        CurrentUserContext.set(alice);
        when(programRepository.findById(404L)).thenReturn(Optional.empty());

        ProgramStateUpdateRequest request = new ProgramStateUpdateRequest();
        request.setTargetState(ProgramState.SUBMISSION);

        assertThatThrownBy(() -> programService.updateState(404L, request))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PROGRAM_NOT_FOUND");
    }

    // ==================================================================
    // FR-PRG-T9 — ANNOUNCED is terminal and freezes everything
    // ==================================================================

    @ParameterizedTest
    @EnumSource(ProgramState.class)
    @DisplayName("FR-PRG-T9: no transition at all leads out of the terminal ANNOUNCED state")
    void t9_announcedIsTerminal(ProgramState target) {
        CurrentUserContext.set(alice);
        program.setState(ProgramState.ANNOUNCED);

        assertThatThrownBy(() -> transitionTo(target))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_STATE_TRANSITION");

        assertThat(program.getState()).isEqualTo(ProgramState.ANNOUNCED);
    }

    @Test
    @DisplayName("FR-PRG-T9: the ANNOUNCED refusal explains that the state is terminal")
    void t9_theTerminalStateRefusalIsExplained() {
        CurrentUserContext.set(alice);
        program.setState(ProgramState.ANNOUNCED);

        assertThatThrownBy(() -> programService.announce(10L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("terminal");
    }

    // ==================================================================
    // ROLE-20 — an unauthorized actor gets 403, never 409
    // ==================================================================

    @ParameterizedTest
    @EnumSource(value = ProgramState.class, names = "ANNOUNCED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("ROLE-20: a user who is not a PROGRAMMER of the program gets 403 on any state transition")
    void role20_anOutsiderGets403OnEveryLegalStep(ProgramState from) {
        CurrentUserContext.set(carol);
        program.setState(from);
        ProgramState legalTarget = PATH.get(PATH.indexOf(from) + 1);

        assertThatThrownBy(() -> transitionTo(legalTarget))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_PROGRAMMER");

        assertThat(program.getState()).isEqualTo(from);
        verify(programRepository, never()).save(any(Program.class));
    }

    @Test
    @DisplayName("ROLE-20 vs FR-PRG-T8: an outsider attempting an ILLEGAL transition still gets 403, not 409")
    void role20_theActorCheckPrecedesTheLegalityCheck() {
        CurrentUserContext.set(carol);
        program.setState(ProgramState.CREATED);

        assertThatThrownBy(() -> transitionTo(ProgramState.ANNOUNCED))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_PROGRAMMER");
    }

    @Test
    @DisplayName("ROLE-20: an ADMIN attempting a transition is stopped by the cinema-actor gate with 403")
    void role20_anAdminIsStoppedByTheCinemaActorGate() {
        CurrentUserContext.set(admin);

        assertThatThrownBy(() -> programService.startSubmission(10L))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_NOT_ALLOWED");

        assertThat(program.getState()).isEqualTo(ProgramState.CREATED);
        verify(programRepository, never()).findById(any());
    }

    @Test
    @DisplayName("ROLE-20: an unauthenticated caller cannot transition anything")
    void role20_anAnonymousCallerCannotTransitionAnything() {
        assertThatThrownBy(() -> programService.startSubmission(10L))
                .isInstanceOf(gr.aegean.cinema.exception.AuthenticationException.class);

        assertThat(program.getState()).isEqualTo(ProgramState.CREATED);
    }

    @Test
    @DisplayName("ROLE-20: an inactive PROGRAMMER cannot transition their own program")
    void role20_anInactiveProgrammerCannotTransition() {
        alice.setActive(false);
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> programService.startSubmission(10L))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ACCOUNT_INACTIVE");

        assertThat(program.getState()).isEqualTo(ProgramState.CREATED);
    }
}
