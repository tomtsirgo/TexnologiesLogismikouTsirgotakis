package gr.aegean.cinema.crosscutting;

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
import gr.aegean.cinema.security.RateLimiter;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Rate limiting: NFR-06 (screening submission) and NFR-07 (search endpoints),
 * with the HTTP contract of ASSUMPTIONS.md #20 (429, {@code RATE_LIMITED}).
 *
 * <p>The class turns the limiter back ON explicitly, because the test profile
 * disables it by default (ASSUMPTIONS.md #42): the M2–M4 regression suites fire
 * far more than five submissions per minute under the fixed username
 * {@code bob2024} and are not testing this concern. The numbers used here are
 * the PRODUCTION ones from {@code application.properties} — 5 submissions and 30
 * searches per minute — so what is proven is the shipped configuration, not a
 * convenient test-only one. {@link RateLimiter#reset()} runs before each test so
 * that one test never spends another's allowance.
 *
 * <p>The refill behaviour is proven separately, and instantly, against a
 * {@link RateLimiter} built with an injected clock: a test that waited for a
 * real minute to pass would be both slow and flaky.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.submission-per-minute=5",
        "app.rate-limit.search-per-minute=30"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitingTest {

    private static final int SUBMISSION_LIMIT = 5;
    private static final int SEARCH_LIMIT = 30;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthTokenRepository authTokenRepository;
    @Autowired private ProgramRepository programRepository;
    @Autowired private ProgramRoleRepository programRoleRepository;
    @Autowired private ScreeningRepository screeningRepository;
    @Autowired private PasswordUtil passwordUtil;
    @Autowired private RateLimiter rateLimiter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User alice;   // PROGRAMMER of the program
    private User bob;     // SUBMITTER
    private User carol;   // a second SUBMITTER, to prove the buckets are per user

    private String bobToken;
    private String carolToken;

    private Program program;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.reset();

        authTokenRepository.deleteAll();
        screeningRepository.deleteAll();
        programRoleRepository.deleteAll();
        programRepository.deleteAll();
        userRepository.deleteAll();

        alice = persist("alice01", "UserPass1!", PermanentRole.USER);
        bob = persist("bob2024", "UserPass2!", PermanentRole.USER);
        carol = persist("carol_x", "UserPass3!", PermanentRole.USER);

        bobToken = login("bob2024", "UserPass2!");
        carolToken = login("carol_x", "UserPass3!");

        program = programRepository.save(Program.builder()
                .name("Summer Fest").description("A summer season")
                .startDate(LocalDate.of(2026, 6, 1)).endDate(LocalDate.of(2026, 6, 30))
                .state(ProgramState.SUBMISSION).creator(alice).build());
        programRoleRepository.save(ProgramRole.builder()
                .user(alice).program(program).role(ProgramRoleType.PROGRAMMER).build());

        // Logging in costs nothing: only submission and search are limited.
        rateLimiter.reset();
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

    /** A complete, submittable screening owned by {@code submitter}. */
    private long completeScreening(User submitter, String title) {
        LocalDateTime start = LocalDateTime.of(2026, 6, 10, 20, 0);
        return screeningRepository.save(Screening.builder()
                .program(program).submitter(submitter).state(ScreeningState.CREATED)
                .filmTitle(title).filmCast("A Cast").filmGenres("Drama").filmDurationMinutes(120)
                .auditoriumName("Main Hall").startTime(start).endTime(start.plusHours(3))
                .build()).getId();
    }

    private List<Long> completeScreenings(User submitter, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(completeScreening(submitter, "Film " + i));
        }
        return ids;
    }

    // ==================================================================
    // NFR-06 — screening submission, 5 per minute per user
    // ==================================================================

    @Test
    @DisplayName("NFR-06: the first 5 submissions in a minute succeed and the 6th is refused with 429 RATE_LIMITED")
    void nfr06_theSixthSubmissionInAMinuteIsRefused() throws Exception {
        List<Long> ids = completeScreenings(bob, SUBMISSION_LIMIT + 1);

        for (int i = 0; i < SUBMISSION_LIMIT; i++) {
            mockMvc.perform(post("/api/screenings/" + ids.get(i) + "/submit")
                            .header("Authorization", "Bearer " + bobToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("SUBMITTED"));
        }

        mockMvc.perform(post("/api/screenings/" + ids.get(SUBMISSION_LIMIT) + "/submit")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"));

        assertThat(screeningRepository.findById(ids.get(SUBMISSION_LIMIT)).orElseThrow().getState())
                .as("the refused request must not have executed the side effect")
                .isEqualTo(ScreeningState.CREATED);
    }

    @Test
    @DisplayName("NFR-06: the submission bucket is per user, so one caller cannot exhaust another's allowance")
    void nfr06_theSubmissionBucketIsPerUser() throws Exception {
        List<Long> bobs = completeScreenings(bob, SUBMISSION_LIMIT + 1);
        long carols = completeScreening(carol, "Carol's Film");

        for (int i = 0; i < SUBMISSION_LIMIT; i++) {
            mockMvc.perform(post("/api/screenings/" + bobs.get(i) + "/submit")
                            .header("Authorization", "Bearer " + bobToken))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/screenings/" + bobs.get(SUBMISSION_LIMIT) + "/submit")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isTooManyRequests());

        // Carol has spent nothing.
        mockMvc.perform(post("/api/screenings/" + carols + "/submit")
                        .header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("NFR-06: a refused-by-business-rule submission still costs a token — the limiter counts requests")
    void nfr06_theLimiterCountsRequestsNotSuccesses() throws Exception {
        long id = completeScreening(bob, "The Only Film");

        mockMvc.perform(post("/api/screenings/" + id + "/submit")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());
        // Four more attempts on the same, now-SUBMITTED screening: each is a 409 ...
        for (int i = 0; i < SUBMISSION_LIMIT - 1; i++) {
            mockMvc.perform(post("/api/screenings/" + id + "/submit")
                            .header("Authorization", "Bearer " + bobToken))
                    .andExpect(status().isConflict());
        }
        // ... and the allowance is spent all the same.
        mockMvc.perform(post("/api/screenings/" + id + "/submit")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("NFR-06: the rate limit applies to submission only, not to the neighbouring screening endpoints")
    void nfr06_onlyTheSubmissionEndpointIsLimited() throws Exception {
        List<Long> ids = completeScreenings(bob, SUBMISSION_LIMIT + 1);
        for (int i = 0; i < SUBMISSION_LIMIT; i++) {
            mockMvc.perform(post("/api/screenings/" + ids.get(i) + "/submit")
                            .header("Authorization", "Bearer " + bobToken))
                    .andExpect(status().isOk());
        }

        // The bucket is exhausted for submission ...
        mockMvc.perform(post("/api/screenings/" + ids.get(SUBMISSION_LIMIT) + "/submit")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isTooManyRequests());

        // ... but a view of one's own screening is untouched.
        mockMvc.perform(get("/api/screenings/" + ids.get(SUBMISSION_LIMIT))
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());
    }

    // ==================================================================
    // NFR-07 — search endpoints, 30 per minute per caller
    // ==================================================================

    @Test
    @DisplayName("NFR-07: the first 30 program searches in a minute succeed and the 31st is refused with 429")
    void nfr07_theThirtyFirstProgramSearchIsRefused() throws Exception {
        for (int i = 0; i < SEARCH_LIMIT; i++) {
            mockMvc.perform(get("/api/programs").header("Authorization", "Bearer " + bobToken))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/programs").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("NFR-07: program search and screening search share ONE search bucket per caller")
    void nfr07_bothSearchEndpointsShareOneBucket() throws Exception {
        for (int i = 0; i < SEARCH_LIMIT / 2; i++) {
            mockMvc.perform(get("/api/programs").header("Authorization", "Bearer " + bobToken))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/programs/" + program.getId() + "/screenings")
                            .header("Authorization", "Bearer " + bobToken))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/programs/" + program.getId() + "/screenings")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("NFR-07: an anonymous VISITOR is limited too, by client address (ASSUMPTIONS #4)")
    void nfr07_anAnonymousVisitorIsLimitedByAddress() throws Exception {
        for (int i = 0; i < SEARCH_LIMIT; i++) {
            mockMvc.perform(get("/api/programs")).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/programs"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"));

        // An authenticated caller from the same address has their own bucket.
        mockMvc.perform(get("/api/programs").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("NFR-07: viewing one program by id is not a search and is never throttled")
    void nfr07_aViewByIdIsNotASearch() throws Exception {
        for (int i = 0; i < SEARCH_LIMIT; i++) {
            mockMvc.perform(get("/api/programs").header("Authorization", "Bearer " + bobToken))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/programs").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isTooManyRequests());

        // Bob is not a PROGRAMMER of this CREATED-state programme, so the view is
        // a 404 by redaction — the point is that it is NOT a 429.
        mockMvc.perform(get("/api/programs/" + program.getId())
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    // ==================================================================
    // ASSUMPTIONS #20 — the HTTP contract of a throttled request
    // ==================================================================

    @Test
    @DisplayName("ASSUMPTIONS #20: a throttled request returns the standard error envelope with status 429")
    void assumption20_theErrorEnvelopeIsTheStandardOne() throws Exception {
        for (int i = 0; i < SEARCH_LIMIT; i++) {
            mockMvc.perform(get("/api/programs").header("Authorization", "Bearer " + bobToken))
                    .andExpect(status().isOk());
        }

        MvcResult result = mockMvc.perform(get("/api/programs").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("timestamp").asText()).isNotBlank();
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("errorCode").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("message").asText()).contains("30");
        assertThat(result.getResponse().getContentAsString())
                .as("NFR-03: no stack trace ever reaches the client")
                .doesNotContain("java.lang").doesNotContain("at gr.aegean");
    }

    // ==================================================================
    // The token bucket itself — refill, isolation, the off switch
    // ==================================================================

    @Test
    @DisplayName("NFR-06/07: a bucket admits exactly its capacity in a burst, then refuses")
    void theBucketAdmitsExactlyItsCapacityInABurst() {
        RateLimiter limiter = new RateLimiter(true, 3, 10, () -> 0L);

        assertThat(limiter.tryConsume(RateLimiter.Bucket.SUBMISSION, "user:bob2024")).isTrue();
        assertThat(limiter.tryConsume(RateLimiter.Bucket.SUBMISSION, "user:bob2024")).isTrue();
        assertThat(limiter.tryConsume(RateLimiter.Bucket.SUBMISSION, "user:bob2024")).isTrue();
        assertThat(limiter.tryConsume(RateLimiter.Bucket.SUBMISSION, "user:bob2024")).isFalse();
    }

    @Test
    @DisplayName("NFR-06/07: the bucket refills continuously, one token per capacity-th of a minute")
    void theBucketRefillsContinuously() {
        AtomicLong clock = new AtomicLong(0L);
        RateLimiter limiter = new RateLimiter(true, 6, 6, clock::get);

        for (int i = 0; i < 6; i++) {
            assertThat(limiter.tryConsume(RateLimiter.Bucket.SUBMISSION, "user:bob2024")).isTrue();
        }
        assertThat(limiter.tryConsume(RateLimiter.Bucket.SUBMISSION, "user:bob2024")).isFalse();

        // 6 per minute means one token every 10 seconds. Nine seconds is not enough.
        clock.set(9L * 1_000_000_000L);
        assertThat(limiter.tryConsume(RateLimiter.Bucket.SUBMISSION, "user:bob2024")).isFalse();

        clock.set(11L * 1_000_000_000L);
        assertThat(limiter.tryConsume(RateLimiter.Bucket.SUBMISSION, "user:bob2024")).isTrue();
        assertThat(limiter.tryConsume(RateLimiter.Bucket.SUBMISSION, "user:bob2024")).isFalse();

        // A long idle period refills the bucket but never beyond its capacity.
        clock.set(3600L * 1_000_000_000L);
        for (int i = 0; i < 6; i++) {
            assertThat(limiter.tryConsume(RateLimiter.Bucket.SUBMISSION, "user:bob2024")).isTrue();
        }
        assertThat(limiter.tryConsume(RateLimiter.Bucket.SUBMISSION, "user:bob2024")).isFalse();
    }

    @Test
    @DisplayName("NFR-06/07: buckets are independent per endpoint group and per caller")
    void bucketsAreIndependentPerGroupAndPerCaller() {
        RateLimiter limiter = new RateLimiter(true, 1, 1, () -> 0L);

        assertThat(limiter.tryConsume(RateLimiter.Bucket.SUBMISSION, "user:bob2024")).isTrue();
        assertThat(limiter.tryConsume(RateLimiter.Bucket.SUBMISSION, "user:bob2024")).isFalse();

        assertThat(limiter.tryConsume(RateLimiter.Bucket.SEARCH, "user:bob2024"))
                .as("a different endpoint group").isTrue();
        assertThat(limiter.tryConsume(RateLimiter.Bucket.SUBMISSION, "user:carol_x"))
                .as("a different caller").isTrue();
        assertThat(limiter.tryConsume(RateLimiter.Bucket.SUBMISSION, "ip:127.0.0.1"))
                .as("an anonymous caller").isTrue();
    }

    @Test
    @DisplayName("The limiter can be switched off entirely, which is what the test profile does (ASSUMPTIONS #42)")
    void theLimiterCanBeSwitchedOff() {
        RateLimiter disabled = new RateLimiter(false, 1, 1, () -> 0L);
        for (int i = 0; i < 100; i++) {
            assertThat(disabled.tryConsume(RateLimiter.Bucket.SUBMISSION, "user:bob2024")).isTrue();
        }
        assertThat(disabled.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("The configured capacities are the documented ones: 5 submissions, 30 searches per minute")
    void theConfiguredCapacitiesAreTheDocumentedOnes() {
        assertThat(rateLimiter.isEnabled()).isTrue();
        assertThat(rateLimiter.capacityOf(RateLimiter.Bucket.SUBMISSION)).isEqualTo(SUBMISSION_LIMIT);
        assertThat(rateLimiter.capacityOf(RateLimiter.Bucket.SEARCH)).isEqualTo(SEARCH_LIMIT);
    }
}
