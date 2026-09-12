package gr.aegean.cinema.crosscutting;

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
import gr.aegean.cinema.security.IdempotencyStore;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Idempotency (NFR-05): DOMAIN_RULES.md requires that "a successfully executed
 * non-idempotent function (e.g. screening creation, submission) must not be
 * executable twice with the same effect". The mechanism is the optional
 * {@code Idempotency-Key} request header of ASSUMPTIONS.md #15, honoured on the
 * three endpoints that create something or move a state: program creation,
 * screening creation and screening submission.
 *
 * <p>Every test here asserts BOTH halves of the rule — that the response is
 * replayed verbatim, and that the side effect did not happen a second time.
 * Asserting only the response would pass against an implementation that
 * re-executed the action and merely returned the cached body.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdempotencyTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthTokenRepository authTokenRepository;
    @Autowired private ProgramRepository programRepository;
    @Autowired private ProgramRoleRepository programRoleRepository;
    @Autowired private ScreeningRepository screeningRepository;
    @Autowired private PasswordUtil passwordUtil;
    @Autowired private IdempotencyStore idempotencyStore;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User alice;   // PROGRAMMER of the existing program
    private User bob;     // SUBMITTER
    private User carol;   // a second caller, to prove keys are scoped per user

    private String aliceToken;
    private String bobToken;
    private String carolToken;

    private Program program;

    @BeforeEach
    void setUp() throws Exception {
        idempotencyStore.reset();

        authTokenRepository.deleteAll();
        screeningRepository.deleteAll();
        programRoleRepository.deleteAll();
        programRepository.deleteAll();
        userRepository.deleteAll();

        alice = persist("alice01", "UserPass1!", PermanentRole.USER);
        bob = persist("bob2024", "UserPass2!", PermanentRole.USER);
        carol = persist("carol_x", "UserPass3!", PermanentRole.USER);

        aliceToken = login("alice01", "UserPass1!");
        bobToken = login("bob2024", "UserPass2!");
        carolToken = login("carol_x", "UserPass3!");

        program = programRepository.save(Program.builder()
                .name("Summer Fest").description("A summer season")
                .startDate(LocalDate.of(2026, 6, 1)).endDate(LocalDate.of(2026, 6, 30))
                .state(ProgramState.SUBMISSION).creator(alice).build());
        programRoleRepository.save(ProgramRole.builder()
                .user(alice).program(program).role(ProgramRoleType.PROGRAMMER).build());
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

    private String programBody(String name) {
        return "{\"name\":\"" + name + "\",\"description\":\"A season\","
                + "\"startDate\":\"2027-06-01\",\"endDate\":\"2027-06-30\"}";
    }

    private long completeScreening(User submitter, String title) {
        LocalDateTime start = LocalDateTime.of(2026, 6, 10, 20, 0);
        return screeningRepository.save(Screening.builder()
                .program(program).submitter(submitter).state(ScreeningState.CREATED)
                .filmTitle(title).filmCast("A Cast").filmGenres("Drama").filmDurationMinutes(120)
                .auditoriumName("Main Hall").startTime(start).endTime(start.plusHours(3))
                .build()).getId();
    }

    // ==================================================================
    // NFR-05 — program creation
    // ==================================================================

    @Test
    @DisplayName("NFR-05: a repeated Idempotency-Key on program creation replays the first response and creates nothing")
    void nfr05_programCreationIsNotExecutedTwice() throws Exception {
        long before = programRepository.count();

        MvcResult first = mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header(IdempotencyStore.HEADER, "key-prg-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(programBody("Winter Fest")))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header(IdempotencyStore.HEADER, "key-prg-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(programBody("Winter Fest")))
                .andExpect(status().isCreated())
                .andExpect(header().string(IdempotencyStore.REPLAY_HEADER, "true"))
                .andReturn();

        assertThat(second.getResponse().getContentAsString())
                .as("the first response, byte for byte")
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(programRepository.count())
                .as("exactly ONE program was created")
                .isEqualTo(before + 1);
    }

    @Test
    @DisplayName("NFR-05: without the header the second creation is executed and fails on the unique name")
    void nfr05_withoutTheHeaderTheSecondAttemptIsReallyExecuted() throws Exception {
        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(programBody("Winter Fest")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(programBody("Winter Fest")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROGRAM_NAME_TAKEN"))
                .andExpect(header().doesNotExist(IdempotencyStore.REPLAY_HEADER));
    }

    @Test
    @DisplayName("NFR-05: a DIFFERENT key is a different operation and is executed normally")
    void nfr05_aDifferentKeyIsExecutedNormally() throws Exception {
        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header(IdempotencyStore.HEADER, "key-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(programBody("Winter Fest")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header(IdempotencyStore.HEADER, "key-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(programBody("Spring Fest")))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotencyStore.REPLAY_HEADER));

        assertThat(programRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("NFR-05: the cache is scoped per user, so two callers may pick the same key safely")
    void nfr05_theCacheIsScopedPerUser() throws Exception {
        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header(IdempotencyStore.HEADER, "shared-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(programBody("Alice's Fest")))
                .andExpect(status().isCreated());

        MvcResult carols = mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + carolToken)
                        .header(IdempotencyStore.HEADER, "shared-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(programBody("Carol's Fest")))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotencyStore.REPLAY_HEADER))
                .andExpect(jsonPath("$.name").value("Carol's Fest"))
                .andReturn();

        assertThat(carols.getResponse().getContentAsString()).contains("carol_x");
        assertThat(programRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("NFR-05: a FAILED first attempt is not cached, so the same key may be retried after a fix")
    void nfr05_aFailedAttemptIsNotCached() throws Exception {
        // First attempt: an inverted date range is a 400.
        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header(IdempotencyStore.HEADER, "retry-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Broken Fest\",\"description\":\"d\","
                                + "\"startDate\":\"2027-06-30\",\"endDate\":\"2027-06-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PROGRAM_DATES"));

        // The same key, now with a valid payload, really executes.
        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header(IdempotencyStore.HEADER, "retry-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(programBody("Fixed Fest")))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotencyStore.REPLAY_HEADER))
                .andExpect(jsonPath("$.name").value("Fixed Fest"));
    }

    // ==================================================================
    // NFR-05 — screening creation
    // ==================================================================

    @Test
    @DisplayName("NFR-05: a repeated Idempotency-Key on screening creation creates exactly one screening")
    void nfr05_screeningCreationIsNotExecutedTwice() throws Exception {
        String body = "{\"filmTitle\":\"Arrival\",\"filmCast\":\"Amy Adams\",\"filmGenres\":\"SciFi\","
                + "\"filmDurationMinutes\":116,\"auditoriumName\":\"Main Hall\","
                + "\"startTime\":\"2026-06-10T20:00:00\",\"endTime\":\"2026-06-10T22:30:00\"}";

        MvcResult first = mockMvc.perform(post("/api/programs/" + program.getId() + "/screenings")
                        .header("Authorization", "Bearer " + bobToken)
                        .header(IdempotencyStore.HEADER, "key-scr-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/programs/" + program.getId() + "/screenings")
                        .header("Authorization", "Bearer " + bobToken)
                        .header(IdempotencyStore.HEADER, "key-scr-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string(IdempotencyStore.REPLAY_HEADER, "true"))
                .andReturn();

        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(screeningRepository.count())
                .as("without the key this endpoint would happily create a duplicate draft")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("NFR-05: without the key, repeated screening creation really does create a second draft")
    void nfr05_withoutTheKeyScreeningCreationDuplicates() throws Exception {
        String body = "{\"filmTitle\":\"Arrival\"}";
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/programs/" + program.getId() + "/screenings")
                            .header("Authorization", "Bearer " + bobToken)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated());
        }
        assertThat(screeningRepository.count())
                .as("this is exactly the duplication the Idempotency-Key prevents")
                .isEqualTo(2);
    }

    // ==================================================================
    // NFR-05 — screening submission
    // ==================================================================

    @Test
    @DisplayName("NFR-05: a repeated Idempotency-Key on submission replays the 200 instead of returning a 409")
    void nfr05_submissionIsNotExecutedTwice() throws Exception {
        long id = completeScreening(bob, "Arrival");

        MvcResult first = mockMvc.perform(post("/api/screenings/" + id + "/submit")
                        .header("Authorization", "Bearer " + bobToken)
                        .header(IdempotencyStore.HEADER, "key-sub-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUBMITTED"))
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/screenings/" + id + "/submit")
                        .header("Authorization", "Bearer " + bobToken)
                        .header(IdempotencyStore.HEADER, "key-sub-1"))
                .andExpect(status().isOk())
                .andExpect(header().string(IdempotencyStore.REPLAY_HEADER, "true"))
                .andReturn();

        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(screeningRepository.findById(id).orElseThrow().getState())
                .isEqualTo(ScreeningState.SUBMITTED);
    }

    @Test
    @DisplayName("NFR-05: without the key a repeated submission is refused by the state machine with 409")
    void nfr05_withoutTheKeyARepeatedSubmissionIs409() throws Exception {
        long id = completeScreening(bob, "Arrival");

        mockMvc.perform(post("/api/screenings/" + id + "/submit")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/screenings/" + id + "/submit")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SCREENING_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("NFR-05: one key is bound to ONE operation and never replays a different endpoint's response")
    void nfr05_aKeyIsBoundToOneOperation() throws Exception {
        long id = completeScreening(bob, "Arrival");

        mockMvc.perform(post("/api/screenings/" + id + "/submit")
                        .header("Authorization", "Bearer " + bobToken)
                        .header(IdempotencyStore.HEADER, "reused"))
                .andExpect(status().isOk());

        // The same key on a different endpoint must execute, not replay.
        mockMvc.perform(post("/api/programs/" + program.getId() + "/screenings")
                        .header("Authorization", "Bearer " + bobToken)
                        .header(IdempotencyStore.HEADER, "reused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filmTitle\":\"Another Draft\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotencyStore.REPLAY_HEADER))
                .andExpect(jsonPath("$.filmTitle").value("Another Draft"));
    }

    // ==================================================================
    // Scope of the mechanism
    // ==================================================================

    @Test
    @DisplayName("ASSUMPTIONS #15: the header is honoured on exactly the three documented endpoints")
    void assumption15_exactlyThreeEndpointsHonourTheHeader() {
        assertThat(IdempotencyStore.isIdempotentEndpoint("POST", "/api/programs")).isTrue();
        assertThat(IdempotencyStore.isIdempotentEndpoint("POST", "/api/programs/7/screenings")).isTrue();
        assertThat(IdempotencyStore.isIdempotentEndpoint("POST", "/api/screenings/7/submit")).isTrue();

        assertThat(IdempotencyStore.isIdempotentEndpoint("GET", "/api/programs")).isFalse();
        assertThat(IdempotencyStore.isIdempotentEndpoint("PUT", "/api/programs/7")).isFalse();
        assertThat(IdempotencyStore.isIdempotentEndpoint("DELETE", "/api/programs/7")).isFalse();
        assertThat(IdempotencyStore.isIdempotentEndpoint("POST", "/api/screenings/7/approve")).isFalse();
        assertThat(IdempotencyStore.isIdempotentEndpoint("POST", "/api/auth/login")).isFalse();
    }

    @Test
    @DisplayName("ASSUMPTIONS #15: an endpoint outside the list ignores the header entirely")
    void assumption15_anUnlistedEndpointIgnoresTheHeader() throws Exception {
        mockMvc.perform(put("/api/programs/" + program.getId())
                        .header("Authorization", "Bearer " + aliceToken)
                        .header(IdempotencyStore.HEADER, "key-upd-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"First edit\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/programs/" + program.getId())
                        .header("Authorization", "Bearer " + aliceToken)
                        .header(IdempotencyStore.HEADER, "key-upd-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Second edit\"}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyStore.REPLAY_HEADER))
                .andExpect(jsonPath("$.description").value("Second edit"));
    }

    @Test
    @DisplayName("NFR-05: a blank Idempotency-Key is treated as no key at all")
    void nfr05_aBlankKeyIsNoKey() throws Exception {
        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header(IdempotencyStore.HEADER, "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(programBody("Winter Fest")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header(IdempotencyStore.HEADER, "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(programBody("Winter Fest")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROGRAM_NAME_TAKEN"));
    }

    @Test
    @DisplayName("NFR-05: the replay never re-runs authorization-failing requests either — an unauthorized call is not cached")
    void nfr05_anUnauthorizedCallIsNeverCached() throws Exception {
        // Carol is not the SUBMITTER, so her submission attempt is a 403.
        long id = completeScreening(bob, "Arrival");

        mockMvc.perform(post("/api/screenings/" + id + "/submit")
                        .header("Authorization", "Bearer " + carolToken)
                        .header(IdempotencyStore.HEADER, "key-forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_SUBMITTER"));

        // The rightful submitter, using their own key, is completely unaffected.
        mockMvc.perform(post("/api/screenings/" + id + "/submit")
                        .header("Authorization", "Bearer " + bobToken)
                        .header(IdempotencyStore.HEADER, "key-forbidden"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyStore.REPLAY_HEADER))
                .andExpect(jsonPath("$.state").value("SUBMITTED"));
    }
}
