package gr.aegean.cinema.screening;

import gr.aegean.cinema.dto.screening.ApprovalRequest;
import gr.aegean.cinema.dto.screening.HandlerAssignRequest;
import gr.aegean.cinema.dto.screening.RejectionRequest;
import gr.aegean.cinema.dto.screening.ReviewRequest;
import gr.aegean.cinema.dto.screening.ScreeningCreateRequest;
import gr.aegean.cinema.dto.screening.ScreeningResponse;
import gr.aegean.cinema.dto.screening.ScreeningUpdateRequest;
import gr.aegean.cinema.exception.AuthenticationException;
import gr.aegean.cinema.exception.BadRequestException;
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
import gr.aegean.cinema.service.impl.ScreeningServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service-level coverage of the whole screening module: FR-SCR-01 … FR-SCR-36
 * plus ROLE-05, ROLE-08, ROLE-09, ROLE-10, ROLE-12 and ROLE-19.
 *
 * <p>The REAL {@link AuthorizationService} is used, with only its repositories
 * doubled, exactly as the M3 program tests do. Mocking the authorization service
 * away — as the inherited M0 test class did — would let the ADMIN-rejection and
 * inactive-account gates (ROLE-14/15/16) regress silently, since every service
 * method's first statement is {@code requireCinemaActor()}.
 *
 * <p>Three refusals that look alike are deliberately kept apart here:
 * a WRONG ACTOR is 403, a right actor whose request CONFLICTS WITH THE CURRENT
 * STATE is 409 (ROLE-19 included, which DOMAIN_RULES.md pins to 409), and
 * genuinely MALFORMED INPUT — FR-SCR-10 — is 400.
 */
class ScreeningLifecycleServiceTest {

    private ScreeningRepository screeningRepository;
    private ProgramRepository programRepository;
    private ProgramRoleRepository programRoleRepository;
    private UserRepository userRepository;
    private ScreeningServiceImpl screeningService;

    private User alice;   // PROGRAMMER (and creator) of the program under test
    private User bob;     // SUBMITTER of the screening under test
    private User dave;    // STAFF of the program, the assigned handler
    private User erin;    // another STAFF member of the program
    private User carol;   // an outsider with no role anywhere
    private User admin;

    private Program program;
    private Screening screening;

    @BeforeEach
    void setUp() {
        CurrentUserContext.clear();
        screeningRepository = mock(ScreeningRepository.class);
        programRepository = mock(ProgramRepository.class);
        programRoleRepository = mock(ProgramRoleRepository.class);
        userRepository = mock(UserRepository.class);
        AuthorizationService authorizationService = new AuthorizationService(
                programRoleRepository, userRepository, mock(TokenService.class));
        screeningService = new ScreeningServiceImpl(
                screeningRepository, programRepository, userRepository, authorizationService,
                new RedactionService(authorizationService, programRoleRepository, screeningRepository));

        alice = user(1L, "alice01");
        bob = user(2L, "bob2024");
        carol = user(3L, "carol_x");
        dave = user(4L, "dave123");
        erin = user(5L, "erin567");
        admin = User.builder().id(99L).username("admin1").password("H").fullName("admin1")
                .permanentRole(PermanentRole.ADMIN).active(true).build();

        program = Program.builder()
                .id(10L).name("Summer Fest").description("A summer season")
                .startDate(LocalDate.of(2026, 6, 1)).endDate(LocalDate.of(2026, 6, 30))
                .state(ProgramState.SUBMISSION).creator(alice)
                .build();

        screening = completeScreening(100L, ScreeningState.CREATED);

        when(programRepository.findById(10L)).thenReturn(Optional.of(program));
        when(screeningRepository.findById(100L)).thenReturn(Optional.of(screening));
        when(screeningRepository.save(any(Screening.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findByUsername("dave123")).thenReturn(Optional.of(dave));
        when(userRepository.findByUsername("erin567")).thenReturn(Optional.of(erin));
        when(userRepository.findByUsername("carol_x")).thenReturn(Optional.of(carol));
        when(userRepository.findByUsername("bob2024")).thenReturn(Optional.of(bob));

        when(programRoleRepository.existsByUserAndProgramAndRole(alice, program, ProgramRoleType.PROGRAMMER))
                .thenReturn(true);
        when(programRoleRepository.existsByUserAndProgramAndRole(dave, program, ProgramRoleType.STAFF))
                .thenReturn(true);
        when(programRoleRepository.existsByUserAndProgramAndRole(erin, program, ProgramRoleType.STAFF))
                .thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static User user(long id, String username) {
        return User.builder().id(id).username(username).password("H").fullName(username)
                .permanentRole(PermanentRole.USER).active(true).build();
    }

    /** A screening carrying every detail FR-SCR-13 requires, submitted by Bob. */
    private Screening completeScreening(long id, ScreeningState state) {
        return Screening.builder()
                .id(id).program(program).submitter(bob).state(state)
                .filmTitle("Star Wars").filmCast("Mark Hamill, Carrie Fisher").filmGenres("SciFi,Adventure")
                .filmDurationMinutes(120).auditoriumName("Hall A")
                .startTime(LocalDateTime.of(2026, 6, 10, 20, 0))
                .endTime(LocalDateTime.of(2026, 6, 10, 22, 30))
                .build();
    }

    private ScreeningCreateRequest completeCreateRequest() {
        ScreeningCreateRequest request = new ScreeningCreateRequest();
        request.setFilmTitle("Star Wars");
        request.setFilmCast("Mark Hamill, Carrie Fisher");
        request.setFilmGenres("SciFi,Adventure");
        request.setFilmDurationMinutes(120);
        request.setAuditoriumName("Hall A");
        request.setStartTime(LocalDateTime.of(2026, 6, 10, 20, 0));
        request.setEndTime(LocalDateTime.of(2026, 6, 10, 22, 30));
        return request;
    }

    private RejectionRequest rejection(String reason) {
        RejectionRequest request = new RejectionRequest();
        request.setReason(reason);
        return request;
    }

    private ReviewRequest review(int score, String comments) {
        ReviewRequest request = new ReviewRequest();
        request.setScore(score);
        request.setComments(comments);
        return request;
    }

    private HandlerAssignRequest handler(String username) {
        HandlerAssignRequest request = new HandlerAssignRequest();
        request.setStaffUsername(username);
        return request;
    }

    // ==================================================================
    // Creation — FR-SCR-01 … FR-SCR-06, FR-SCR-19, ROLE-05, ROLE-19
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-01/FR-SCR-02/FR-SCR-06/ROLE-05: a plain USER creates a screening that starts in CREATED with themselves as SUBMITTER")
    void role05_aPlainUserMayCreateAScreening() {
        CurrentUserContext.set(bob);

        ScreeningResponse response = screeningService.createScreening(10L, completeCreateRequest());

        assertThat(response.getState()).isEqualTo(ScreeningState.CREATED);
        assertThat(response.getSubmitterUsername()).isEqualTo("bob2024");
        assertThat(response.getProgramId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("FR-SCR-03/FR-SCR-04: creation leaves the id and the creation date to the persistence layer")
    void creationLeavesIdAndCreationDateToThePersistenceLayer() {
        CurrentUserContext.set(bob);

        screeningService.createScreening(10L, completeCreateRequest());

        verify(screeningRepository).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getId() == null && saved.getCreatedAt() == null));
    }

    @Test
    @DisplayName("FR-SCR-05: the program link is mandatory at creation and comes from the program the screening is created in")
    void fr05_theProgramLinkIsMandatoryAtCreation() {
        CurrentUserContext.set(bob);

        screeningService.createScreening(10L, completeCreateRequest());

        verify(screeningRepository).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getProgram() == program));
    }

    @Test
    @DisplayName("FR-SCR-05: the program link is immutable — no update payload can even name a program")
    void fr05_theProgramLinkIsImmutableAfterCreation() {
        boolean anyProgramField = Arrays.stream(ScreeningUpdateRequest.class.getDeclaredFields())
                .map(Field::getName)
                .anyMatch(name -> name.toLowerCase().contains("program"));
        assertThat(anyProgramField)
                .as("ScreeningUpdateRequest must expose no program field at all")
                .isFalse();

        CurrentUserContext.set(bob);
        ScreeningUpdateRequest request = new ScreeningUpdateRequest();
        request.setFilmTitle("Renamed");

        screeningService.updateScreening(100L, request);

        assertThat(screening.getProgram()).isSameAs(program);
    }

    @Test
    @DisplayName("FR-SCR-19: a newly created screening has no handler until one is assigned")
    void fr19_theHandlerIsInitiallyEmpty() {
        CurrentUserContext.set(bob);

        ScreeningResponse response = screeningService.createScreening(10L, completeCreateRequest());

        assertThat(response.getHandlerUsername()).isNull();
    }

    @Test
    @DisplayName("FR-SCR-01: an ADMIN is rejected from screening creation with 403 ADMIN_NOT_ALLOWED")
    void fr01_anAdminCannotCreateAScreening() {
        CurrentUserContext.set(admin);

        assertThatThrownBy(() -> screeningService.createScreening(10L, completeCreateRequest()))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_NOT_ALLOWED");

        verify(screeningRepository, never()).save(any(Screening.class));
    }

    @Test
    @DisplayName("FR-SCR-01: an inactive account is rejected from screening creation with 403 ACCOUNT_INACTIVE")
    void fr01_anInactiveAccountCannotCreateAScreening() {
        bob.setActive(false);
        CurrentUserContext.set(bob);

        assertThatThrownBy(() -> screeningService.createScreening(10L, completeCreateRequest()))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ACCOUNT_INACTIVE");
    }

    @Test
    @DisplayName("FR-SCR-01: an unauthenticated caller cannot create a screening")
    void fr01_anAnonymousCallerCannotCreateAScreening() {
        assertThatThrownBy(() -> screeningService.createScreening(10L, completeCreateRequest()))
                .isInstanceOf(AuthenticationException.class);

        verify(screeningRepository, never()).save(any(Screening.class));
    }

    @Test
    @DisplayName("ROLE-19: a PROGRAMMER of the program cannot submit a screening into it — 409, not 403")
    void role19_aProgrammerCannotSubmitIntoTheirOwnProgram() {
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.createScreening(10L, completeCreateRequest()))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PROGRAMMER_CANNOT_SUBMIT_IN_OWN_PROGRAM");

        verify(screeningRepository, never()).save(any(Screening.class));
    }

    @Test
    @DisplayName("ROLE-19: the refusal is a ConflictException and never a ForbiddenException")
    void role19_theRefusalIsNotAForbiddenException() {
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.createScreening(10L, completeCreateRequest()))
                .isNotInstanceOf(ForbiddenException.class)
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("ROLE-19: a PROGRAMMER of a DIFFERENT program may still submit screenings here")
    void role19_aProgrammerOfAnotherProgramIsUnaffected() {
        Program other = Program.builder().id(11L).name("Autumn Fest").description("d")
                .startDate(LocalDate.of(2026, 9, 1)).endDate(LocalDate.of(2026, 9, 30))
                .state(ProgramState.SUBMISSION).creator(carol).build();
        when(programRoleRepository.existsByUserAndProgramAndRole(carol, other, ProgramRoleType.PROGRAMMER))
                .thenReturn(true);
        CurrentUserContext.set(carol);

        assertThat(screeningService.createScreening(10L, completeCreateRequest()).getState())
                .isEqualTo(ScreeningState.CREATED);
    }

    @Test
    @DisplayName("ROLE-19: a SUBMITTER who has since become a PROGRAMMER of the program cannot submit the screening either")
    void role19_theRuleIsRecheckedAtSubmissionTime() {
        when(programRoleRepository.existsByUserAndProgramAndRole(bob, program, ProgramRoleType.PROGRAMMER))
                .thenReturn(true);
        CurrentUserContext.set(bob);

        assertThatThrownBy(() -> screeningService.submitScreening(100L))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PROGRAMMER_CANNOT_SUBMIT_IN_OWN_PROGRAM");

        assertThat(screening.getState()).isEqualTo(ScreeningState.CREATED);
    }

    @Test
    @DisplayName("FR-SCR-10: creating a screening whose slot is shorter than the film duration is a 400, and nothing is persisted")
    void fr10_aTooShortSlotIsRejectedWith400AtCreation() {
        CurrentUserContext.set(bob);
        ScreeningCreateRequest request = completeCreateRequest();
        request.setFilmDurationMinutes(200);   // 200 min film in a 150 min slot

        assertThatThrownBy(() -> screeningService.createScreening(10L, request))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_DURATION_TOO_SHORT");

        verify(screeningRepository, never()).save(any(Screening.class));
    }

    @Test
    @DisplayName("FR-SCR-10: an end time that does not follow the start time is a 400 INVALID_SCREENING_TIMES")
    void fr10_anEndTimeBeforeTheStartTimeIsRejected() {
        CurrentUserContext.set(bob);
        ScreeningCreateRequest request = completeCreateRequest();
        request.setEndTime(request.getStartTime().minusMinutes(30));

        assertThatThrownBy(() -> screeningService.createScreening(10L, request))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_SCREENING_TIMES");
    }

    @Test
    @DisplayName("FR-SCR-10: a slot exactly as long as the film is accepted (the rule is >=, not >)")
    void fr10_aSlotExactlyAsLongAsTheFilmIsAccepted() {
        CurrentUserContext.set(bob);
        ScreeningCreateRequest request = completeCreateRequest();
        request.setFilmDurationMinutes(150);   // exactly the 20:00 -> 22:30 slot

        assertThat(screeningService.createScreening(10L, request)).isNotNull();
    }

    @Test
    @DisplayName("FR-PRG-T9: no screening can be created inside an ANNOUNCED program")
    void creationIsFrozenInAnAnnouncedProgram() {
        program.setState(ProgramState.ANNOUNCED);
        CurrentUserContext.set(bob);

        assertThatThrownBy(() -> screeningService.createScreening(10L, completeCreateRequest()))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PROGRAM_ANNOUNCED_FROZEN");
    }

    @ParameterizedTest
    @EnumSource(value = ProgramState.class,
            names = {"ASSIGNMENT", "REVIEW", "SCHEDULING", "FINAL_SUBMISSION", "DECISION"})
    @DisplayName("FR-SCR-01: screenings are accepted only while the program is in CREATED or SUBMISSION (ASSUMPTIONS.md #21)")
    void creationIsRefusedOutsideTheCreationWindow(ProgramState state) {
        program.setState(state);
        CurrentUserContext.set(bob);

        assertThatThrownBy(() -> screeningService.createScreening(10L, completeCreateRequest()))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PROGRAM_NOT_ACCEPTING_SCREENINGS");
    }

    @Test
    @DisplayName("FR-SCR-05: creating a screening inside a program that does not exist is a 404")
    void creationInAnUnknownProgramIs404() {
        CurrentUserContext.set(bob);
        when(programRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> screeningService.createScreening(404L, completeCreateRequest()))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PROGRAM_NOT_FOUND");
    }

    // ==================================================================
    // Update — FR-SCR-07 … FR-SCR-10, FR-SCR-32, FR-SCR-33, ROLE-12
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-07/FR-SCR-09/ROLE-12: the SUBMITTER may change auditorium, film info and timing while in CREATED")
    void fr09_theSubmitterMayUpdateEveryMutableField() {
        CurrentUserContext.set(bob);
        ScreeningUpdateRequest request = new ScreeningUpdateRequest();
        request.setFilmTitle("The Matrix");
        request.setFilmCast("Keanu Reeves");
        request.setFilmGenres("SciFi");
        request.setFilmDurationMinutes(136);
        request.setAuditoriumName("Hall B");
        request.setStartTime(LocalDateTime.of(2026, 6, 12, 18, 0));
        request.setEndTime(LocalDateTime.of(2026, 6, 12, 21, 0));

        ScreeningResponse response = screeningService.updateScreening(100L, request);

        assertThat(response.getFilmTitle()).isEqualTo("The Matrix");
        assertThat(response.getAuditoriumName()).isEqualTo("Hall B");
        assertThat(response.getFilmDurationMinutes()).isEqualTo(136);
        assertThat(response.getStartTime()).isEqualTo(LocalDateTime.of(2026, 6, 12, 18, 0));
        assertThat(response.getEndTime()).isEqualTo(LocalDateTime.of(2026, 6, 12, 21, 0));
    }

    @Test
    @DisplayName("FR-SCR-07/ROLE-12: a user who is not the SUBMITTER gets 403 NOT_SUBMITTER on update")
    void fr07_onlyTheSubmitterMayUpdate() {
        CurrentUserContext.set(carol);
        ScreeningUpdateRequest request = new ScreeningUpdateRequest();
        request.setFilmTitle("hijacked");

        assertThatThrownBy(() -> screeningService.updateScreening(100L, request))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_SUBMITTER");

        assertThat(screening.getFilmTitle()).isEqualTo("Star Wars");
    }

    @Test
    @DisplayName("FR-SCR-07: not even a PROGRAMMER of the program may update somebody else's screening")
    void fr07_notEvenAProgrammerMayUpdateSomebodyElsesScreening() {
        CurrentUserContext.set(alice);
        ScreeningUpdateRequest request = new ScreeningUpdateRequest();
        request.setFilmTitle("hijacked");

        assertThatThrownBy(() -> screeningService.updateScreening(100L, request))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_SUBMITTER");
    }

    @ParameterizedTest
    @EnumSource(value = ScreeningState.class, names = "CREATED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("FR-SCR-08/FR-SCR-33: a regular update outside the CREATED state is refused with 409")
    void fr33_regularUpdatesAreAllowedOnlyInCreated(ScreeningState state) {
        screening.setState(state);
        CurrentUserContext.set(bob);
        ScreeningUpdateRequest request = new ScreeningUpdateRequest();
        request.setFilmTitle("too late");

        assertThatThrownBy(() -> screeningService.updateScreening(100L, request))
                .isInstanceOf(ConflictException.class);

        assertThat(screening.getFilmTitle()).isEqualTo("Star Wars");
        verify(screeningRepository, never()).save(any(Screening.class));
    }

    @Test
    @DisplayName("FR-SCR-10: an update that shortens the slot below the film duration is a 400, and persists nothing")
    void fr10_anUpdateCannotShortenTheSlotBelowTheFilmDuration() {
        CurrentUserContext.set(bob);
        ScreeningUpdateRequest request = new ScreeningUpdateRequest();
        request.setEndTime(LocalDateTime.of(2026, 6, 10, 21, 0));   // 60 min for a 120 min film

        assertThatThrownBy(() -> screeningService.updateScreening(100L, request))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_DURATION_TOO_SHORT");

        verify(screeningRepository, never()).save(any(Screening.class));
    }

    @Test
    @DisplayName("FR-SCR-32: after a successful final submission the details are frozen against any further update")
    void fr32_detailsAreFrozenAfterTheFinalSubmission() {
        screening.setState(ScreeningState.APPROVED);
        screening.setFinalSubmissionDate(LocalDateTime.of(2026, 5, 20, 12, 0));
        CurrentUserContext.set(bob);
        ScreeningUpdateRequest request = new ScreeningUpdateRequest();
        request.setFilmTitle("too late");

        assertThatThrownBy(() -> screeningService.updateScreening(100L, request))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_DETAILS_FROZEN");

        assertThat(screening.getFilmTitle()).isEqualTo("Star Wars");
    }

    @Test
    @DisplayName("FR-SCR-07: updating a screening that does not exist is a 404 SCREENING_NOT_FOUND")
    void updatingAnUnknownScreeningIs404() {
        CurrentUserContext.set(bob);
        when(screeningRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> screeningService.updateScreening(404L, new ScreeningUpdateRequest()))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_NOT_FOUND");
    }

    // ==================================================================
    // Submission — FR-SCR-11, FR-SCR-12, FR-SCR-13
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-11/FR-SCR-12/FR-SCR-13/ROLE-12: the SUBMITTER submits a complete screening while the program is in SUBMISSION")
    void fr11_theSubmitterSubmitsACompleteScreening() {
        CurrentUserContext.set(bob);

        assertThat(screeningService.submitScreening(100L).getState()).isEqualTo(ScreeningState.SUBMITTED);
        assertThat(screening.getState()).isEqualTo(ScreeningState.SUBMITTED);
    }

    @Test
    @DisplayName("FR-SCR-11/ROLE-12: a user who is not the SUBMITTER gets 403 NOT_SUBMITTER on submission")
    void fr11_onlyTheSubmitterMaySubmit() {
        CurrentUserContext.set(carol);

        assertThatThrownBy(() -> screeningService.submitScreening(100L))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_SUBMITTER");

        assertThat(screening.getState()).isEqualTo(ScreeningState.CREATED);
    }

    @ParameterizedTest
    @EnumSource(value = ProgramState.class, names = "SUBMISSION", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("FR-SCR-12: submission outside the program's SUBMISSION state is refused with 409")
    void fr12_submissionRequiresTheProgramInSubmission(ProgramState state) {
        program.setState(state);
        CurrentUserContext.set(bob);

        assertThatThrownBy(() -> screeningService.submitScreening(100L))
                .isInstanceOf(ConflictException.class);

        assertThat(screening.getState()).isEqualTo(ScreeningState.CREATED);
    }

    @Test
    @DisplayName("FR-SCR-12: the refusal names PROGRAM_NOT_IN_SUBMISSION when the program has simply not opened yet")
    void fr12_theRefusalCarriesItsOwnErrorCode() {
        program.setState(ProgramState.CREATED);
        CurrentUserContext.set(bob);

        assertThatThrownBy(() -> screeningService.submitScreening(100L))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PROGRAM_NOT_IN_SUBMISSION");
    }

    @ParameterizedTest
    @ValueSource(strings = {"filmTitle", "filmCast", "filmGenres", "filmDurationMinutes",
            "auditoriumName", "startTime", "endTime"})
    @DisplayName("FR-SCR-13: a screening missing any one required detail cannot be submitted")
    void fr13_everyRequiredDetailIsCheckedBeforeSubmission(String missingField) {
        switch (missingField) {
            case "filmTitle" -> screening.setFilmTitle(null);
            case "filmCast" -> screening.setFilmCast(null);
            case "filmGenres" -> screening.setFilmGenres(null);
            case "filmDurationMinutes" -> screening.setFilmDurationMinutes(null);
            case "auditoriumName" -> screening.setAuditoriumName(null);
            case "startTime" -> screening.setStartTime(null);
            default -> screening.setEndTime(null);
        }
        CurrentUserContext.set(bob);

        assertThatThrownBy(() -> screeningService.submitScreening(100L))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_NOT_COMPLETE");

        assertThat(screening.getState()).isEqualTo(ScreeningState.CREATED);
        verify(screeningRepository, never()).save(any(Screening.class));
    }

    @Test
    @DisplayName("FR-SCR-13: a blank (not merely null) required detail is incomplete too")
    void fr13_blankDetailsCountAsMissing() {
        screening.setFilmCast("   ");
        CurrentUserContext.set(bob);

        assertThatThrownBy(() -> screeningService.submitScreening(100L))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_NOT_COMPLETE");
    }

    // ==================================================================
    // Withdrawal — FR-SCR-14, FR-SCR-15
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-14/FR-SCR-15/ROLE-12: the SUBMITTER withdraws a CREATED screening and it is deleted")
    void fr14_withdrawalDeletesTheScreening() {
        CurrentUserContext.set(bob);

        screeningService.withdrawScreening(100L);

        verify(screeningRepository).delete(screening);
    }

    @Test
    @DisplayName("FR-SCR-14: a user who is not the SUBMITTER cannot withdraw the screening")
    void fr14_onlyTheSubmitterMayWithdraw() {
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.withdrawScreening(100L))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_SUBMITTER");

        verify(screeningRepository, never()).delete(any(Screening.class));
    }

    @ParameterizedTest
    @EnumSource(value = ScreeningState.class, names = "CREATED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("FR-SCR-15: withdrawal outside the CREATED state is refused with 409 and deletes nothing")
    void fr15_withdrawalIsAllowedOnlyInCreated(ScreeningState state) {
        screening.setState(state);
        CurrentUserContext.set(bob);

        assertThatThrownBy(() -> screeningService.withdrawScreening(100L))
                .isInstanceOf(ConflictException.class);

        verify(screeningRepository, never()).delete(any(Screening.class));
    }

    // ==================================================================
    // Handler assignment — FR-SCR-16 … FR-SCR-19, ROLE-09
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-16/FR-SCR-17/FR-SCR-18/ROLE-09: a PROGRAMMER assigns one STAFF handler during ASSIGNMENT")
    void fr16_aProgrammerAssignsAStaffHandler() {
        program.setState(ProgramState.ASSIGNMENT);
        screening.setState(ScreeningState.SUBMITTED);
        CurrentUserContext.set(alice);

        ScreeningResponse response = screeningService.assignHandler(100L, handler("dave123"));

        assertThat(response.getHandlerUsername()).isEqualTo("dave123");
        assertThat(screening.getHandler()).isSameAs(dave);
    }

    @Test
    @DisplayName("FR-SCR-16/ROLE-09: a user who is not a PROGRAMMER of the program gets 403 NOT_PROGRAMMER")
    void fr16_onlyAProgrammerMayAssignAHandler() {
        program.setState(ProgramState.ASSIGNMENT);
        CurrentUserContext.set(carol);

        assertThatThrownBy(() -> screeningService.assignHandler(100L, handler("dave123")))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_PROGRAMMER");

        assertThat(screening.getHandler()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = ProgramState.class, names = {"ASSIGNMENT", "ANNOUNCED"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("FR-SCR-17: handler assignment outside the program's ASSIGNMENT state is 409 PROGRAM_NOT_IN_ASSIGNMENT")
    void fr17_assignmentRequiresTheProgramInAssignment(ProgramState state) {
        program.setState(state);
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.assignHandler(100L, handler("dave123")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PROGRAM_NOT_IN_ASSIGNMENT");

        assertThat(screening.getHandler()).isNull();
    }

    @Test
    @DisplayName("FR-SCR-18: the named user must actually hold the STAFF role in this program")
    void fr18_theHandlerMustBeStaffOfThisProgram() {
        program.setState(ProgramState.ASSIGNMENT);
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.assignHandler(100L, handler("carol_x")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_PROGRAM_STAFF");

        assertThat(screening.getHandler()).isNull();
    }

    @Test
    @DisplayName("FR-SCR-18: exactly ONE handler is held at a time — a re-assignment replaces the previous one")
    void fr18_exactlyOneHandlerIsHeldAtATime() {
        program.setState(ProgramState.ASSIGNMENT);
        CurrentUserContext.set(alice);

        screeningService.assignHandler(100L, handler("dave123"));
        ScreeningResponse response = screeningService.assignHandler(100L, handler("erin567"));

        assertThat(response.getHandlerUsername()).isEqualTo("erin567");
        assertThat(screening.getHandler()).isSameAs(erin);
    }

    @Test
    @DisplayName("FR-SCR-18: the SUBMITTER of a screening can never be assigned as its own handler")
    void fr18_theSubmitterCannotHandleTheirOwnScreening() {
        program.setState(ProgramState.ASSIGNMENT);
        when(programRoleRepository.existsByUserAndProgramAndRole(bob, program, ProgramRoleType.STAFF))
                .thenReturn(true);
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.assignHandler(100L, handler("bob2024")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "HANDLER_IS_SUBMITTER");
    }

    @Test
    @DisplayName("FR-SCR-18: naming a user that does not exist is a 404 USER_NOT_FOUND")
    void fr18_anUnknownHandlerUsernameIs404() {
        program.setState(ProgramState.ASSIGNMENT);
        when(userRepository.findByUsername("ghost99")).thenReturn(Optional.empty());
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.assignHandler(100L, handler("ghost99")))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "USER_NOT_FOUND");
    }

    // ==================================================================
    // Review — FR-SCR-20, FR-SCR-21, FR-SCR-22, ROLE-10
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-20/FR-SCR-21/FR-SCR-22/ROLE-10: the assigned STAFF handler reviews the screening during REVIEW")
    void fr20_theAssignedHandlerReviewsTheScreening() {
        program.setState(ProgramState.REVIEW);
        screening.setState(ScreeningState.SUBMITTED);
        screening.setHandler(dave);
        CurrentUserContext.set(dave);

        ScreeningResponse response = screeningService.reviewScreening(100L, review(8, "Strong programme fit"));

        assertThat(response.getState()).isEqualTo(ScreeningState.REVIEWED);
        assertThat(response.getReviewScore()).isEqualTo(8);
        assertThat(response.getReviewComments()).isEqualTo("Strong programme fit");
    }

    @Test
    @DisplayName("ROLE-10: a STAFF member who is not the assigned handler gets 403 NOT_HANDLER")
    void role10_staffMayReviewOnlyWhatTheyWereAssigned() {
        program.setState(ProgramState.REVIEW);
        screening.setState(ScreeningState.SUBMITTED);
        screening.setHandler(dave);
        CurrentUserContext.set(erin);

        assertThatThrownBy(() -> screeningService.reviewScreening(100L, review(8, "not mine")))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_HANDLER");

        assertThat(screening.getState()).isEqualTo(ScreeningState.SUBMITTED);
    }

    @Test
    @DisplayName("FR-SCR-20: neither the PROGRAMMER nor the SUBMITTER may write the review")
    void fr20_onlyTheHandlerMayReview() {
        program.setState(ProgramState.REVIEW);
        screening.setState(ScreeningState.SUBMITTED);
        screening.setHandler(dave);

        CurrentUserContext.set(alice);
        assertThatThrownBy(() -> screeningService.reviewScreening(100L, review(8, "c")))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_HANDLER");

        CurrentUserContext.set(bob);
        assertThatThrownBy(() -> screeningService.reviewScreening(100L, review(8, "c")))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_HANDLER");
    }

    @Test
    @DisplayName("FR-SCR-20: an unassigned screening has no handler, so nobody at all can review it")
    void fr20_anUnassignedScreeningCannotBeReviewed() {
        program.setState(ProgramState.REVIEW);
        screening.setState(ScreeningState.SUBMITTED);
        CurrentUserContext.set(dave);

        assertThatThrownBy(() -> screeningService.reviewScreening(100L, review(8, "c")))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_HANDLER");
    }

    @ParameterizedTest
    @EnumSource(value = ProgramState.class, names = {"REVIEW", "ANNOUNCED"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("FR-SCR-21: a review outside the program's REVIEW state is 409 PROGRAM_NOT_IN_REVIEW")
    void fr21_reviewRequiresTheProgramInReview(ProgramState state) {
        program.setState(state);
        screening.setState(ScreeningState.SUBMITTED);
        screening.setHandler(dave);
        CurrentUserContext.set(dave);

        assertThatThrownBy(() -> screeningService.reviewScreening(100L, review(8, "c")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PROGRAM_NOT_IN_REVIEW");

        assertThat(screening.getState()).isEqualTo(ScreeningState.SUBMITTED);
    }

    @Test
    @DisplayName("FR-SCR-22: the review payload requires both a numeric score and comments (ASSUMPTIONS.md #5: 1-10)")
    void fr22_theReviewPayloadRequiresAScoreAndComments() throws Exception {
        assertThat(ReviewRequest.class.getDeclaredField("score")
                .isAnnotationPresent(jakarta.validation.constraints.NotNull.class)).isTrue();
        assertThat(ReviewRequest.class.getDeclaredField("score")
                .isAnnotationPresent(jakarta.validation.constraints.Min.class)).isTrue();
        assertThat(ReviewRequest.class.getDeclaredField("score")
                .isAnnotationPresent(jakarta.validation.constraints.Max.class)).isTrue();
        assertThat(ReviewRequest.class.getDeclaredField("comments")
                .isAnnotationPresent(jakarta.validation.constraints.NotBlank.class)).isTrue();
    }

    // ==================================================================
    // Approval — FR-SCR-23, FR-SCR-24, FR-SCR-25, ROLE-08
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-23/FR-SCR-24/ROLE-08: a PROGRAMMER of the program approves a REVIEWED screening during SCHEDULING")
    void fr24_aProgrammerApprovesTheScreening() {
        program.setState(ProgramState.SCHEDULING);
        screening.setState(ScreeningState.REVIEWED);
        CurrentUserContext.set(alice);

        assertThat(screeningService.approveScreening(100L, new ApprovalRequest()).getState())
                .isEqualTo(ScreeningState.APPROVED);
    }

    @Test
    @DisplayName("FR-SCR-24: the SUBMITTER may NOT approve their own screening — 403 NOT_PROGRAMMER (M0 finding #2)")
    void fr24_theSubmitterCanNoLongerApproveTheirOwnScreening() {
        program.setState(ProgramState.SCHEDULING);
        screening.setState(ScreeningState.REVIEWED);
        CurrentUserContext.set(bob);

        assertThatThrownBy(() -> screeningService.approveScreening(100L, new ApprovalRequest()))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_PROGRAMMER");

        assertThat(screening.getState()).isEqualTo(ScreeningState.REVIEWED);
    }

    @Test
    @DisplayName("FR-SCR-24: the assigned STAFF handler may not approve either")
    void fr24_theHandlerCannotApprove() {
        program.setState(ProgramState.SCHEDULING);
        screening.setState(ScreeningState.REVIEWED);
        screening.setHandler(dave);
        CurrentUserContext.set(dave);

        assertThatThrownBy(() -> screeningService.approveScreening(100L, new ApprovalRequest()))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_PROGRAMMER");
    }

    @ParameterizedTest
    @EnumSource(value = ProgramState.class, names = {"SCHEDULING", "ANNOUNCED"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("FR-SCR-23: approval outside the program's SCHEDULING state is 409 PROGRAM_NOT_IN_SCHEDULING")
    void fr23_approvalRequiresTheProgramInScheduling(ProgramState state) {
        program.setState(state);
        screening.setState(ScreeningState.REVIEWED);
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.approveScreening(100L, new ApprovalRequest()))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PROGRAM_NOT_IN_SCHEDULING");

        assertThat(screening.getState()).isEqualTo(ScreeningState.REVIEWED);
    }

    @Test
    @DisplayName("FR-SCR-25: approval may carry conditional notes about the required final changes")
    void fr25_approvalMayCarryConditionalNotes() {
        program.setState(ProgramState.SCHEDULING);
        screening.setState(ScreeningState.REVIEWED);
        CurrentUserContext.set(alice);
        ApprovalRequest request = new ApprovalRequest();
        request.setApprovalNotes("Move the start 30 minutes later before the final submission");

        ScreeningResponse response = screeningService.approveScreening(100L, request);

        assertThat(response.getApprovalNotes())
                .isEqualTo("Move the start 30 minutes later before the final submission");
    }

    @Test
    @DisplayName("FR-SCR-25: the conditional notes are optional — an approval with none still succeeds")
    void fr25_theConditionalNotesAreOptional() {
        program.setState(ProgramState.SCHEDULING);
        screening.setState(ScreeningState.REVIEWED);
        CurrentUserContext.set(alice);

        ScreeningResponse response = screeningService.approveScreening(100L, new ApprovalRequest());

        assertThat(response.getState()).isEqualTo(ScreeningState.APPROVED);
        assertThat(response.getApprovalNotes()).isNull();
    }

    // ==================================================================
    // Manual rejection — FR-SCR-26, FR-SCR-27, FR-SCR-29, ROLE-08
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-26/ROLE-08: a PROGRAMMER rejects a REVIEWED screening in SCHEDULING, on the strength of the review")
    void fr26_aProgrammerRejectsAReviewedScreeningInScheduling() {
        program.setState(ProgramState.SCHEDULING);
        screening.setState(ScreeningState.REVIEWED);
        CurrentUserContext.set(alice);

        ScreeningResponse response = screeningService.rejectScreening(100L, rejection("Score too low"));

        assertThat(response.getState()).isEqualTo(ScreeningState.REJECTED);
        assertThat(response.getRejectionReason()).isEqualTo("Score too low");
    }

    @Test
    @DisplayName("FR-SCR-26: an already APPROVED screening may still be rejected while the program is in SCHEDULING")
    void fr26_anApprovedScreeningMayStillBeRejectedInScheduling() {
        program.setState(ProgramState.SCHEDULING);
        screening.setState(ScreeningState.APPROVED);
        CurrentUserContext.set(alice);

        assertThat(screeningService.rejectScreening(100L, rejection("Auditorium clash")).getState())
                .isEqualTo(ScreeningState.REJECTED);
    }

    @Test
    @DisplayName("FR-SCR-27: a PROGRAMMER rejects an APPROVED screening in DECISION when the final submission missed the point")
    void fr27_aProgrammerRejectsInDecision() {
        program.setState(ProgramState.DECISION);
        screening.setState(ScreeningState.APPROVED);
        screening.setFinalSubmissionDate(LocalDateTime.of(2026, 5, 20, 12, 0));
        CurrentUserContext.set(alice);

        ScreeningResponse response = screeningService.rejectScreening(
                100L, rejection("The final submission ignored the required changes"));

        assertThat(response.getState()).isEqualTo(ScreeningState.REJECTED);
        assertThat(response.getRejectionReason()).contains("required changes");
    }

    @Test
    @DisplayName("FR-SCR-26/ROLE-08: a user who is not a PROGRAMMER of the program cannot reject anything")
    void fr26_onlyAProgrammerMayReject() {
        program.setState(ProgramState.SCHEDULING);
        screening.setState(ScreeningState.REVIEWED);
        CurrentUserContext.set(bob);

        assertThatThrownBy(() -> screeningService.rejectScreening(100L, rejection("nope")))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_PROGRAMMER");

        assertThat(screening.getState()).isEqualTo(ScreeningState.REVIEWED);
    }

    @ParameterizedTest
    @EnumSource(value = ProgramState.class,
            names = {"SCHEDULING", "DECISION", "ANNOUNCED"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("FR-SCR-26/FR-SCR-27: a manual rejection outside SCHEDULING and DECISION is 409")
    void fr26_rejectionIsConfinedToSchedulingAndDecision(ProgramState state) {
        program.setState(state);
        screening.setState(ScreeningState.REVIEWED);
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.rejectScreening(100L, rejection("too early")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "REJECTION_NOT_ALLOWED_IN_STATE");

        assertThat(screening.getState()).isEqualTo(ScreeningState.REVIEWED);
    }

    @Test
    @DisplayName("FR-SCR-27: in DECISION only an APPROVED screening can be rejected, never a merely REVIEWED one")
    void fr27_inDecisionOnlyApprovedScreeningsMayBeRejected() {
        program.setState(ProgramState.DECISION);
        screening.setState(ScreeningState.REVIEWED);
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.rejectScreening(100L, rejection("late")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "REJECTION_NOT_ALLOWED_IN_STATE");
    }

    @Test
    @DisplayName("FR-SCR-29: a rejection with no reason at all is refused, and the screening is left untouched")
    void fr29_aRejectionWithoutAReasonIsImpossible() {
        program.setState(ProgramState.SCHEDULING);
        screening.setState(ScreeningState.REVIEWED);
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.rejectScreening(100L, rejection(null)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "REJECTION_REASON_REQUIRED");

        assertThat(screening.getState()).isEqualTo(ScreeningState.REVIEWED);
        assertThat(screening.getRejectionReason()).isNull();
        verify(screeningRepository, never()).save(any(Screening.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    @DisplayName("FR-SCR-29: a blank rejection reason counts as no reason at all")
    void fr29_aBlankReasonIsNoReason(String reason) {
        program.setState(ProgramState.SCHEDULING);
        screening.setState(ScreeningState.REVIEWED);
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.rejectScreening(100L, rejection(reason)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "REJECTION_REASON_REQUIRED");

        assertThat(screening.getState()).isEqualTo(ScreeningState.REVIEWED);
    }

    @Test
    @DisplayName("FR-SCR-29: every successful manual rejection records its reason on the screening itself")
    void fr29_everyManualRejectionRecordsItsReason() {
        program.setState(ProgramState.SCHEDULING);
        screening.setState(ScreeningState.APPROVED);
        CurrentUserContext.set(alice);

        screeningService.rejectScreening(100L, rejection("Duplicate of another screening"));

        assertThat(screening.getRejectionReason()).isEqualTo("Duplicate of another screening");
        assertThat(screening.getState()).isEqualTo(ScreeningState.REJECTED);
    }

    // ==================================================================
    // Final submission — FR-SCR-30, FR-SCR-31, FR-SCR-32
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-30/FR-SCR-31/ROLE-12: the SUBMITTER finally submits an APPROVED screening during FINAL_SUBMISSION")
    void fr30_theSubmitterFinallySubmits() {
        program.setState(ProgramState.FINAL_SUBMISSION);
        screening.setState(ScreeningState.APPROVED);
        CurrentUserContext.set(bob);

        ScreeningResponse response = screeningService.finalSubmitScreening(100L, new ScreeningUpdateRequest());

        assertThat(response.getFinallySubmitted()).isTrue();
        assertThat(screening.getFinalSubmissionDate()).isNotNull();
        assertThat(screening.getState()).isEqualTo(ScreeningState.APPROVED);
    }

    @Test
    @DisplayName("FR-SCR-30: the final submission may carry the final bundle of changes the approval notes asked for")
    void fr30_theFinalSubmissionMayCarryTheFinalChanges() {
        program.setState(ProgramState.FINAL_SUBMISSION);
        screening.setState(ScreeningState.APPROVED);
        CurrentUserContext.set(bob);
        ScreeningUpdateRequest request = new ScreeningUpdateRequest();
        request.setStartTime(LocalDateTime.of(2026, 6, 10, 20, 30));
        request.setEndTime(LocalDateTime.of(2026, 6, 10, 23, 0));

        ScreeningResponse response = screeningService.finalSubmitScreening(100L, request);

        assertThat(response.getStartTime()).isEqualTo(LocalDateTime.of(2026, 6, 10, 20, 30));
        assertThat(response.getFinallySubmitted()).isTrue();
    }

    @Test
    @DisplayName("FR-SCR-30/ROLE-12: a user who is not the SUBMITTER cannot finally submit the screening")
    void fr30_onlyTheSubmitterMayFinallySubmit() {
        program.setState(ProgramState.FINAL_SUBMISSION);
        screening.setState(ScreeningState.APPROVED);
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.finalSubmitScreening(100L, new ScreeningUpdateRequest()))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_SUBMITTER");

        assertThat(screening.isFinallySubmitted()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ProgramState.class,
            names = {"FINAL_SUBMISSION", "ANNOUNCED"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("FR-SCR-31: a final submission outside the program's FINAL_SUBMISSION state is 409")
    void fr31_finalSubmissionRequiresTheProgramInFinalSubmission(ProgramState state) {
        program.setState(state);
        screening.setState(ScreeningState.APPROVED);
        CurrentUserContext.set(bob);

        assertThatThrownBy(() -> screeningService.finalSubmitScreening(100L, new ScreeningUpdateRequest()))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PROGRAM_NOT_IN_FINAL_SUBMISSION");

        assertThat(screening.isFinallySubmitted()).isFalse();
    }

    @Test
    @DisplayName("FR-SCR-30: only an APPROVED screening can be finally submitted")
    void fr30_onlyAnApprovedScreeningCanBeFinallySubmitted() {
        program.setState(ProgramState.FINAL_SUBMISSION);
        screening.setState(ScreeningState.REVIEWED);
        CurrentUserContext.set(bob);

        assertThatThrownBy(() -> screeningService.finalSubmitScreening(100L, new ScreeningUpdateRequest()))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_NOT_APPROVED");
    }

    @Test
    @DisplayName("FR-SCR-32: the final submission cannot be repeated — the second attempt hits the freeze")
    void fr32_theFinalSubmissionCannotBeRepeated() {
        program.setState(ProgramState.FINAL_SUBMISSION);
        screening.setState(ScreeningState.APPROVED);
        CurrentUserContext.set(bob);

        screeningService.finalSubmitScreening(100L, new ScreeningUpdateRequest());
        LocalDateTime firstDate = screening.getFinalSubmissionDate();

        ScreeningUpdateRequest second = new ScreeningUpdateRequest();
        second.setFilmTitle("Sneaky rename");
        assertThatThrownBy(() -> screeningService.finalSubmitScreening(100L, second))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_DETAILS_FROZEN");

        assertThat(screening.getFinalSubmissionDate()).isEqualTo(firstDate);
        assertThat(screening.getFilmTitle()).isEqualTo("Star Wars");
    }

    @Test
    @DisplayName("FR-SCR-10: even the final bundle of changes cannot break the duration rule")
    void fr10_theFinalBundleStillObeysTheDurationRule() {
        program.setState(ProgramState.FINAL_SUBMISSION);
        screening.setState(ScreeningState.APPROVED);
        CurrentUserContext.set(bob);
        ScreeningUpdateRequest request = new ScreeningUpdateRequest();
        request.setEndTime(LocalDateTime.of(2026, 6, 10, 20, 30));   // 30 min for a 120 min film

        assertThatThrownBy(() -> screeningService.finalSubmitScreening(100L, request))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_DURATION_TOO_SHORT");

        assertThat(screening.isFinallySubmitted()).isFalse();
    }

    // ==================================================================
    // Acceptance — FR-SCR-34, FR-SCR-35, FR-SCR-36, ROLE-08
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-34/FR-SCR-35/FR-SCR-36/ROLE-08: a PROGRAMMER accepts an APPROVED, finally submitted screening in DECISION")
    void fr34_aProgrammerAcceptsTheScreening() {
        program.setState(ProgramState.DECISION);
        screening.setState(ScreeningState.APPROVED);
        screening.setFinalSubmissionDate(LocalDateTime.of(2026, 5, 20, 12, 0));
        CurrentUserContext.set(alice);

        assertThat(screeningService.acceptScreening(100L).getState()).isEqualTo(ScreeningState.SCHEDULED);
    }

    @Test
    @DisplayName("FR-SCR-34/ROLE-08: a user who is not a PROGRAMMER of the program cannot accept a screening")
    void fr34_onlyAProgrammerMayAccept() {
        program.setState(ProgramState.DECISION);
        screening.setState(ScreeningState.APPROVED);
        screening.setFinalSubmissionDate(LocalDateTime.of(2026, 5, 20, 12, 0));
        CurrentUserContext.set(bob);

        assertThatThrownBy(() -> screeningService.acceptScreening(100L))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_PROGRAMMER");

        assertThat(screening.getState()).isEqualTo(ScreeningState.APPROVED);
    }

    @ParameterizedTest
    @EnumSource(value = ProgramState.class, names = {"DECISION", "ANNOUNCED"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("FR-SCR-35: acceptance outside the program's DECISION state is 409 PROGRAM_NOT_IN_DECISION")
    void fr35_acceptanceRequiresTheProgramInDecision(ProgramState state) {
        program.setState(state);
        screening.setState(ScreeningState.APPROVED);
        screening.setFinalSubmissionDate(LocalDateTime.of(2026, 5, 20, 12, 0));
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.acceptScreening(100L))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PROGRAM_NOT_IN_DECISION");

        assertThat(screening.getState()).isEqualTo(ScreeningState.APPROVED);
    }

    @Test
    @DisplayName("FR-SCR-36: an APPROVED screening that was never finally submitted cannot be accepted")
    void fr36_acceptanceRequiresAFinalSubmission() {
        program.setState(ProgramState.DECISION);
        screening.setState(ScreeningState.APPROVED);
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.acceptScreening(100L))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "SCREENING_NOT_FINALLY_SUBMITTED");

        assertThat(screening.getState()).isEqualTo(ScreeningState.APPROVED);
    }

    @ParameterizedTest
    @EnumSource(value = ScreeningState.class, names = {"CREATED", "SUBMITTED", "REVIEWED"})
    @DisplayName("FR-SCR-36: a screening that never reached APPROVED cannot be accepted, however finally submitted it is")
    void fr36_acceptanceRequiresTheApprovedState(ScreeningState state) {
        program.setState(ProgramState.DECISION);
        screening.setState(state);
        screening.setFinalSubmissionDate(LocalDateTime.of(2026, 5, 20, 12, 0));
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> screeningService.acceptScreening(100L))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_SCREENING_STATE_TRANSITION");

        assertThat(screening.getState()).isEqualTo(state);
    }

    // ==================================================================
    // The ADMIN / inactive gate holds on EVERY screening function (ROLE-16)
    // ==================================================================

    @Test
    @DisplayName("ROLE-16: an ADMIN is rejected with 403 ADMIN_NOT_ALLOWED from every screening-management function")
    void role16_everyScreeningFunctionRejectsAdmin() {
        CurrentUserContext.set(admin);

        assertThatThrownBy(() -> screeningService.createScreening(10L, completeCreateRequest()))
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_NOT_ALLOWED");
        assertThatThrownBy(() -> screeningService.updateScreening(100L, new ScreeningUpdateRequest()))
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_NOT_ALLOWED");
        assertThatThrownBy(() -> screeningService.submitScreening(100L))
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_NOT_ALLOWED");
        assertThatThrownBy(() -> screeningService.withdrawScreening(100L))
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_NOT_ALLOWED");
        assertThatThrownBy(() -> screeningService.assignHandler(100L, handler("dave123")))
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_NOT_ALLOWED");
        assertThatThrownBy(() -> screeningService.reviewScreening(100L, review(8, "c")))
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_NOT_ALLOWED");
        assertThatThrownBy(() -> screeningService.approveScreening(100L, new ApprovalRequest()))
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_NOT_ALLOWED");
        assertThatThrownBy(() -> screeningService.rejectScreening(100L, rejection("r")))
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_NOT_ALLOWED");
        assertThatThrownBy(() -> screeningService.finalSubmitScreening(100L, new ScreeningUpdateRequest()))
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_NOT_ALLOWED");
        assertThatThrownBy(() -> screeningService.acceptScreening(100L))
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_NOT_ALLOWED");

        verify(screeningRepository, never()).save(any(Screening.class));
        verify(screeningRepository, never()).delete(any(Screening.class));
    }

    @Test
    @DisplayName("FR-USR-17: an inactive account is rejected with 403 ACCOUNT_INACTIVE from every screening function")
    void anInactiveAccountIsBlockedFromEveryScreeningFunction() {
        bob.setActive(false);
        CurrentUserContext.set(bob);

        assertThatThrownBy(() -> screeningService.updateScreening(100L, new ScreeningUpdateRequest()))
                .hasFieldOrPropertyWithValue("errorCode", "ACCOUNT_INACTIVE");
        assertThatThrownBy(() -> screeningService.submitScreening(100L))
                .hasFieldOrPropertyWithValue("errorCode", "ACCOUNT_INACTIVE");
        assertThatThrownBy(() -> screeningService.withdrawScreening(100L))
                .hasFieldOrPropertyWithValue("errorCode", "ACCOUNT_INACTIVE");
        assertThatThrownBy(() -> screeningService.finalSubmitScreening(100L, new ScreeningUpdateRequest()))
                .hasFieldOrPropertyWithValue("errorCode", "ACCOUNT_INACTIVE");
    }
}
