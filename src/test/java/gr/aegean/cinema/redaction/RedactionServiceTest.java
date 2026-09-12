package gr.aegean.cinema.redaction;

import gr.aegean.cinema.dto.program.ProgramResponse;
import gr.aegean.cinema.dto.screening.ScreeningResponse;
import gr.aegean.cinema.model.entity.Program;
import gr.aegean.cinema.model.entity.ProgramRole;
import gr.aegean.cinema.model.entity.Screening;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.PermanentRole;
import gr.aegean.cinema.model.enums.ProgramRoleType;
import gr.aegean.cinema.model.enums.ProgramState;
import gr.aegean.cinema.model.enums.ScreeningState;
import gr.aegean.cinema.repository.ProgramRoleRepository;
import gr.aegean.cinema.repository.ScreeningRepository;
import gr.aegean.cinema.repository.UserRepository;
import gr.aegean.cinema.security.AuthorizationService;
import gr.aegean.cinema.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The redaction rules themselves: FR-RED-01 … FR-RED-08.
 *
 * <p>As in every M3/M4 service test, the {@link AuthorizationService} is the
 * REAL one and only its repositories are doubles — the role questions
 * ("is this user a PROGRAMMER of that program?", "is this user the handler?")
 * are exactly what the redaction decision turns on, so mocking the
 * authorization service away would make every assertion here vacuous, and the
 * ADMIN exclusion in particular would become untestable.
 *
 * <p>FR-RED-01 and FR-RED-02 are not behavioural rules but structural ones —
 * "ONE central redaction component … never redact ad hoc inside a controller".
 * They are therefore proven structurally, by
 * {@link #fr_red_01_02_noResponseDtoIsBuiltOutsideTheRedactionComponent}, which
 * reads the production sources and asserts that no class other than
 * {@link RedactionService} builds a {@link ProgramResponse} or a
 * {@link ScreeningResponse}. A behavioural test could not catch a SECOND
 * redaction path being reintroduced somewhere else; this one does.
 */
class RedactionServiceTest {

    private ProgramRoleRepository programRoleRepository;
    private ScreeningRepository screeningRepository;
    private RedactionService redactionService;

    private User alice;    // PROGRAMMER of the program
    private User bob;      // SUBMITTER of the screening
    private User dave;     // STAFF, assigned handler of the screening
    private User carol;    // an outsider
    private User admin;

    private Program program;
    private Screening screening;

    @BeforeEach
    void setUp() {
        programRoleRepository = mock(ProgramRoleRepository.class);
        screeningRepository = mock(ScreeningRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        AuthorizationService authorizationService =
                new AuthorizationService(programRoleRepository, userRepository, mock(TokenService.class));
        redactionService = new RedactionService(authorizationService, programRoleRepository, screeningRepository);

        alice = user(1L, "alice01", PermanentRole.USER);
        bob = user(2L, "bob2024", PermanentRole.USER);
        carol = user(3L, "carol_x", PermanentRole.USER);
        dave = user(4L, "dave123", PermanentRole.USER);
        admin = user(99L, "admin1", PermanentRole.ADMIN);

        program = Program.builder()
                .id(10L).name("Summer Fest").description("A summer season")
                .startDate(LocalDate.of(2026, 6, 1)).endDate(LocalDate.of(2026, 6, 30))
                .state(ProgramState.ANNOUNCED).creator(alice)
                .createdAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .build();

        screening = Screening.builder()
                .id(100L).program(program).submitter(bob).handler(dave)
                .filmTitle("Blade Runner 2049").filmCast("Ryan Gosling").filmGenres("SciFi,Drama")
                .filmDurationMinutes(163).auditoriumName("Main Hall")
                .startTime(LocalDateTime.of(2026, 6, 10, 21, 0))
                .endTime(LocalDateTime.of(2026, 6, 11, 0, 0))
                .state(ScreeningState.SCHEDULED)
                .reviewScore(9).reviewComments("Excellent").approvalNotes("Confirm the print")
                .finalSubmissionDate(LocalDateTime.of(2026, 5, 1, 12, 0))
                .build();

        when(programRoleRepository.existsByUserAndProgramAndRole(alice, program, ProgramRoleType.PROGRAMMER))
                .thenReturn(true);
        when(programRoleRepository.existsByUserAndProgramAndRole(dave, program, ProgramRoleType.STAFF))
                .thenReturn(true);
        when(programRoleRepository.findByProgramAndRole(program, ProgramRoleType.PROGRAMMER))
                .thenReturn(List.of(role(alice, ProgramRoleType.PROGRAMMER)));
        when(programRoleRepository.findByProgramAndRole(program, ProgramRoleType.STAFF))
                .thenReturn(List.of(role(dave, ProgramRoleType.STAFF)));
        when(screeningRepository.findByProgram(program)).thenReturn(List.of(screening));
    }

    private static User user(long id, String username, PermanentRole role) {
        return User.builder().id(id).username(username).password("H").fullName(username)
                .permanentRole(role).active(true).build();
    }

    private ProgramRole role(User user, ProgramRoleType type) {
        return ProgramRole.builder().id(user.getId()).user(user).program(program).role(type).build();
    }

    // ==================================================================
    // FR-RED-03 — the VISITOR / USER program view
    // ==================================================================

    @Test
    @DisplayName("FR-RED-03: the VISITOR program view exposes only name, dates, auditorium, description and programmer names")
    void fr_red_03_theVisitorProgramViewExposesOnlyTheAllowedFields() {
        ProgramResponse response = redactionService.program(program, null);

        assertThat(response.getName()).isEqualTo("Summer Fest");
        assertThat(response.getDescription()).isEqualTo("A summer season");
        assertThat(response.getStartDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(response.getEndDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(response.getAuditoriums()).containsExactly("Main Hall");
        assertThat(response.getProgrammerUsernames()).containsExactly("alice01");

        assertThat(response.getState()).as("the lifecycle state is not a public field").isNull();
        assertThat(response.getCreatedAt()).isNull();
        assertThat(response.getCreatorUsername()).isNull();
        assertThat(response.getStaffUsernames()).as("the STAFF set is for PROGRAMMERs only").isNull();
    }

    @Test
    @DisplayName("FR-RED-03: a plain USER with no role in the program gets exactly the VISITOR view")
    void fr_red_03_aPlainUserGetsTheVisitorView() {
        ProgramResponse anonymous = redactionService.program(program, null);
        ProgramResponse plainUser = redactionService.program(program, carol);

        assertThat(plainUser.getStaffUsernames()).isNull();
        assertThat(plainUser.getState()).isNull();
        assertThat(plainUser.getName()).isEqualTo(anonymous.getName());
    }

    @Test
    @DisplayName("FR-RED-03 / ASSUMPTIONS #7: the derived auditorium set is distinct and sorted")
    void fr_red_03_theDerivedAuditoriumSetIsDistinctAndSorted() {
        Screening second = Screening.builder().id(101L).program(program).submitter(bob)
                .auditoriumName("Blue Room").state(ScreeningState.SCHEDULED).build();
        Screening third = Screening.builder().id(102L).program(program).submitter(bob)
                .auditoriumName("Main Hall").state(ScreeningState.CREATED).build();
        Screening blank = Screening.builder().id(103L).program(program).submitter(bob)
                .auditoriumName("  ").state(ScreeningState.CREATED).build();
        when(screeningRepository.findByProgram(program)).thenReturn(List.of(screening, second, third, blank));

        assertThat(redactionService.program(program, null).getAuditoriums())
                .containsExactly("Blue Room", "Main Hall");
    }

    // ==================================================================
    // FR-RED-04 — the VISITOR / USER screening view
    // ==================================================================

    @Test
    @DisplayName("FR-RED-04: the VISITOR screening view exposes only film title, genre, scheduled time and auditorium")
    void fr_red_04_theVisitorScreeningViewExposesOnlyTheAllowedFields() {
        ScreeningResponse response = redactionService.screening(screening, null);

        assertThat(response.getFilmTitle()).isEqualTo("Blade Runner 2049");
        assertThat(response.getFilmGenres()).isEqualTo("SciFi,Drama");
        assertThat(response.getStartTime()).isEqualTo(LocalDateTime.of(2026, 6, 10, 21, 0));
        assertThat(response.getAuditoriumName()).isEqualTo("Main Hall");

        assertThat(response.getState()).as("the migrated toVisitorResponse leaked this — INVENTORY.md").isNull();
        assertThat(response.getFilmCast()).isNull();
        assertThat(response.getFilmDurationMinutes()).isNull();
        assertThat(response.getEndTime()).isNull();
        assertThat(response.getReviewScore()).isNull();
        assertThat(response.getReviewComments()).isNull();
        assertThat(response.getApprovalNotes()).isNull();
        assertThat(response.getRejectionReason()).isNull();
        assertThat(response.getSubmitterUsername()).isNull();
        assertThat(response.getHandlerUsername()).isNull();
        assertThat(response.getFinallySubmitted())
                .as("boxed precisely so it can be absent rather than a misleading false").isNull();
    }

    // ==================================================================
    // FR-RED-05 / FR-RED-06 — the PROGRAMMER
    // ==================================================================

    @Test
    @DisplayName("FR-RED-05: a PROGRAMMER sees their own program in full, STAFF set and state included")
    void fr_red_05_theProgrammerSeesTheirProgramInFull() {
        ProgramResponse response = redactionService.program(program, alice);

        assertThat(response.getState()).isEqualTo(ProgramState.ANNOUNCED);
        assertThat(response.getCreatorUsername()).isEqualTo("alice01");
        assertThat(response.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 0));
        assertThat(response.getStaffUsernames()).containsExactly("dave123");
        assertThat(response.getAuditoriums()).containsExactly("Main Hall");
    }

    @Test
    @DisplayName("FR-RED-05: a PROGRAMMER of ANOTHER program gets only the VISITOR view of this one")
    void fr_red_05_aProgrammerOfAnotherProgramIsAnOutsiderHere() {
        Program foreign = Program.builder().id(11L).name("Other").description("d")
                .startDate(LocalDate.of(2026, 1, 1)).endDate(LocalDate.of(2026, 2, 1))
                .state(ProgramState.CREATED).creator(carol).build();
        when(programRoleRepository.existsByUserAndProgramAndRole(carol, foreign, ProgramRoleType.PROGRAMMER))
                .thenReturn(true);

        assertThat(redactionService.program(program, carol).getStaffUsernames()).isNull();
        assertThat(redactionService.hasFullProgramAccess(program, carol)).isFalse();
        assertThat(redactionService.hasFullProgramAccess(foreign, carol)).isTrue();
    }

    @Test
    @DisplayName("FR-RED-06: a PROGRAMMER sees every screening within their own program in full")
    void fr_red_06_theProgrammerSeesEveryScreeningInFull() {
        ScreeningResponse response = redactionService.screening(screening, alice);

        assertThat(response.getState()).isEqualTo(ScreeningState.SCHEDULED);
        assertThat(response.getFilmCast()).isEqualTo("Ryan Gosling");
        assertThat(response.getSubmitterUsername()).isEqualTo("bob2024");
        assertThat(response.getHandlerUsername()).isEqualTo("dave123");
        assertThat(response.getReviewScore()).isEqualTo(9);
        assertThat(response.getFinallySubmitted()).isTrue();
    }

    // ==================================================================
    // FR-RED-07 / FR-RED-08 — STAFF and SUBMITTER
    // ==================================================================

    @Test
    @DisplayName("FR-RED-07: STAFF sees a screening in full only when they are its assigned handler")
    void fr_red_07_staffSeesOnlyWhatTheyHandle() {
        assertThat(redactionService.screening(screening, dave).getReviewComments()).isEqualTo("Excellent");

        // Same STAFF member, a screening they were not assigned to.
        Screening other = Screening.builder().id(101L).program(program).submitter(bob)
                .filmTitle("Arrival").filmGenres("SciFi").auditoriumName("Blue Room")
                .state(ScreeningState.SCHEDULED).reviewComments("Not visible").build();

        ScreeningResponse response = redactionService.screening(other, dave);
        assertThat(response.getReviewComments()).isNull();
        assertThat(response.getState()).isNull();
        assertThat(response.getFilmTitle()).as("VISITOR rights everywhere else — ROLE-11").isEqualTo("Arrival");
    }

    @Test
    @DisplayName("FR-RED-08: a SUBMITTER sees a screening in full only when they own it")
    void fr_red_08_theSubmitterSeesOnlyTheirOwn() {
        assertThat(redactionService.screening(screening, bob).getSubmitterUsername()).isEqualTo("bob2024");

        Screening somebodyElses = Screening.builder().id(102L).program(program).submitter(carol)
                .filmTitle("Alien").filmGenres("Horror").auditoriumName("Main Hall")
                .state(ScreeningState.SCHEDULED).build();

        assertThat(redactionService.screening(somebodyElses, bob).getSubmitterUsername()).isNull();
        assertThat(redactionService.hasFullScreeningAccess(somebodyElses, bob)).isFalse();
        assertThat(redactionService.hasFullScreeningAccess(screening, bob)).isTrue();
    }

    @Test
    @DisplayName("FR-RED-07/08: a SUBMITTER has no privileged view of the PROGRAM their screening belongs to")
    void fr_red_07_08_aStakeInAScreeningGrantsNothingOnTheProgram() {
        assertThat(redactionService.program(program, bob).getStaffUsernames()).isNull();
        assertThat(redactionService.program(program, dave).getStaffUsernames()).isNull();
    }

    // ==================================================================
    // ROLE-01 / ROLE-02 — what is visible at all
    // ==================================================================

    @Test
    @DisplayName("ROLE-01: only ANNOUNCED programs are visible to a VISITOR; a PROGRAMMER sees theirs in every state")
    void role01_visibilityOfPrograms() {
        for (ProgramState state : ProgramState.values()) {
            program.setState(state);
            assertThat(redactionService.isProgramVisible(program, null))
                    .as("visitor, program in %s", state)
                    .isEqualTo(state == ProgramState.ANNOUNCED);
            assertThat(redactionService.isProgramVisible(program, alice))
                    .as("its PROGRAMMER, program in %s", state)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("ROLE-02: only a SCHEDULED screening inside an ANNOUNCED program is visible to a VISITOR")
    void role02_visibilityOfScreenings() {
        for (ScreeningState state : ScreeningState.values()) {
            screening.setState(state);
            assertThat(redactionService.isScreeningVisible(screening, null))
                    .as("visitor, screening in %s of an ANNOUNCED program", state)
                    .isEqualTo(state == ScreeningState.SCHEDULED);
        }

        screening.setState(ScreeningState.SCHEDULED);
        program.setState(ProgramState.DECISION);
        assertThat(redactionService.isScreeningVisible(screening, null))
                .as("scheduled, but the programme is not announced yet")
                .isFalse();
        assertThat(redactionService.isScreeningVisible(screening, bob))
                .as("its own SUBMITTER sees it regardless")
                .isTrue();
        assertThat(redactionService.isScreeningVisible(screening, dave))
                .as("its assigned handler sees it regardless")
                .isTrue();
    }

    // ==================================================================
    // ROLE-14/15/16 — the ADMIN exclusion reaches the redaction layer too
    // ==================================================================

    @Test
    @DisplayName("ROLE-15/16: an ADMIN can never obtain a full view, even if they somehow held a program role")
    void role15_16_anAdminNeverGetsAFullView() {
        // Deliberately contradictory stubbing: even if a PROGRAMMER row existed
        // for the ADMIN account, the redaction layer must still refuse.
        when(programRoleRepository.existsByUserAndProgramAndRole(admin, program, ProgramRoleType.PROGRAMMER))
                .thenReturn(true);

        assertThat(redactionService.hasFullProgramAccess(program, admin)).isFalse();
        assertThat(redactionService.hasFullScreeningAccess(screening, admin)).isFalse();
        assertThat(redactionService.program(program, admin).getStaffUsernames()).isNull();
        assertThat(redactionService.screening(screening, admin).getState()).isNull();
    }

    @Test
    @DisplayName("ROLE-15/16: an ADMIN is also limited to what a VISITOR may see at all")
    void role15_16_anAdminSeesOnlyWhatIsPublic() {
        program.setState(ProgramState.CREATED);
        assertThat(redactionService.isProgramVisible(program, admin)).isFalse();

        screening.setState(ScreeningState.CREATED);
        assertThat(redactionService.isScreeningVisible(screening, admin)).isFalse();
    }

    // ==================================================================
    // FR-RED-01 / FR-RED-02 — ONE component, structurally
    // ==================================================================

    @Test
    @DisplayName("FR-RED-01/FR-RED-02: no class other than RedactionService builds a Program or Screening response")
    void fr_red_01_02_noResponseDtoIsBuiltOutsideTheRedactionComponent() throws IOException {
        Path mainSources = Paths.get("src", "main", "java");
        assertThat(mainSources).as("the production sources must be readable from the test working directory")
                .exists();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainSources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("RedactionService.java")
                        || name.equals("ProgramResponse.java")
                        || name.equals("ScreeningResponse.java")) {
                    continue; // the component itself, and the two DTO declarations
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.contains("ProgramResponse.builder()") || source.contains("ScreeningResponse.builder()")) {
                    offenders.add(file.toString());
                }
            }
        }

        assertThat(offenders)
                .as("DOMAIN_RULES.md: ONE central redaction layer, never ad hoc elsewhere")
                .isEmpty();
    }

    @Test
    @DisplayName("FR-RED-01/FR-RED-02: no controller decides anything about redaction")
    void fr_red_01_02_noControllerRedactsAnything() throws IOException {
        Path controllers = Paths.get("src", "main", "java", "gr", "aegean", "cinema", "controller");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(controllers)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.contains("RedactionService")
                        || source.contains("CurrentUserContext")
                        || source.contains("isProgrammerOf")
                        || source.contains("setState(")) {
                    offenders.add(file.toString());
                }
            }
        }

        assertThat(offenders)
                .as("controllers map HTTP to a service call and nothing else")
                .isEmpty();
    }

    /** A guard on the two DTOs themselves: every field must be nullable, or redaction cannot omit it. */
    @Test
    @DisplayName("FR-RED-03/04: every field of both response DTOs is nullable, so a redacted field is ABSENT, not empty")
    void fr_red_03_04_everyResponseFieldIsNullable() {
        assertThat(Stream.of(ProgramResponse.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .filter(f -> f.getType().isPrimitive())
                .map(java.lang.reflect.Field::getName))
                .as("a primitive field always serializes, so it can never be redacted away")
                .isEmpty();

        assertThat(Stream.of(ScreeningResponse.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .filter(f -> f.getType().isPrimitive())
                .map(java.lang.reflect.Field::getName))
                .isEmpty();
    }

    @Test
    @DisplayName("FR-RED-01/02: both services delegate to the shared component rather than to a private mapper")
    void fr_red_01_02_bothServicesDelegateToTheSharedComponent() throws IOException {
        for (String service : List.of("ProgramServiceImpl.java", "ScreeningServiceImpl.java")) {
            String source = Files.readString(
                    Paths.get("src", "main", "java", "gr", "aegean", "cinema", "service", "impl", service),
                    StandardCharsets.UTF_8);
            assertThat(source).as("%s must hold a RedactionService", service).contains("redactionService");
            assertThat(source).as("%s must no longer own a redacted mapper", service)
                    .doesNotContain("toRedactedResponse")
                    .doesNotContain("toVisitorResponse");
        }
    }
}
