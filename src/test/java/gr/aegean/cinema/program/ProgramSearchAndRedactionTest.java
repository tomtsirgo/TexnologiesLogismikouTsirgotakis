package gr.aegean.cinema.program;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.aegean.cinema.model.entity.Program;
import gr.aegean.cinema.model.entity.ProgramRole;
import gr.aegean.cinema.model.entity.Screening;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.PermanentRole;
import gr.aegean.cinema.model.enums.ProgramRoleType;
import gr.aegean.cinema.model.enums.ProgramState;
import gr.aegean.cinema.model.enums.ScreeningState;
import gr.aegean.cinema.repository.AuthTokenRepository;
import gr.aegean.cinema.repository.ProgramRepository;
import gr.aegean.cinema.repository.ProgramRoleRepository;
import gr.aegean.cinema.repository.ScreeningRepository;
import gr.aegean.cinema.repository.UserRepository;
import gr.aegean.cinema.security.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Program search and program redaction over real HTTP, against the real
 * specifications, the real central redaction layer and an in-memory H2
 * database: FR-PRG-15 … FR-PRG-23, plus ROLE-01 and ROLE-03.
 *
 * <p>The fixture is deliberately built straight through the repositories rather
 * than through the seven lifecycle endpoints: what is under test here is what a
 * search returns and what a response discloses, and driving four programs
 * through the whole state machine first would only add noise (and coupling to
 * rules already proven by {@link ProgramStateMachineTest}).
 *
 * <p>The sharpest row in the file is FR-PRG-19. The migrated
 * {@code ProgramSpecifications.nameContains}/{@code descriptionContains} did a
 * single whole-string {@code LIKE %value%} (INVENTORY.md), so a two-word filter
 * whose words are not adjacent in the field silently returned nothing.
 * {@link #fr19_everyWordEnteredMustAppearEvenWhenTheWordsAreNotAdjacent} and
 * {@link #fr19_theDescriptionFilterSplitsIntoWordsToo} are built exactly on that
 * case and fail against the old implementation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProgramSearchAndRedactionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthTokenRepository authTokenRepository;
    @Autowired private ProgramRepository programRepository;
    @Autowired private ProgramRoleRepository programRoleRepository;
    @Autowired private ScreeningRepository screeningRepository;
    @Autowired private PasswordUtil passwordUtil;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User alice;   // PROGRAMMER (creator) of "Summer Star Wars Retrospective" and of the secret season
    private User bob;     // SUBMITTER of the screenings
    private User carol;   // PROGRAMMER (creator) of the two other announced programs
    private User dave;    // STAFF of the retrospective

    private String aliceToken;
    private String carolToken;
    private String adminToken;

    private Program retrospective;   // ANNOUNCED, 2026-06-01
    private Program springGala;      // ANNOUNCED, 2026-06-01 (same date, earlier name)
    private Program dramaWeek;       // ANNOUNCED, 2026-09-01
    private Program secretSeason;    // CREATED   — visible to Alice only

    @BeforeEach
    void buildTheCatalogue() throws Exception {
        authTokenRepository.deleteAll();
        screeningRepository.deleteAll();
        programRoleRepository.deleteAll();
        programRepository.deleteAll();
        userRepository.deleteAll();

        alice = persist("alice01", "UserPass1!", PermanentRole.USER);
        bob = persist("bob2024", "UserPass2!", PermanentRole.USER);
        carol = persist("carol_x", "UserPass3!", PermanentRole.USER);
        dave = persist("dave123", "UserPass4!", PermanentRole.USER);
        persist("admin1", "AdminPass1!", PermanentRole.ADMIN);

        aliceToken = login("alice01", "UserPass1!");
        carolToken = login("carol_x", "UserPass3!");
        adminToken = login("admin1", "AdminPass1!");

        retrospective = program("Summer Star Wars Retrospective",
                "A retrospective of classic space opera films",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), ProgramState.ANNOUNCED, alice);
        role(alice, retrospective, ProgramRoleType.PROGRAMMER);
        role(dave, retrospective, ProgramRoleType.STAFF);
        screening(retrospective, "Star Wars: A New Hope", "Mark Hamill, Carrie Fisher", "SciFi,Adventure",
                "Main Hall", LocalDateTime.of(2026, 6, 10, 20, 0), ScreeningState.SCHEDULED, bob);
        screening(retrospective, "The Empire Strikes Back", "Mark Hamill", "Adventure,SciFi",
                "Blue Room", LocalDateTime.of(2026, 6, 12, 18, 0), ScreeningState.SCHEDULED, bob);
        // A draft in an announced programme: it still contributes its auditorium
        // to the derived field, but never appears as a public screening.
        screening(retrospective, "Unfinished Documentary", "Nobody", "Documentary",
                "Main Hall", LocalDateTime.of(2026, 6, 14, 10, 0), ScreeningState.CREATED, bob);

        springGala = program("Alpha Spring Gala", "An opening gala of short films",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 15), ProgramState.ANNOUNCED, carol);
        role(carol, springGala, ProgramRoleType.PROGRAMMER);
        screening(springGala, "Short Film Anthology", "Various", "Shorts",
                "Green Room", LocalDateTime.of(2026, 6, 2, 19, 0), ScreeningState.SCHEDULED, bob);

        dramaWeek = program("Autumn Drama Week", "Drama films from the new wave",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 10), ProgramState.ANNOUNCED, carol);
        role(carol, dramaWeek, ProgramRoleType.PROGRAMMER);
        screening(dramaWeek, "Autumn Sonata", "Ingrid Bergman", "Drama",
                "Green Room", LocalDateTime.of(2026, 9, 3, 20, 30), ScreeningState.SCHEDULED, bob);

        secretSeason = program("Winter Secret Season", "A secret winter programme of drama films",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 20), ProgramState.CREATED, alice);
        role(alice, secretSeason, ProgramRoleType.PROGRAMMER);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private User persist(String username, String rawPassword, PermanentRole role) {
        return userRepository.save(User.builder()
                .username(username).password(passwordUtil.hash(rawPassword)).fullName(username)
                .permanentRole(role).active(true).failedAuthAttempts(0).failedPasswordAttempts(0).build());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private Program program(String name, String description, LocalDate start, LocalDate end,
                            ProgramState state, User creator) {
        return programRepository.save(Program.builder()
                .name(name).description(description).startDate(start).endDate(end)
                .state(state).creator(creator).build());
    }

    private void role(User user, Program program, ProgramRoleType roleType) {
        programRoleRepository.save(ProgramRole.builder().user(user).program(program).role(roleType).build());
    }

    private void screening(Program program, String title, String cast, String genres, String auditorium,
                           LocalDateTime start, ScreeningState state, User submitter) {
        screeningRepository.save(Screening.builder()
                .program(program).submitter(submitter).state(state)
                .filmTitle(title).filmCast(cast).filmGenres(genres).filmDurationMinutes(120)
                .auditoriumName(auditorium).startTime(start).endTime(start.plusHours(3))
                .build());
    }

    /** Runs a program search and returns the program names, in the order the API returned them. */
    private List<String> searchNames(String token, String... queryParams) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/programs");
        for (int i = 0; i < queryParams.length; i += 2) {
            request = request.param(queryParams[i], queryParams[i + 1]);
        }
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        List<String> names = new ArrayList<>();
        body.forEach(node -> names.add(node.get("name").asText()));
        return names;
    }

    /** The same search, performed with no Authorization header at all: a true anonymous VISITOR. */
    private List<String> visitorSearch(String... queryParams) throws Exception {
        return searchNames(null, queryParams);
    }

    // ==================================================================
    // FR-PRG-15 — the five documented filters
    // ==================================================================

    @Test
    @DisplayName("FR-PRG-15: program search accepts name, description, dates, film title and auditorium together")
    void fr15_everyDocumentedFilterIsAccepted() throws Exception {
        assertThat(visitorSearch(
                "name", "Retrospective",
                "description", "space opera",
                "startFrom", "2026-05-01",
                "endTo", "2026-07-31",
                "filmTitle", "Star Wars",
                "auditorium", "Main Hall"))
                .containsExactly("Summer Star Wars Retrospective");
    }

    @Test
    @DisplayName("FR-PRG-15: the film-title filter matches a program through its screenings")
    void fr15_theFilmTitleFilterMatchesThroughTheScreenings() throws Exception {
        assertThat(visitorSearch("filmTitle", "Autumn Sonata")).containsExactly("Autumn Drama Week");
    }

    @Test
    @DisplayName("FR-PRG-15: the auditorium filter matches a program through its screenings")
    void fr15_theAuditoriumFilterMatchesThroughTheScreenings() throws Exception {
        assertThat(visitorSearch("auditorium", "Green Room"))
                .containsExactly("Alpha Spring Gala", "Autumn Drama Week");
    }

    @Test
    @DisplayName("FR-PRG-15 / ASSUMPTIONS #18: the date filters test the program's own season dates")
    void fr15_theDateFiltersTestTheProgramsOwnSeasonDates() throws Exception {
        assertThat(visitorSearch("startFrom", "2026-07-01")).containsExactly("Autumn Drama Week");
        assertThat(visitorSearch("endTo", "2026-06-20")).containsExactly("Alpha Spring Gala");
    }

    // ==================================================================
    // FR-PRG-16 / FR-PRG-17 — AND semantics, and the empty search
    // ==================================================================

    @Test
    @DisplayName("FR-PRG-16: supplied filters are ANDed, never ORed")
    void fr16_filtersAreCombinedWithAnd() throws Exception {
        // Each half matches on its own ...
        assertThat(visitorSearch("name", "Retrospective")).containsExactly("Summer Star Wars Retrospective");
        assertThat(visitorSearch("auditorium", "Green Room")).hasSize(2);
        // ... but no program satisfies both at once.
        assertThat(visitorSearch("name", "Retrospective", "auditorium", "Green Room")).isEmpty();
    }

    @Test
    @DisplayName("FR-PRG-17: a search with no criteria returns everything the requester may see")
    void fr17_noCriteriaReturnsEverythingTheRequesterMaySee() throws Exception {
        assertThat(visitorSearch())
                .containsExactly("Alpha Spring Gala", "Summer Star Wars Retrospective", "Autumn Drama Week");

        // Alice additionally owns a programme that has not been announced yet.
        assertThat(searchNames(aliceToken)).contains("Winter Secret Season");
    }

    // ==================================================================
    // FR-PRG-18 / FR-PRG-19 — case-insensitive, every-word matching
    // ==================================================================

    @Test
    @DisplayName("FR-PRG-18: text filters are case-insensitive")
    void fr18_textFiltersAreCaseInsensitive() throws Exception {
        assertThat(visitorSearch("name", "sTaR wArS")).containsExactly("Summer Star Wars Retrospective");
        assertThat(visitorSearch("description", "SPACE OPERA")).containsExactly("Summer Star Wars Retrospective");
        assertThat(visitorSearch("filmTitle", "eMpIrE")).containsExactly("Summer Star Wars Retrospective");
        assertThat(visitorSearch("auditorium", "GREEN room")).hasSize(2);
    }

    @Test
    @DisplayName("FR-PRG-19: every word entered must appear, even when the words are not adjacent in the name")
    void fr19_everyWordEnteredMustAppearEvenWhenTheWordsAreNotAdjacent() throws Exception {
        // "Summer Star Wars Retrospective" contains both words, but NOT the
        // substring "star retrospective" — the whole-string LIKE the migrated
        // code used returns nothing here. Word order is irrelevant too.
        assertThat(visitorSearch("name", "star retrospective"))
                .containsExactly("Summer Star Wars Retrospective");
        assertThat(visitorSearch("name", "retrospective star"))
                .containsExactly("Summer Star Wars Retrospective");
    }

    @Test
    @DisplayName("FR-PRG-19: the description filter splits into words too")
    void fr19_theDescriptionFilterSplitsIntoWordsToo() throws Exception {
        assertThat(visitorSearch("description", "retrospective films"))
                .containsExactly("Summer Star Wars Retrospective");
    }

    @Test
    @DisplayName("FR-PRG-19: a single absent word excludes the program, however well the others match")
    void fr19_oneAbsentWordExcludesTheProgram() throws Exception {
        assertThat(visitorSearch("name", "star retrospective westerns")).isEmpty();
        assertThat(visitorSearch("filmTitle", "star wars documentary")).isEmpty();
    }

    @Test
    @DisplayName("FR-PRG-19: the every-word rule also applies to the film-title and auditorium filters")
    void fr19_theRuleAppliesToTheScreeningBackedFiltersToo() throws Exception {
        assertThat(visitorSearch("filmTitle", "hope wars"))
                .containsExactly("Summer Star Wars Retrospective");
        assertThat(visitorSearch("auditorium", "hall main"))
                .containsExactly("Summer Star Wars Retrospective");
    }

    // ==================================================================
    // FR-PRG-20 / ROLE-01 — role filtering, before anything else
    // ==================================================================

    @Test
    @DisplayName("ROLE-01 / FR-PRG-20: a VISITOR never sees a program that has not been ANNOUNCED")
    void role01_aVisitorNeverSeesAnUnannouncedProgram() throws Exception {
        assertThat(visitorSearch()).doesNotContain("Winter Secret Season");
        assertThat(visitorSearch("name", "Winter Secret Season")).isEmpty();
    }

    @Test
    @DisplayName("ROLE-01 / FR-PRG-20: a plain USER with no role in the program is filtered exactly like a VISITOR")
    void role01_aPlainUserIsFilteredLikeAVisitor() throws Exception {
        // Carol is a PROGRAMMER — but of OTHER programmes; on Alice's secret season she is nobody.
        assertThat(searchNames(carolToken)).doesNotContain("Winter Secret Season");
    }

    @Test
    @DisplayName("FR-PRG-20: a PROGRAMMER sees their own program in every state")
    void fr20_aProgrammerSeesTheirOwnUnannouncedProgram() throws Exception {
        assertThat(searchNames(aliceToken, "name", "Winter Secret"))
                .containsExactly("Winter Secret Season");
    }

    @Test
    @DisplayName("FR-PRG-20: an ADMIN gets VISITOR-level results, never a privileged view")
    void fr20_anAdminIsTreatedAsAVisitorBySearch() throws Exception {
        assertThat(searchNames(adminToken)).doesNotContain("Winter Secret Season");

        mockMvc.perform(get("/api/programs/" + retrospective.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffUsernames").doesNotExist())
                .andExpect(jsonPath("$.state").doesNotExist());
    }

    // ==================================================================
    // FR-PRG-21 / FR-PRG-22 — sorting
    // ==================================================================

    @Test
    @DisplayName("FR-PRG-21: results are sorted primarily by date")
    void fr21_resultsAreSortedPrimarilyByDate() throws Exception {
        // September last, despite "Autumn" sorting before "Summer" alphabetically.
        assertThat(visitorSearch()).last().isEqualTo("Autumn Drama Week");
        assertThat(visitorSearch())
                .containsExactly("Alpha Spring Gala", "Summer Star Wars Retrospective", "Autumn Drama Week");
    }

    @Test
    @DisplayName("FR-PRG-22: programs sharing a start date are ordered by name")
    void fr22_equalDatesAreBrokenByName() throws Exception {
        List<String> sameDate = visitorSearch("startFrom", "2026-06-01", "endTo", "2026-06-30");
        assertThat(sameDate).containsExactly("Alpha Spring Gala", "Summer Star Wars Retrospective");
    }

    @Test
    @DisplayName("FR-PRG-20 + FR-PRG-21: role filtering happens BEFORE sorting, so a hidden program shifts nothing")
    void fr20_roleFilteringHappensBeforeSorting() throws Exception {
        // The secret season starts in January, i.e. it would sort FIRST if it
        // took part at all. A visitor's first result must still be the gala.
        assertThat(visitorSearch()).first().isEqualTo("Alpha Spring Gala");
        assertThat(searchNames(aliceToken)).first().isEqualTo("Winter Secret Season");
    }

    // ==================================================================
    // FR-PRG-23 — redaction of a single program view
    // ==================================================================

    @Test
    @DisplayName("FR-PRG-23: the VISITOR view of a program carries only name, dates, auditorium, description and programmer names")
    void fr23_theVisitorViewCarriesOnlyTheAllowedFields() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/programs/" + retrospective.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Summer Star Wars Retrospective"))
                .andExpect(jsonPath("$.description").value("A retrospective of classic space opera films"))
                .andExpect(jsonPath("$.startDate").value("2026-06-01"))
                .andExpect(jsonPath("$.endDate").value("2026-06-30"))
                .andExpect(jsonPath("$.programmerUsernames[0]").value("alice01"))
                .andExpect(jsonPath("$.auditoriums").isArray())
                // Everything outside the allowed set is absent, not merely empty.
                .andExpect(jsonPath("$.state").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.creatorUsername").doesNotExist())
                .andExpect(jsonPath("$.staffUsernames").doesNotExist())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.fieldNames()).toIterable()
                .as("FR-RED-03: nothing beyond the allowed field set, plus the resource id (ASSUMPTIONS #41)")
                .containsExactlyInAnyOrder("id", "name", "description", "startDate", "endDate",
                        "programmerUsernames", "auditoriums");
    }

    @Test
    @DisplayName("FR-PRG-23: the PROGRAMMER's own view of the program is complete, STAFF set and state included")
    void fr23_theProgrammerViewIsComplete() throws Exception {
        mockMvc.perform(get("/api/programs/" + retrospective.getId())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ANNOUNCED"))
                .andExpect(jsonPath("$.creatorUsername").value("alice01"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.staffUsernames[0]").value("dave123"));
    }

    @Test
    @DisplayName("FR-PRG-23 / ASSUMPTIONS #7: the derived auditorium field is the distinct, sorted set of the program's screening auditoriums")
    void fr23_theDerivedAuditoriumFieldIsDistinctAndSorted() throws Exception {
        // Three screenings use "Main Hall", "Blue Room" and "Main Hall" again.
        mockMvc.perform(get("/api/programs/" + retrospective.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditoriums.length()").value(2))
                .andExpect(jsonPath("$.auditoriums[0]").value("Blue Room"))
                .andExpect(jsonPath("$.auditoriums[1]").value("Main Hall"));
    }

    @Test
    @DisplayName("FR-PRG-23: a program with no screenings yet reports an empty auditorium set, not an error")
    void fr23_aProgramWithoutScreeningsHasAnEmptyAuditoriumSet() throws Exception {
        mockMvc.perform(get("/api/programs/" + secretSeason.getId())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditoriums.length()").value(0));
    }

    @Test
    @DisplayName("FR-PRG-23 / ROLE-01: viewing an unannounced program as an outsider is 404, not 403")
    void fr23_viewingAnUnannouncedProgramAsAnOutsiderIs404() throws Exception {
        mockMvc.perform(get("/api/programs/" + secretSeason.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PROGRAM_NOT_FOUND"));

        mockMvc.perform(get("/api/programs/" + secretSeason.getId())
                        .header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PROGRAM_NOT_FOUND"));
    }

    @Test
    @DisplayName("FR-PRG-23: search results are redacted per requester exactly as the single view is")
    void fr23_searchResultsUseTheSameRedactionAsTheSingleView() throws Exception {
        MvcResult visitor = mockMvc.perform(get("/api/programs").param("name", "Retrospective"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").doesNotExist())
                .andExpect(jsonPath("$[0].staffUsernames").doesNotExist())
                .andReturn();
        assertThat(visitor.getResponse().getContentAsString()).doesNotContain("dave123");

        mockMvc.perform(get("/api/programs").param("name", "Retrospective")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("ANNOUNCED"))
                .andExpect(jsonPath("$[0].staffUsernames[0]").value("dave123"));
    }

    // ==================================================================
    // ROLE-03 — a VISITOR may not manage anything
    // ==================================================================

    @Test
    @DisplayName("ROLE-03: a VISITOR (no token) is refused every program-management action")
    void role03_aVisitorMayNotManageAnything() throws Exception {
        mockMvc.perform(post("/api/programs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pirate Season\",\"description\":\"d\","
                                + "\"startDate\":\"2026-06-01\",\"endDate\":\"2026-06-30\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_MISSING"));

        mockMvc.perform(put("/api/programs/" + retrospective.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hijacked\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/programs/" + dramaWeek.getId()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/programs/" + dramaWeek.getId() + "/announce"))
                .andExpect(status().isUnauthorized());

        assertThat(programRepository.count()).as("nothing was created or deleted").isEqualTo(4);
    }
}
