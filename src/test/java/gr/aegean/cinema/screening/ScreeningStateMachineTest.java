package gr.aegean.cinema.screening;

import gr.aegean.cinema.dto.screening.ApprovalRequest;
import gr.aegean.cinema.dto.screening.HandlerAssignRequest;
import gr.aegean.cinema.dto.screening.RejectionRequest;
import gr.aegean.cinema.dto.screening.ReviewRequest;
import gr.aegean.cinema.dto.screening.ScreeningUpdateRequest;
import gr.aegean.cinema.exception.ConflictException;
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
import gr.aegean.cinema.service.impl.ScreeningServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The screening state machine on its own: FR-SCR-T1 … FR-SCR-T8.
 *
 * <p>FR-SCR-T6 (the automatic rejection on entering DECISION) is implemented by
 * {@code ProgramServiceImpl.autoRejectUnfinishedApprovedScreenings} and is proven
 * from the screening side by {@link ScreeningAutoRejectionTest}, so that the one
 * implementation is not duplicated here.
 *
 * <p>Two refusals that look alike are kept apart deliberately: a WRONG ACTOR is
 * always 403 (that is {@link ScreeningLifecycleServiceTest}'s job), while an
 * authorized actor attempting an ILLEGAL EDGE is 409
 * {@code INVALID_SCREENING_STATE_TRANSITION} — or, out of a terminal state, 409
 * {@code SCREENING_TERMINAL_STATE}.
 */
class ScreeningStateMachineTest {

    private ScreeningRepository screeningRepository;
    private ProgramRoleRepository programRoleRepository;
    private ScreeningServiceImpl screeningService;

    private User alice;   // PROGRAMMER of the program
    private User bob;     // SUBMITTER of the screening
    private User dave;    // STAFF, the assigned handler

    private Program program;
    private Screening screening;

    @BeforeEach
    void setUp() {
        CurrentUserContext.clear();
        screeningRepository = mock(ScreeningRepository.class);
        ProgramRepository programRepository = mock(ProgramRepository.class);
        programRoleRepository = mock(ProgramRoleRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        AuthorizationService authorizationService = new AuthorizationService(
                programRoleRepository, userRepository, mock(TokenService.class));
        screeningService = new ScreeningServiceImpl(
                screeningRepository, programRepository, userRepository, authorizationService,
                new RedactionService(authorizationService, programRoleRepository, screeningRepository));

        alice = user(1L, "alice01");
        bob = user(2L, "bob2024");
        dave = user(4L, "dave123");

        program = Program.builder()
                .id(10L).name("Summer Fest").description("A summer season")
                .startDate(LocalDate.of(2026, 6, 1)).endDate(LocalDate.of(2026, 6, 30))
                .state(ProgramState.SUBMISSION).creator(alice)
                .build();

        screening = Screening.builder()
                .id(100L).program(program).submitter(bob).handler(dave).state(ScreeningState.CREATED)
                .filmTitle("Star Wars").filmCast("Mark Hamill").filmGenres("SciFi")
                .filmDurationMinutes(120).auditoriumName("Hall A")
                .startTime(LocalDateTime.of(2026, 6, 10, 20, 0))
                .endTime(LocalDateTime.of(2026, 6, 10, 22, 30))
                .build();

        when(programRepository.findById(10L)).thenReturn(Optional.of(program));
        when(screeningRepository.findById(100L)).thenReturn(Optional.of(screening));
        when(screeningRepository.save(any(Screening.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findByUsername("dave123")).thenReturn(Optional.of(dave));
        when(programRoleRepository.existsByUserAndProgramAndRole(alice, program, ProgramRoleType.PROGRAMMER))
                .thenReturn(true);
        when(programRoleRepository.existsByUserAndProgramAndRole(dave, program, ProgramRoleType.STAFF))
                .thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
    }

    private static User user(long id, String username) {
        return User.builder().id(id).username(username).password("H").fullName(username)
                .permanentRole(PermanentRole.USER).active(true).build();
    }

    private RejectionRequest rejection(String reason) {
        RejectionRequest request = new RejectionRequest();
        request.setReason(reason);
        return request;
    }

    private ReviewRequest review() {
        ReviewRequest request = new ReviewRequest();
        request.setScore(8);
        request.setComments("A solid fit for the season");
        return request;
    }

    /** Puts the fixture into the {@code (programState, screeningState)} pair a test needs. */
    private void positionAt(ProgramState programState, ScreeningState screeningState) {
        program.setState(programState);
        screening.setState(screeningState);
    }

    // ==================================================================
    // FR-SCR-T1 … FR-SCR-T7 — every legal edge
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-T1: CREATED -> SUBMITTED happens only through the submission function")
    void t1_createdToSubmitted() {
        positionAt(ProgramState.SUBMISSION, ScreeningState.CREATED);
        CurrentUserContext.set(bob);

        assertThat(screeningService.submitScreening(100L).getState()).isEqualTo(ScreeningState.SUBMITTED);
        assertThat(screening.getState()).isEqualTo(ScreeningState.SUBMITTED);
    }

    @Test
    @DisplayName("FR-SCR-T2: a CREATED screening leaves the system only through the withdrawal function, by deletion")
    void t2_createdIsRemovedByWithdrawal() {
        positionAt(ProgramState.SUBMISSION, ScreeningState.CREATED);
        CurrentUserContext.set(bob);

        screeningService.withdrawScreening(100L);

        verify(screeningRepository).delete(screening);
        verify(screeningRepository, never()).save(any(Screening.class));
    }

    @Test
    @DisplayName("FR-SCR-T3: SUBMITTED -> REVIEWED happens only through the review function")
    void t3_submittedToReviewed() {
        positionAt(ProgramState.REVIEW, ScreeningState.SUBMITTED);
        CurrentUserContext.set(dave);

        assertThat(screeningService.reviewScreening(100L, review()).getState())
                .isEqualTo(ScreeningState.REVIEWED);
    }

    @Test
    @DisplayName("FR-SCR-T4: REVIEWED -> APPROVED happens only through the approval function")
    void t4_reviewedToApproved() {
        positionAt(ProgramState.SCHEDULING, ScreeningState.REVIEWED);
        CurrentUserContext.set(alice);

        assertThat(screeningService.approveScreening(100L, new ApprovalRequest()).getState())
                .isEqualTo(ScreeningState.APPROVED);
    }

    @Test
    @DisplayName("FR-SCR-T5: REVIEWED -> REJECTED happens through a manual rejection by a PROGRAMMER")
    void t5_reviewedToRejected() {
        positionAt(ProgramState.SCHEDULING, ScreeningState.REVIEWED);
        CurrentUserContext.set(alice);

        assertThat(screeningService.rejectScreening(100L, rejection("Weak review")).getState())
                .isEqualTo(ScreeningState.REJECTED);
    }

    @Test
    @DisplayName("FR-SCR-T5: APPROVED -> REJECTED happens through a manual rejection by a PROGRAMMER")
    void t5_approvedToRejected() {
        positionAt(ProgramState.DECISION, ScreeningState.APPROVED);
        screening.setFinalSubmissionDate(LocalDateTime.of(2026, 5, 20, 12, 0));
        CurrentUserContext.set(alice);

        assertThat(screeningService.rejectScreening(100L, rejection("Required changes not addressed")).getState())
                .isEqualTo(ScreeningState.REJECTED);
    }

    @Test
    @DisplayName("FR-SCR-T7: APPROVED -> SCHEDULED happens only through acceptance, and only when finally submitted")
    void t7_approvedToScheduled() {
        positionAt(ProgramState.DECISION, ScreeningState.APPROVED);
        screening.setFinalSubmissionDate(LocalDateTime.of(2026, 5, 20, 12, 0));
        CurrentUserContext.set(alice);

        assertThat(screeningService.acceptScreening(100L).getState()).isEqualTo(ScreeningState.SCHEDULED);
    }

    @Test
    @DisplayName("FR-SCR-T1/T3/T4/T7: the whole happy path CREATED -> SUBMITTED -> REVIEWED -> APPROVED -> SCHEDULED runs end to end")
    void theWholeHappyPathRunsEndToEnd() {
        positionAt(ProgramState.SUBMISSION, ScreeningState.CREATED);

        CurrentUserContext.set(bob);
        assertThat(screeningService.submitScreening(100L).getState()).isEqualTo(ScreeningState.SUBMITTED);

        program.setState(ProgramState.REVIEW);
        CurrentUserContext.set(dave);
        assertThat(screeningService.reviewScreening(100L, review()).getState()).isEqualTo(ScreeningState.REVIEWED);

        program.setState(ProgramState.SCHEDULING);
        CurrentUserContext.set(alice);
        assertThat(screeningService.approveScreening(100L, new ApprovalRequest()).getState())
                .isEqualTo(ScreeningState.APPROVED);

        program.setState(ProgramState.FINAL_SUBMISSION);
        CurrentUserContext.set(bob);
        assertThat(screeningService.finalSubmitScreening(100L, new ScreeningUpdateRequest()).getFinallySubmitted())
                .isTrue();

        program.setState(ProgramState.DECISION);
        CurrentUserContext.set(alice);
        assertThat(screeningService.acceptScreening(100L).getState()).isEqualTo(ScreeningState.SCHEDULED);
    }

    // ==================================================================
    // Illegal edges — 409 INVALID_SCREENING_STATE_TRANSITION
    // ==================================================================

    @ParameterizedTest
    @EnumSource(value = ScreeningState.class, names = "CREATED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("FR-SCR-T1: nothing but a CREATED screening can be submitted")
    void t1_onlyCreatedCanBeSubmitted(ScreeningState from) {
        positionAt(ProgramState.SUBMISSION, from);
        CurrentUserContext.set(bob);

        assertThatThrownBy(() -> screeningService.submitScreening(100L))
                .isInstanceOf(ConflictException.class);

        assertThat(screening.getState()).isEqualTo(from);
    }

    @ParameterizedTest
    @EnumSource(value = ScreeningState.class, names = {"CREATED", "REVIEWED", "APPROVED"})
    @DisplayName("FR-SCR-T3: nothing but a SUBMITTED screening can be reviewed")
    void t3_onlySubmittedCanBeReviewed(ScreeningState from) {
        positionAt(ProgramState.REVIEW, from);
        CurrentUserContext.set(dave);

        assertThatThrownBy(() -> screeningService.reviewScreening(100L, review()))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_SCREENING_STATE_TRANSITION");

        assertThat(screening.getState()).isEqualTo(from);
    }

    @ParameterizedTest
    @EnumSource(value = ScreeningState.class, names = {"CREATED", "SUBMITTED", "APPROVED"})
    @DisplayName("FR-SCR-T4: nothing but a REVIEWED screening can be approved")
    void t4_onlyReviewedCanBeApproved(ScreeningState from) {
        positionAt(ProgramState.SCHEDULING, from);
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.approveScreening(100L, new ApprovalRequest()))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_SCREENING_STATE_TRANSITION");

        assertThat(screening.getState()).isEqualTo(from);
    }

    @ParameterizedTest
    @EnumSource(value = ScreeningState.class, names = {"CREATED", "SUBMITTED"})
    @DisplayName("FR-SCR-T5: only a REVIEWED or an APPROVED screening can be rejected manually")
    void t5_onlyReviewedOrApprovedCanBeRejected(ScreeningState from) {
        positionAt(ProgramState.SCHEDULING, from);
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.rejectScreening(100L, rejection("no")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_SCREENING_STATE_TRANSITION");

        assertThat(screening.getState()).isEqualTo(from);
        assertThat(screening.getRejectionReason()).isNull();
    }

    @Test
    @DisplayName("FR-SCR-T4: a screening cannot be approved twice — the second attempt is an illegal edge")
    void t4_approvalIsNotRepeatable() {
        positionAt(ProgramState.SCHEDULING, ScreeningState.REVIEWED);
        CurrentUserContext.set(alice);

        screeningService.approveScreening(100L, new ApprovalRequest());

        assertThatThrownBy(() -> screeningService.approveScreening(100L, new ApprovalRequest()))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_SCREENING_STATE_TRANSITION");
    }

    @Test
    @DisplayName("FR-SCR-T1: a screening cannot be submitted twice — the second attempt is an illegal edge")
    void t1_submissionIsNotRepeatable() {
        positionAt(ProgramState.SUBMISSION, ScreeningState.CREATED);
        CurrentUserContext.set(bob);

        screeningService.submitScreening(100L);

        assertThatThrownBy(() -> screeningService.submitScreening(100L))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_SCREENING_STATE_TRANSITION");
    }

    @Test
    @DisplayName("FR-SCR-T3/T4: a rollback from APPROVED back to REVIEWED is not reachable by any function")
    void noFunctionRollsAScreeningBackwards() {
        positionAt(ProgramState.REVIEW, ScreeningState.APPROVED);
        CurrentUserContext.set(dave);

        assertThatThrownBy(() -> screeningService.reviewScreening(100L, review()))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_SCREENING_STATE_TRANSITION");

        assertThat(screening.getState()).isEqualTo(ScreeningState.APPROVED);
    }

    // ==================================================================
    // FR-SCR-T8 — SCHEDULED and REJECTED are terminal
    // ==================================================================

    @ParameterizedTest
    @EnumSource(value = ScreeningState.class, names = {"SCHEDULED", "REJECTED"})
    @DisplayName("FR-SCR-T8: no state-changing function has any effect on a SCHEDULED or REJECTED screening")
    void t8_terminalStatesHaveNoOutgoingTransition(ScreeningState terminal) {
        screening.setState(terminal);
        screening.setFinalSubmissionDate(null);

        program.setState(ProgramState.SUBMISSION);
        CurrentUserContext.set(bob);
        assertThatThrownBy(() -> screeningService.submitScreening(100L)).isInstanceOf(ConflictException.class);

        program.setState(ProgramState.REVIEW);
        CurrentUserContext.set(dave);
        assertThatThrownBy(() -> screeningService.reviewScreening(100L, review()))
                .isInstanceOf(ConflictException.class);

        program.setState(ProgramState.SCHEDULING);
        CurrentUserContext.set(alice);
        assertThatThrownBy(() -> screeningService.approveScreening(100L, new ApprovalRequest()))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> screeningService.rejectScreening(100L, rejection("again")))
                .isInstanceOf(ConflictException.class);

        program.setState(ProgramState.DECISION);
        assertThatThrownBy(() -> screeningService.acceptScreening(100L)).isInstanceOf(ConflictException.class);

        assertThat(screening.getState()).isEqualTo(terminal);
        verify(screeningRepository, never()).save(any(Screening.class));
    }

    @ParameterizedTest
    @EnumSource(value = ScreeningState.class, names = {"SCHEDULED", "REJECTED"})
    @DisplayName("FR-SCR-T8: a terminal screening can no longer be updated, withdrawn or re-assigned a handler either")
    void t8_terminalScreeningsAreClosedToEveryOtherFunctionToo(ScreeningState terminal) {
        screening.setState(terminal);

        program.setState(ProgramState.SUBMISSION);
        CurrentUserContext.set(bob);
        ScreeningUpdateRequest update = new ScreeningUpdateRequest();
        update.setFilmTitle("Renamed");
        assertThatThrownBy(() -> screeningService.updateScreening(100L, update))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_TERMINAL_STATE");
        assertThatThrownBy(() -> screeningService.withdrawScreening(100L))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_TERMINAL_STATE");

        program.setState(ProgramState.ASSIGNMENT);
        CurrentUserContext.set(alice);
        HandlerAssignRequest assign = new HandlerAssignRequest();
        assign.setStaffUsername("dave123");
        assertThatThrownBy(() -> screeningService.assignHandler(100L, assign))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_TERMINAL_STATE");

        program.setState(ProgramState.FINAL_SUBMISSION);
        CurrentUserContext.set(bob);
        assertThatThrownBy(() -> screeningService.finalSubmitScreening(100L, new ScreeningUpdateRequest()))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_TERMINAL_STATE");

        assertThat(screening.getState()).isEqualTo(terminal);
        assertThat(screening.getFilmTitle()).isEqualTo("Star Wars");
        verify(screeningRepository, never()).delete(any(Screening.class));
    }

    @Test
    @DisplayName("FR-SCR-T8: the refusal out of a terminal state says so explicitly and carries SCREENING_TERMINAL_STATE")
    void t8_theTerminalRefusalIsExplained() {
        positionAt(ProgramState.DECISION, ScreeningState.SCHEDULED);
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.acceptScreening(100L))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_TERMINAL_STATE")
                .hasMessageContaining("terminal");
    }

    @Test
    @DisplayName("FR-SCR-T8: a REJECTED screening keeps its recorded reason; a second rejection cannot overwrite it")
    void t8_aRejectedScreeningKeepsItsReason() {
        positionAt(ProgramState.SCHEDULING, ScreeningState.REVIEWED);
        CurrentUserContext.set(alice);
        screeningService.rejectScreening(100L, rejection("The first and only reason"));

        assertThatThrownBy(() -> screeningService.rejectScreening(100L, rejection("A second reason")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_TERMINAL_STATE");

        assertThat(screening.getRejectionReason()).isEqualTo("The first and only reason");
    }

    @Test
    @DisplayName("FR-SCR-T7/T8: a SCHEDULED screening cannot be accepted a second time")
    void t8_acceptanceIsNotRepeatable() {
        positionAt(ProgramState.DECISION, ScreeningState.APPROVED);
        screening.setFinalSubmissionDate(LocalDateTime.of(2026, 5, 20, 12, 0));
        CurrentUserContext.set(alice);

        screeningService.acceptScreening(100L);

        assertThatThrownBy(() -> screeningService.acceptScreening(100L))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_TERMINAL_STATE");
    }

    @Test
    @DisplayName("FR-SCR-T8: the state machine has exactly six states, and exactly two of them are terminal")
    void t8_theMachineHasTwoTerminalStatesAndNoMore() {
        assertThat(ScreeningState.values()).containsExactlyInAnyOrder(
                ScreeningState.CREATED, ScreeningState.SUBMITTED, ScreeningState.REVIEWED,
                ScreeningState.APPROVED, ScreeningState.SCHEDULED, ScreeningState.REJECTED);

        // Every non-terminal state still has at least one reachable next state,
        // proven by the legal-edge tests above; the two terminal ones have none.
        positionAt(ProgramState.SCHEDULING, ScreeningState.SCHEDULED);
        CurrentUserContext.set(alice);
        assertThatThrownBy(() -> screeningService.rejectScreening(100L, rejection("r")))
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_TERMINAL_STATE");

        screening.setState(ScreeningState.REJECTED);
        assertThatThrownBy(() -> screeningService.approveScreening(100L, new ApprovalRequest()))
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_TERMINAL_STATE");
    }
}
