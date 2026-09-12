package gr.aegean.cinema.screening;

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
import gr.aegean.cinema.service.impl.ProgramServiceImpl;
import gr.aegean.cinema.service.impl.ScreeningServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-SCR-28 and FR-SCR-T6 seen from the screening side: on entering DECISION the
 * system itself rejects every APPROVED screening that was never finally
 * submitted, recording a reason on each (FR-SCR-29).
 *
 * <p>The rule has exactly ONE implementation,
 * {@code ProgramServiceImpl.autoRejectUnfinishedApprovedScreenings}, written in
 * M3 as the side effect of the FINAL_SUBMISSION → DECISION step. This class
 * deliberately drives that same implementation rather than re-implementing the
 * sweep inside the screening service, and then proves the consequences the
 * screening module owns: the swept screenings really are terminal afterwards,
 * and each of them really does carry a reason.
 */
class ScreeningAutoRejectionTest {

    private ProgramRepository programRepository;
    private ScreeningRepository screeningRepository;
    private ProgramRoleRepository programRoleRepository;
    private ProgramServiceImpl programService;
    private ScreeningServiceImpl screeningService;

    private User alice;   // PROGRAMMER of the program
    private User bob;     // SUBMITTER of the screenings
    private Program program;

    @BeforeEach
    void setUp() {
        CurrentUserContext.clear();
        programRepository = mock(ProgramRepository.class);
        screeningRepository = mock(ScreeningRepository.class);
        programRoleRepository = mock(ProgramRoleRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        AuthorizationService authorizationService = new AuthorizationService(
                programRoleRepository, userRepository, mock(TokenService.class));
        RedactionService redactionService =
                new RedactionService(authorizationService, programRoleRepository, screeningRepository);
        programService = new ProgramServiceImpl(programRepository, programRoleRepository, screeningRepository,
                userRepository, authorizationService, redactionService);
        screeningService = new ScreeningServiceImpl(
                screeningRepository, programRepository, userRepository, authorizationService, redactionService);

        alice = user(1L, "alice01");
        bob = user(2L, "bob2024");

        program = Program.builder()
                .id(10L).name("Summer Fest").description("A summer season")
                .startDate(LocalDate.of(2026, 6, 1)).endDate(LocalDate.of(2026, 6, 30))
                .state(ProgramState.FINAL_SUBMISSION).creator(alice)
                .build();

        when(programRepository.findById(10L)).thenReturn(Optional.of(program));
        when(programRepository.save(any(Program.class))).thenAnswer(inv -> inv.getArgument(0));
        when(screeningRepository.save(any(Screening.class))).thenAnswer(inv -> inv.getArgument(0));
        when(programRoleRepository.findByProgramAndRole(any(), any())).thenReturn(List.of());
        when(programRoleRepository.existsByUserAndProgramAndRole(alice, program, ProgramRoleType.PROGRAMMER))
                .thenReturn(true);
        when(screeningRepository.findByProgramAndState(any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
    }

    private static User user(long id, String username) {
        return User.builder().id(id).username(username).password("H").fullName(username)
                .permanentRole(PermanentRole.USER).active(true).build();
    }

    private Screening approved(long id, boolean finallySubmitted) {
        return Screening.builder()
                .id(id).program(program).submitter(bob).state(ScreeningState.APPROVED)
                .filmTitle("Film " + id).filmCast("A cast").filmGenres("Drama")
                .filmDurationMinutes(90).auditoriumName("Hall A")
                .startTime(LocalDateTime.of(2026, 6, 10, 20, 0))
                .endTime(LocalDateTime.of(2026, 6, 10, 22, 0))
                .finalSubmissionDate(finallySubmitted ? LocalDateTime.of(2026, 5, 1, 12, 0) : null)
                .build();
    }

    private void enterDecision(List<Screening> approvedScreenings) {
        when(screeningRepository.findByProgramAndState(program, ScreeningState.APPROVED))
                .thenReturn(approvedScreenings);
        CurrentUserContext.set(alice);
        programService.startDecision(10L);
    }

    // ==================================================================
    // FR-SCR-28 / FR-SCR-T6 — the automatic sweep
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-28/FR-SCR-T6: entering DECISION rejects an APPROVED screening that was never finally submitted")
    void fr28_theSweepRejectsAnApprovedScreeningWithNoFinalSubmission() {
        Screening unfinished = approved(100L, false);

        enterDecision(List.of(unfinished));

        assertThat(unfinished.getState()).isEqualTo(ScreeningState.REJECTED);
        verify(screeningRepository).save(unfinished);
    }

    @Test
    @DisplayName("FR-SCR-28/FR-SCR-29: every automatically rejected screening carries a recorded rejection reason")
    void fr29_theAutomaticRejectionAlsoRecordsAReason() {
        Screening first = approved(100L, false);
        Screening second = approved(101L, false);

        enterDecision(List.of(first, second));

        assertThat(first.getRejectionReason()).isNotBlank();
        assertThat(second.getRejectionReason()).isNotBlank();
        assertThat(first.getRejectionReason()).isEqualTo(second.getRejectionReason());
        assertThat(first.getRejectionReason()).contains("DECISION");
    }

    @Test
    @DisplayName("FR-SCR-28: an APPROVED screening that WAS finally submitted survives the sweep untouched")
    void fr28_aFinallySubmittedScreeningSurvivesTheSweep() {
        Screening finished = approved(100L, true);
        Screening unfinished = approved(101L, false);

        enterDecision(List.of(finished, unfinished));

        assertThat(finished.getState()).isEqualTo(ScreeningState.APPROVED);
        assertThat(finished.getRejectionReason()).isNull();
        assertThat(unfinished.getState()).isEqualTo(ScreeningState.REJECTED);
        verify(screeningRepository, never()).save(finished);
    }

    @Test
    @DisplayName("FR-SCR-T6: the sweep looks at APPROVED screenings only — nothing in any other state is touched")
    void fr28_onlyApprovedScreeningsAreSwept() {
        enterDecision(new ArrayList<>());

        verify(screeningRepository).findByProgramAndState(program, ScreeningState.APPROVED);
        verify(screeningRepository, never()).save(any(Screening.class));
    }

    @ParameterizedTest
    @EnumSource(value = ProgramState.class,
            names = {"CREATED", "SUBMISSION", "ASSIGNMENT", "REVIEW", "SCHEDULING"})
    @DisplayName("FR-SCR-T6: no transition other than the one into DECISION triggers the automatic rejection")
    void fr28_theSweepRunsOnNoOtherTransition(ProgramState from) {
        program.setState(from);
        CurrentUserContext.set(alice);

        switch (from) {
            case CREATED -> programService.startSubmission(10L);
            case SUBMISSION -> programService.startAssignment(10L);
            case ASSIGNMENT -> programService.startReview(10L);
            case REVIEW -> programService.startScheduling(10L);
            default -> programService.startFinalSubmission(10L);
        }

        verify(screeningRepository, never()).findByProgramAndState(any(), any());
        verify(screeningRepository, never()).save(any(Screening.class));
    }

    // ==================================================================
    // The consequences the screening module owns (FR-SCR-T8, FR-SCR-36)
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-T8: a screening the system rejected automatically is terminal — it can no longer be accepted")
    void fr28_anAutoRejectedScreeningIsTerminal() {
        Screening unfinished = approved(100L, false);
        enterDecision(List.of(unfinished));
        when(screeningRepository.findById(100L)).thenReturn(Optional.of(unfinished));

        CurrentUserContext.set(alice);
        assertThatThrownBy(() -> screeningService.acceptScreening(100L))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_TERMINAL_STATE");

        assertThat(unfinished.getState()).isEqualTo(ScreeningState.REJECTED);
    }

    @Test
    @DisplayName("FR-SCR-T8/FR-SCR-29: an auto-rejected screening keeps the system's reason; a manual rejection cannot overwrite it")
    void fr29_theSystemsReasonIsNotOverwrittenAfterwards() {
        Screening unfinished = approved(100L, false);
        enterDecision(List.of(unfinished));
        String systemReason = unfinished.getRejectionReason();
        when(screeningRepository.findById(100L)).thenReturn(Optional.of(unfinished));

        CurrentUserContext.set(alice);
        gr.aegean.cinema.dto.screening.RejectionRequest request =
                new gr.aegean.cinema.dto.screening.RejectionRequest();
        request.setReason("A later, manual reason");

        assertThatThrownBy(() -> screeningService.rejectScreening(100L, request))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_TERMINAL_STATE");

        assertThat(unfinished.getRejectionReason()).isEqualTo(systemReason);
    }

    @Test
    @DisplayName("FR-SCR-36: after the sweep, only the finally submitted screening is still acceptable")
    void fr36_onlyTheSurvivingScreeningCanStillBeAccepted() {
        Screening finished = approved(100L, true);
        Screening unfinished = approved(101L, false);
        enterDecision(List.of(finished, unfinished));
        when(screeningRepository.findById(100L)).thenReturn(Optional.of(finished));

        CurrentUserContext.set(alice);
        assertThat(screeningService.acceptScreening(100L).getState()).isEqualTo(ScreeningState.SCHEDULED);
    }
}
