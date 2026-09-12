package gr.aegean.cinema.screening;

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
 * Screening search, timetable mode and screening redaction over real HTTP:
 * FR-SCR-37 … FR-SCR-46, plus ROLE-02, ROLE-11 and ROLE-13.
 *
 * <p>The fixture is one ANNOUNCED programme holding four public (SCHEDULED)
 * screenings and one private draft, plus a second programme used only to prove
 * that a screening search never leaks across programme boundaries. The genres
 * are chosen so that the two orderings the specification demands are genuinely
 * different from each other and from alphabetical title order, which is what
 * makes FR-SCR-43/44/45 falsifiable rather than accidentally satisfied:
 *
 * <pre>
 *   by genre then title : Amelie, Arrival, Blade Runner 2049, Alien
 *   by start time       : Amelie, Blade Runner 2049, Alien, Arrival
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScreeningSearchAndRedactionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthTokenRepository authTokenRepository;
    @Autowired private ProgramRepository programRepository;
    @Autowired private ProgramRoleRepository programRoleRepository;
    @Autowired private ScreeningRepository screeningRepository;
    @Autowired private PasswordUtil passwordUtil;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User alice;   // PROGRAMMER of the festival
    private User bob;     // SUBMITTER of most screenings, including the private draft
    private User carol;   // an outsider with no role anywhere
    private User dave;    // STAFF of the festival, handler of "Blade Runner 2049"
    private User erin;    // SUBMITTER of "Alien" only

    private String aliceToken;
    private String bobToken;
    private String carolToken;
    private String daveToken;
    private String adminToken;

    private Program festival;
    private Program otherFestival;

    private Screening amelie;
    private Screening bladeRunner;
    private Screening alien;
    private Screening arrival;
    private Screening privateDraft;

    @BeforeEach
    void buildTheProgramme() throws Exception {
        authTokenRepository.deleteAll();
        screeningRepository.deleteAll();
        programRoleRepository.deleteAll();
        programRepository.deleteAll();
        userRepository.deleteAll();

        alice = persist("alice01", "UserPass1!", PermanentRole.USER);
        bob = persist("bob2024", "UserPass2!", PermanentRole.USER);
        carol = persist("carol_x", "UserPass3!", PermanentRole.USER);
        dave = persist("dave123", "UserPass4!", PermanentRole.USER);
        erin = persist("erin567", "UserPass5!", PermanentRole.USER);
        persist("admin1", "AdminPass1!", PermanentRole.ADMIN);

        aliceToken = login("alice01", "UserPass1!");
        bobToken = login("bob2024", "UserPass2!");
        carolToken = login("carol_x", "UserPass3!");
        daveToken = login("dave123", "UserPass4!");
        adminToken = login("admin1", "AdminPass1!");

        festival = program("Summer Festival", ProgramState.ANNOUNCED, alice);
        role(alice, festival, ProgramRoleType.PROGRAMMER);
        role(dave, festival, ProgramRoleType.STAFF);

        amelie = screening(festival, "Amelie", "Audrey Tautou, Mathieu Kassovitz", "Comedy,Romance",
                "Blue Room", LocalDateTime.of(2026, 6, 9, 18, 0), ScreeningState.SCHEDULED, bob, null);
        bladeRunner = screening(festival, "Blade Runner 2049", "Ryan Gosling, Harrison Ford", "SciFi,Drama",
                "Main Hall", LocalDateTime.of(2026, 6, 10, 21, 0), ScreeningState.SCHEDULED, bob, dave);
        alien = screening(festival, "Alien", "Sigourney Weaver", "SciFi,Horror",
                "Main Hall", LocalDateTime.of(2026, 6, 11, 22, 0), ScreeningState.SCHEDULED, erin, null);
        arrival = screening(festival, "Arrival", "Amy Adams, Jeremy Renner", "SciFi,Drama",
                "Blue Room", LocalDateTime.of(2026, 6, 13, 20, 0), ScreeningState.SCHEDULED, bob, null);
        privateDraft = screening(festival, "Hidden Draft", "Nobody At All", "Documentary",
                "Grey Room", LocalDateTime.of(2026, 6, 8, 10, 0), ScreeningState.CREATED, bob, null);

        otherFestival = program("Autumn Festival", ProgramState.ANNOUNCED, carol);
        role(carol, otherFestival, ProgramRoleType.PROGRAMMER);
        screening(otherFestival, "Alien", "Sigourney Weaver", "SciFi,Horror",
                "Red Room", LocalDateTime.of(2026, 9, 3, 20, 0), ScreeningState.SCHEDULED, bob, null);
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

    private Program program(String name, ProgramState state, User creator) {
        return programRepository.save(Program.builder()
                .name(name).description("A season of films")
                .startDate(LocalDate.of(2026, 6, 1)).endDate(LocalDate.of(2026, 9, 30))
                .state(state).creator(creator).build());
    }

    private void role(User user, Program program, ProgramRoleType roleType) {
        programRoleRepository.save(ProgramRole.builder().user(user).program(program).role(roleType).build());
    }

    private Screening screening(Program program, String title, String cast, String genres, String auditorium,
                                LocalDateTime start, ScreeningState state, User submitter, User handler) {
        return screeningRepository.save(Screening.builder()
                .program(program).submitter(submitter).handler(handler).state(state)
                .filmTitle(title).filmCast(cast).filmGenres(genres).filmDurationMinutes(120)
                .auditoriumName(auditorium).startTime(start).endTime(start.plusHours(3))
                .reviewScore(8).reviewComments("A strong fit for the programme")
                .approvalNotes("Please confirm the print format")
                .build());
    }

    /** Runs a screening search inside {@link #festival} and returns the titles, in API order. */
    private List<String> search(String token, String... queryParams) throws Exception {
        return searchIn(festival, token, queryParams);
    }

    private List<String> searchIn(Program program, String token, String... queryParams) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/programs/" + program.getId() + "/screenings");
        for (int i = 0; i < queryParams.length; i += 2) {
            request = request.param(queryParams[i], queryParams[i + 1]);
        }
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();

        List<String> titles = new ArrayList<>();
        objectMapper.readTree(result.getResponse().getContentAsString())
                .forEach(node -> titles.add(node.get("filmTitle").asText()));
        return titles;
    }

    private List<String> visitorSearch(String... queryParams) throws Exception {
        return search(null, queryParams);
    }

    // ==================================================================
    // FR-SCR-37 / FR-SCR-38 — the four filters, within one program, ANDed
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-37: screening search accepts film title, cast, genre and a date range, all optional")
    void fr37_allFourFilterKindsAreAccepted() throws Exception {
        assertThat(visitorSearch(
                "title", "Blade Runner",
                "cast", "Gosling",
                "genre", "SciFi",
                "dateFrom", "2026-06-10T00:00:00",
                "dateTo", "2026-06-10T23:59:59"))
                .containsExactly("Blade Runner 2049");
    }

    @Test
    @DisplayName("FR-SCR-37: the search is scoped to ONE program and never crosses into another")
    void fr37_theSearchIsScopedToOneProgram() throws Exception {
        // "Alien" exists in both programmes; each search sees only its own.
        assertThat(visitorSearch("title", "Alien")).containsExactly("Alien");
        assertThat(searchIn(otherFestival, null, "title", "Alien")).containsExactly("Alien");
        assertThat(visitorSearch()).hasSize(4);
        assertThat(searchIn(otherFestival, null)).hasSize(1);
    }

    @Test
    @DisplayName("FR-SCR-38: supplied filters are ANDed, never ORed")
    void fr38_filtersAreCombinedWithAnd() throws Exception {
        assertThat(visitorSearch("genre", "SciFi")).hasSize(3);
        assertThat(visitorSearch("cast", "Audrey")).containsExactly("Amelie");
        // No screening is both a SciFi film and one starring Audrey Tautou.
        assertThat(visitorSearch("genre", "SciFi", "cast", "Audrey")).isEmpty();
    }

    @Test
    @DisplayName("FR-SCR-38: the date range and a text filter combine with AND too")
    void fr38_theDateRangeAndsWithTheTextFilters() throws Exception {
        assertThat(visitorSearch("genre", "SciFi", "dateTo", "2026-06-11T23:59:59"))
                .containsExactly("Blade Runner 2049", "Alien");
    }

    @Test
    @DisplayName("FR-SCR-37: the date range is inclusive at both ends")
    void fr37_theDateRangeIsInclusiveAtBothEnds() throws Exception {
        assertThat(visitorSearch("dateFrom", "2026-06-09T18:00:00", "dateTo", "2026-06-09T18:00:00"))
                .containsExactly("Amelie");
    }

    // ==================================================================
    // FR-SCR-39 / FR-SCR-40 — every word, case-insensitive
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-39: every word entered must appear in the field, in any order and not necessarily adjacent")
    void fr39_everyWordEnteredMustAppear() throws Exception {
        assertThat(visitorSearch("title", "blade 2049")).containsExactly("Blade Runner 2049");
        assertThat(visitorSearch("title", "2049 blade")).containsExactly("Blade Runner 2049");
        assertThat(visitorSearch("cast", "harrison gosling")).containsExactly("Blade Runner 2049");
    }

    @Test
    @DisplayName("FR-SCR-39: one absent word excludes the screening, however well the others match")
    void fr39_oneAbsentWordExcludesTheScreening() throws Exception {
        assertThat(visitorSearch("title", "blade 2049 remastered")).isEmpty();
        assertThat(visitorSearch("cast", "gosling weaver")).isEmpty();
    }

    @Test
    @DisplayName("FR-SCR-40: the title, cast and genre filters are all case-insensitive")
    void fr40_everyTextFilterIsCaseInsensitive() throws Exception {
        assertThat(visitorSearch("title", "aMeLiE")).containsExactly("Amelie");
        assertThat(visitorSearch("cast", "SIGOURNEY weaver")).containsExactly("Alien");
        assertThat(visitorSearch("genre", "scifi,HORROR")).containsExactly("Alien");
    }

    @Test
    @DisplayName("FR-SCR-39/40: the genre filter matches inside the comma-separated genre list")
    void fr40_theGenreFilterMatchesInsideTheGenreList() throws Exception {
        assertThat(visitorSearch("genre", "drama")).containsExactly("Arrival", "Blade Runner 2049");
        assertThat(visitorSearch("genre", "romance")).containsExactly("Amelie");
    }

    // ==================================================================
    // FR-SCR-41 / FR-SCR-42 — empty search, and role filtering first
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-41: no filters returns every screening of the program the requester may see")
    void fr41_noFiltersReturnsEverythingTheRequesterMaySee() throws Exception {
        assertThat(visitorSearch()).containsExactly("Amelie", "Arrival", "Blade Runner 2049", "Alien");
        // The PROGRAMMER additionally sees the draft that is not public yet.
        assertThat(search(aliceToken)).hasSize(5).contains("Hidden Draft");
    }

    @Test
    @DisplayName("ROLE-02 / FR-SCR-42: a VISITOR never sees a screening that is not SCHEDULED")
    void role02_aVisitorNeverSeesAnUnscheduledScreening() throws Exception {
        assertThat(visitorSearch()).doesNotContain("Hidden Draft");
        assertThat(visitorSearch("title", "Hidden Draft")).isEmpty();
    }

    @Test
    @DisplayName("ROLE-02: a SCHEDULED screening inside a programme that is not ANNOUNCED yet is still not public")
    void role02_aScheduledScreeningOfAnUnannouncedProgramIsNotPublic() throws Exception {
        festival.setState(ProgramState.DECISION);
        programRepository.save(festival);

        assertThat(visitorSearch()).isEmpty();
        assertThat(search(aliceToken)).as("its PROGRAMMER still sees everything").hasSize(5);
    }

    @Test
    @DisplayName("ROLE-13 / FR-SCR-42: a SUBMITTER sees their own draft, and nobody else's")
    void role13_aSubmitterSeesOnlyTheirOwnDraft() throws Exception {
        assertThat(search(bobToken)).contains("Hidden Draft");
        assertThat(search(carolToken)).doesNotContain("Hidden Draft");
        // Erin submitted "Alien" but not the draft, so she does not see it either.
        assertThat(search(login("erin567", "UserPass5!"))).doesNotContain("Hidden Draft");
    }

    @Test
    @DisplayName("ROLE-11 / FR-SCR-42: outside their assigned screenings a STAFF member has VISITOR rights only")
    void role11_staffHasVisitorRightsOutsideTheirAssignedScreenings() throws Exception {
        assertThat(search(daveToken))
                .as("Dave handles only Blade Runner 2049; the draft stays invisible to him")
                .doesNotContain("Hidden Draft");
    }

    @Test
    @DisplayName("FR-SCR-42: an ADMIN is filtered and redacted exactly like a VISITOR")
    void fr42_anAdminIsFilteredLikeAVisitor() throws Exception {
        assertThat(search(adminToken)).containsExactly("Amelie", "Arrival", "Blade Runner 2049", "Alien");
    }

    // ==================================================================
    // FR-SCR-43 / FR-SCR-44 / FR-SCR-45 — the two orderings
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-43: non-timetable results are sorted primarily by genre")
    void fr43_nonTimetableResultsAreSortedByGenreFirst() throws Exception {
        // "Amelie" (Comedy,Romance) comes first even though "Alien" and "Arrival"
        // precede it alphabetically — so the genre, not the title, leads.
        assertThat(visitorSearch()).first().isEqualTo("Amelie");
        assertThat(visitorSearch()).last().isEqualTo("Alien");
    }

    @Test
    @DisplayName("FR-SCR-44: screenings sharing a genre are ordered by film title")
    void fr44_equalGenresAreBrokenByFilmTitle() throws Exception {
        assertThat(visitorSearch("genre", "SciFi,Drama"))
                .containsExactly("Arrival", "Blade Runner 2049");
    }

    @Test
    @DisplayName("FR-SCR-45: timetable mode sorts by start time instead")
    void fr45_timetableModeSortsByStartTime() throws Exception {
        assertThat(visitorSearch("timetable", "true"))
                .containsExactly("Amelie", "Blade Runner 2049", "Alien", "Arrival");
    }

    @Test
    @DisplayName("FR-SCR-45: the two orderings really are different, so neither is satisfied by accident")
    void fr45_theTwoOrderingsAreGenuinelyDifferent() throws Exception {
        assertThat(visitorSearch("timetable", "true")).isNotEqualTo(visitorSearch());
        assertThat(visitorSearch("timetable", "false")).isEqualTo(visitorSearch());
    }

    @Test
    @DisplayName("FR-SCR-42 + FR-SCR-43: role filtering happens BEFORE sorting, so a hidden screening shifts nothing")
    void fr42_roleFilteringHappensBeforeSorting() throws Exception {
        // "Hidden Draft" starts on 2026-06-08, earlier than everything else, so
        // in timetable order it would sort FIRST if it took part at all. A
        // visitor's first result must be unchanged by its existence...
        assertThat(visitorSearch("timetable", "true")).first().isEqualTo("Amelie");
        assertThat(visitorSearch()).first().isEqualTo("Amelie");

        // ... while for its own SUBMITTER, who may see it, it really does lead.
        assertThat(search(bobToken, "timetable", "true")).first().isEqualTo("Hidden Draft");
    }

    // ==================================================================
    // FR-SCR-46 — redaction of a single screening view
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-46: the VISITOR view of a screening carries only film title, genre, scheduled time and auditorium")
    void fr46_theVisitorViewCarriesOnlyTheAllowedFields() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/screenings/" + bladeRunner.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filmTitle").value("Blade Runner 2049"))
                .andExpect(jsonPath("$.filmGenres").value("SciFi,Drama"))
                .andExpect(jsonPath("$.startTime").value("2026-06-10T21:00:00"))
                .andExpect(jsonPath("$.auditoriumName").value("Main Hall"))
                // The migrated code leaked the lifecycle state here (INVENTORY.md).
                .andExpect(jsonPath("$.state").doesNotExist())
                .andExpect(jsonPath("$.filmCast").doesNotExist())
                .andExpect(jsonPath("$.filmDurationMinutes").doesNotExist())
                .andExpect(jsonPath("$.endTime").doesNotExist())
                .andExpect(jsonPath("$.reviewScore").doesNotExist())
                .andExpect(jsonPath("$.reviewComments").doesNotExist())
                .andExpect(jsonPath("$.approvalNotes").doesNotExist())
                .andExpect(jsonPath("$.rejectionReason").doesNotExist())
                .andExpect(jsonPath("$.submitterUsername").doesNotExist())
                .andExpect(jsonPath("$.handlerUsername").doesNotExist())
                .andExpect(jsonPath("$.finallySubmitted").doesNotExist())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.fieldNames()).toIterable()
                .as("FR-RED-04: nothing else, plus the two identifiers (ASSUMPTIONS #41)")
                .containsExactlyInAnyOrder("id", "programId", "filmTitle", "filmGenres", "startTime",
                        "auditoriumName");
    }

    @Test
    @DisplayName("FR-SCR-46: the PROGRAMMER of the program sees every screening in it in full")
    void fr46_theProgrammerSeesEveryScreeningInFull() throws Exception {
        mockMvc.perform(get("/api/screenings/" + bladeRunner.getId())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SCHEDULED"))
                .andExpect(jsonPath("$.filmCast").value("Ryan Gosling, Harrison Ford"))
                .andExpect(jsonPath("$.submitterUsername").value("bob2024"))
                .andExpect(jsonPath("$.handlerUsername").value("dave123"))
                .andExpect(jsonPath("$.reviewScore").value(8))
                .andExpect(jsonPath("$.finallySubmitted").value(false));
    }

    @Test
    @DisplayName("FR-SCR-46: the SUBMITTER sees their own screening in full and somebody else's redacted")
    void fr46_theSubmitterSeesOnlyTheirOwnInFull() throws Exception {
        mockMvc.perform(get("/api/screenings/" + amelie.getId())
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitterUsername").value("bob2024"))
                .andExpect(jsonPath("$.state").value("SCHEDULED"));

        // "Alien" was submitted by Erin, so for Bob it is just a public screening.
        mockMvc.perform(get("/api/screenings/" + alien.getId())
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitterUsername").doesNotExist())
                .andExpect(jsonPath("$.state").doesNotExist());
    }

    @Test
    @DisplayName("FR-SCR-46: the assigned STAFF handler sees their screening in full and the others redacted")
    void fr46_theHandlerSeesOnlyWhatTheyHandleInFull() throws Exception {
        mockMvc.perform(get("/api/screenings/" + bladeRunner.getId())
                        .header("Authorization", "Bearer " + daveToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handlerUsername").value("dave123"))
                .andExpect(jsonPath("$.reviewComments").value("A strong fit for the programme"));

        mockMvc.perform(get("/api/screenings/" + arrival.getId())
                        .header("Authorization", "Bearer " + daveToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewComments").doesNotExist())
                .andExpect(jsonPath("$.state").doesNotExist());
    }

    @Test
    @DisplayName("FR-SCR-46 / ROLE-02: viewing somebody else's draft is 404, not 403")
    void fr46_viewingSomebodyElsesDraftIs404() throws Exception {
        mockMvc.perform(get("/api/screenings/" + privateDraft.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SCREENING_NOT_FOUND"));

        mockMvc.perform(get("/api/screenings/" + privateDraft.getId())
                        .header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SCREENING_NOT_FOUND"));

        mockMvc.perform(get("/api/screenings/" + privateDraft.getId())
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filmTitle").value("Hidden Draft"));
    }

    @Test
    @DisplayName("FR-SCR-46: search results are redacted per requester exactly as the single view is")
    void fr46_searchResultsUseTheSameRedactionAsTheSingleView() throws Exception {
        MvcResult visitor = mockMvc.perform(get("/api/programs/" + festival.getId() + "/screenings")
                        .param("title", "Blade Runner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").doesNotExist())
                .andExpect(jsonPath("$[0].submitterUsername").doesNotExist())
                .andReturn();
        assertThat(visitor.getResponse().getContentAsString()).doesNotContain("dave123");

        mockMvc.perform(get("/api/programs/" + festival.getId() + "/screenings")
                        .param("title", "Blade Runner")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("SCHEDULED"))
                .andExpect(jsonPath("$[0].handlerUsername").value("dave123"));
    }
}
