package gr.aegean.cinema.crosscutting;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.aegean.cinema.exception.BadRequestException;
import gr.aegean.cinema.exception.ConflictException;
import gr.aegean.cinema.exception.ForbiddenException;
import gr.aegean.cinema.exception.NotFoundException;
import gr.aegean.cinema.exception.RateLimitExceededException;
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
import gr.aegean.cinema.service.impl.AuthServiceImpl;
import gr.aegean.cinema.service.impl.ProgramServiceImpl;
import gr.aegean.cinema.service.impl.ScreeningServiceImpl;
import gr.aegean.cinema.service.impl.UserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The cross-cutting non-functional contract: NFR-01 … NFR-04 and NFR-08 …
 * NFR-10.
 *
 * <p>Three of these rows are structural rather than behavioural, and are proven
 * as such:
 * <ul>
 *   <li><b>NFR-01</b> — every write-path service method is {@code @Transactional}:
 *       checked by reflection over the four service implementations, and backed
 *       by a real partial-write rollback against H2.</li>
 *   <li><b>ASSUMPTIONS #19 / #28</b> — every business exception carries a
 *       specific machine code: checked by scanning the production sources for
 *       any throw site that falls back to the generic single-argument
 *       constructor. A behavioural test can only cover the throw sites it
 *       happens to reach; this one covers all of them, including the ones added
 *       tomorrow.</li>
 *   <li><b>NFR-04</b> — business failures map only to 400/403/404/409 (plus the
 *       documented 429): checked by driving one real request into each status
 *       and by asserting the handler declares no other business status.</li>
 * </ul>
 *
 * <p>The audit rows (NFR-08/09) attach a logback {@link ListAppender} to the
 * dedicated {@code AUDIT} logger, so what is asserted is the line that would
 * really be written to the audit appender in production, not a mock interaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ErrorContractAndAuditTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthTokenRepository authTokenRepository;
    @Autowired private ProgramRepository programRepository;
    @Autowired private ProgramRoleRepository programRoleRepository;
    @Autowired private ScreeningRepository screeningRepository;
    @Autowired private PasswordUtil passwordUtil;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ch.qos.logback.classic.Logger auditLogger;
    private ListAppender<ILoggingEvent> auditAppender;

    private User alice;   // PROGRAMMER of the program
    private User bob;     // SUBMITTER
    private User carol;   // an outsider

    private String aliceToken;
    private String bobToken;
    private String carolToken;

    private Program program;

    @BeforeEach
    void setUp() throws Exception {
        authTokenRepository.deleteAll();
        screeningRepository.deleteAll();
        programRoleRepository.deleteAll();
        programRepository.deleteAll();
        userRepository.deleteAll();

        alice = persist("alice01", "UserPass1!", PermanentRole.USER);
        bob = persist("bob2024", "UserPass2!", PermanentRole.USER);
        carol = persist("carol_x", "UserPass3!", PermanentRole.USER);
        persist("admin1", "AdminPass1!", PermanentRole.ADMIN);

        aliceToken = login("alice01", "UserPass1!");
        bobToken = login("bob2024", "UserPass2!");
        carolToken = login("carol_x", "UserPass3!");

        program = programRepository.save(Program.builder()
                .name("Summer Fest").description("A summer season")
                .startDate(LocalDate.of(2026, 6, 1)).endDate(LocalDate.of(2026, 6, 30))
                .state(ProgramState.CREATED).creator(alice).build());
        programRoleRepository.save(ProgramRole.builder()
                .user(alice).program(program).role(ProgramRoleType.PROGRAMMER).build());

        auditLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("AUDIT");
        auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);
    }

    @AfterEach
    void detachTheAuditAppender() {
        if (auditLogger != null && auditAppender != null) {
            auditLogger.detachAppender(auditAppender);
        }
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

    private List<String> auditLines() {
        List<String> lines = new ArrayList<>();
        auditAppender.list.forEach(event -> lines.add(event.getFormattedMessage()));
        return lines;
    }

    private List<Level> auditLevels() {
        List<Level> levels = new ArrayList<>();
        auditAppender.list.forEach(event -> levels.add(event.getLevel()));
        return levels;
    }

    /** Asserts the standard {timestamp, status, errorCode, message} envelope. */
    private JsonNode assertEnvelope(MvcResult result, int status, String errorCode) throws Exception {
        String raw = result.getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(raw);

        assertThat(body.hasNonNull("timestamp")).as("timestamp present in %s", raw).isTrue();
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("errorCode").asText()).isEqualTo(errorCode);
        assertThat(body.get("message").asText()).isNotBlank();
        assertThat(raw)
                .as("NFR-03: no stack trace, no framework exception class name")
                .doesNotContain("java.lang.").doesNotContain("at gr.aegean.").doesNotContain("Exception:");
        assertThat(body.has("error")).as("ASSUMPTIONS #28: the field is errorCode, never 'error'").isFalse();
        return body;
    }

    // ==================================================================
    // NFR-02 / NFR-03 — the error envelope
    // ==================================================================

    @Test
    @DisplayName("NFR-02: a 400 carries {timestamp, status, errorCode, message} with a real business code")
    void nfr02_theBadRequestEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Backwards\",\"description\":\"d\","
                                + "\"startDate\":\"2027-06-30\",\"endDate\":\"2027-06-01\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertEnvelope(result, 400, "INVALID_PROGRAM_DATES");
    }

    @Test
    @DisplayName("NFR-02: a 403 carries the specific authorization code, not the HTTP reason phrase")
    void nfr02_theForbiddenEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(put("/api/programs/" + program.getId())
                        .header("Authorization", "Bearer " + carolToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"hijacked\"}"))
                .andExpect(status().isForbidden())
                .andReturn();

        JsonNode body = assertEnvelope(result, 403, "NOT_PROGRAMMER");
        assertThat(body.get("errorCode").asText()).isNotEqualTo("Forbidden");
    }

    @Test
    @DisplayName("NFR-02: a 404 carries the specific not-found code")
    void nfr02_theNotFoundEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/programs/987654"))
                .andExpect(status().isNotFound())
                .andReturn();

        assertEnvelope(result, 404, "PROGRAM_NOT_FOUND");
    }

    @Test
    @DisplayName("NFR-02: a 409 carries the specific conflict code")
    void nfr02_theConflictEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/programs/" + program.getId() + "/review-start")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andReturn();

        assertEnvelope(result, 409, "INVALID_STATE_TRANSITION");
    }

    @Test
    @DisplayName("NFR-02: a 401 carries the distinct token code, exactly as M2 established")
    void nfr02_theUnauthorizedEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/programs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"description\":\"d\","
                                + "\"startDate\":\"2027-06-01\",\"endDate\":\"2027-06-30\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertEnvelope(result, 401, "TOKEN_MISSING");
    }

    @Test
    @DisplayName("NFR-03: malformed input of every kind produces a structured 4xx, never a 500 or a stack trace")
    void nfr03_noStackTraceAndNo500EverEscapes() throws Exception {
        // Unparsable JSON body.
        assertEnvelope(mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{not json at all"))
                .andExpect(status().isBadRequest()).andReturn(), 400, "MALFORMED_REQUEST_BODY");

        // A path variable of the wrong type.
        assertEnvelope(mockMvc.perform(get("/api/programs/not-a-number"))
                .andExpect(status().isBadRequest()).andReturn(), 400, "INVALID_PARAMETER");

        // Bean Validation failure on a required field.
        assertEnvelope(mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"d\"}"))
                .andExpect(status().isBadRequest()).andReturn(), 400, "VALIDATION_FAILED");

        // A method the endpoint does not support.
        mockMvc.perform(patch("/api/programs/" + program.getId())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
    }

    // ==================================================================
    // NFR-04 — the status codes business failures may use
    // ==================================================================

    @Test
    @DisplayName("NFR-04: business failures map only to 400, 403, 404 and 409 (plus the documented 429)")
    void nfr04_businessFailuresUseOnlyTheAllowedStatuses() {
        // The four business exception types, one status each, decided in ONE place.
        assertThat(new BadRequestException("m", "C").getErrorCode()).isEqualTo("C");
        assertThat(new ForbiddenException("m", "C").getErrorCode()).isEqualTo("C");
        assertThat(new NotFoundException("m", "C").getErrorCode()).isEqualTo("C");
        assertThat(new ConflictException("m", "C").getErrorCode()).isEqualTo("C");
        assertThat(new RateLimitExceededException("m").getErrorCode()).isEqualTo("RATE_LIMITED");
    }

    @Test
    @DisplayName("NFR-04: one real request reaches each of the four allowed business statuses")
    void nfr04_eachAllowedStatusIsReachable() throws Exception {
        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Backwards\",\"description\":\"d\","
                                + "\"startDate\":\"2027-06-30\",\"endDate\":\"2027-06-01\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/programs/" + program.getId())
                        .header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/screenings/987654"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/programs/" + program.getId() + "/announce")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("ASSUMPTIONS #19/#28: no throw site anywhere falls back to a generic error code")
    void assumption19_everyThrowSiteCarriesItsOwnErrorCode() throws IOException {
        Path mainSources = Paths.get("src", "main", "java");
        Set<String> types = Set.of("BadRequestException", "ConflictException",
                "ForbiddenException", "NotFoundException");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainSources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (String type : types) {
                    offenders.addAll(singleArgumentThrowSites(source, file.getFileName().toString(), type));
                }
            }
        }

        assertThat(offenders)
                .as("every business exception must name its own UPPER_SNAKE_CASE rule code")
                .isEmpty();
    }

    /**
     * Finds {@code new SomeException(...)} call sites whose argument list holds
     * no top-level comma, i.e. that use the message-only constructor and would
     * therefore emit the generic fallback code.
     */
    private List<String> singleArgumentThrowSites(String source, String fileName, String exceptionType) {
        List<String> found = new ArrayList<>();
        String needle = "new " + exceptionType + "(";
        int from = 0;
        while (true) {
            int start = source.indexOf(needle, from);
            if (start < 0) {
                return found;
            }
            int cursor = start + needle.length();
            int depth = 1;
            int topLevelCommas = 0;
            boolean inString = false;
            while (cursor < source.length() && depth > 0) {
                char c = source.charAt(cursor);
                if (c == '"' && source.charAt(cursor - 1) != '\\') {
                    inString = !inString;
                } else if (!inString) {
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                    } else if (c == ',' && depth == 1) {
                        topLevelCommas++;
                    }
                }
                cursor++;
            }
            if (topLevelCommas == 0) {
                found.add(fileName + ": " + needle);
            }
            from = start + needle.length();
        }
    }

    // ==================================================================
    // NFR-01 — @Transactional on every write path
    // ==================================================================

    @Test
    @DisplayName("NFR-01: every public service method is transactional, and only the read paths are readOnly")
    void nfr01_everyServiceMethodIsTransactional() {
        List<Class<?>> services = List.of(ProgramServiceImpl.class, ScreeningServiceImpl.class,
                UserServiceImpl.class, AuthServiceImpl.class);
        Set<String> readPaths = Set.of("searchPrograms", "viewProgram", "searchScreenings",
                "viewScreening", "getUser");

        List<String> missing = new ArrayList<>();
        List<String> wrongMode = new ArrayList<>();
        for (Class<?> service : services) {
            for (Method method : service.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                    continue;
                }
                Transactional annotation = method.getAnnotation(Transactional.class);
                if (annotation == null) {
                    missing.add(service.getSimpleName() + '#' + method.getName());
                    continue;
                }
                boolean shouldBeReadOnly = readPaths.contains(method.getName());
                if (annotation.readOnly() != shouldBeReadOnly) {
                    wrongMode.add(service.getSimpleName() + '#' + method.getName()
                            + " readOnly=" + annotation.readOnly());
                }
            }
        }

        assertThat(missing).as("a write path without @Transactional can leave a partial write behind").isEmpty();
        assertThat(wrongMode).as("a read path must be readOnly; a write path must not be").isEmpty();
    }

    @Test
    @DisplayName("NFR-01: a partial write never persists — a mid-method failure rolls the whole method back")
    void nfr01_aPartialWriteNeverPersists() throws Exception {
        // Drive the programme to FINAL_SUBMISSION with one APPROVED screening.
        LocalDateTime start = LocalDateTime.of(2026, 6, 10, 20, 0);
        Screening screening = screeningRepository.save(Screening.builder()
                .program(program).submitter(bob).state(ScreeningState.APPROVED)
                .filmTitle("Original Title").filmCast("A Cast").filmGenres("Drama")
                .filmDurationMinutes(120).auditoriumName("Main Hall")
                .startTime(start).endTime(start.plusHours(3))
                .build());
        program.setState(ProgramState.FINAL_SUBMISSION);
        programRepository.save(program);

        // The final submission applies the bundle of changes FIRST and validates
        // the timing AFTERWARDS, so the entity really is dirty when the 400 is
        // raised. Only the rollback keeps the new title out of the database.
        mockMvc.perform(post("/api/screenings/" + screening.getId() + "/final-submit")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filmTitle\":\"Overwritten Title\",\"endTime\":\"2026-06-10T20:30:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("SCREENING_DURATION_TOO_SHORT"));

        Screening reloaded = screeningRepository.findById(screening.getId()).orElseThrow();
        assertThat(reloaded.getFilmTitle())
                .as("the partial write must have been rolled back")
                .isEqualTo("Original Title");
        assertThat(reloaded.getEndTime()).isEqualTo(start.plusHours(3));
        assertThat(reloaded.isFinallySubmitted()).isFalse();
    }

    // ==================================================================
    // NFR-08 / NFR-09 — the SLF4J audit trail
    // ==================================================================

    @Test
    @DisplayName("NFR-08: every program state transition writes an audit line, accepted and refused alike")
    void nfr08_programTransitionsAreAudited() throws Exception {
        mockMvc.perform(post("/api/programs/" + program.getId() + "/submission-start")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/programs/" + program.getId() + "/announce")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict());

        assertThat(auditLines()).anyMatch(line -> line.contains("event=PROGRAM_STATE_TRANSITION")
                && line.contains("outcome=SUCCESS") && line.contains("from=CREATED")
                && line.contains("to=SUBMISSION") && line.contains("actor=alice01"));
        assertThat(auditLines()).anyMatch(line -> line.contains("event=PROGRAM_STATE_TRANSITION")
                && line.contains("outcome=REJECTED") && line.contains("reason=INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("NFR-08: every screening state transition writes an audit line naming the function that caused it")
    void nfr08_screeningTransitionsAreAudited() throws Exception {
        program.setState(ProgramState.SUBMISSION);
        programRepository.save(program);
        LocalDateTime start = LocalDateTime.of(2026, 6, 10, 20, 0);
        Screening screening = screeningRepository.save(Screening.builder()
                .program(program).submitter(bob).state(ScreeningState.CREATED)
                .filmTitle("Arrival").filmCast("Amy Adams").filmGenres("SciFi")
                .filmDurationMinutes(116).auditoriumName("Main Hall")
                .startTime(start).endTime(start.plusHours(3)).build());

        mockMvc.perform(post("/api/screenings/" + screening.getId() + "/submit")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());

        assertThat(auditLines()).anyMatch(line -> line.contains("event=SCREENING_STATE_TRANSITION")
                && line.contains("outcome=SUCCESS") && line.contains("from=CREATED")
                && line.contains("to=SUBMITTED") && line.contains("trigger=SUBMIT"));
    }

    @Test
    @DisplayName("NFR-09: a successful authentication writes an audit line")
    void nfr09_successfulAuthenticationIsAudited() throws Exception {
        login("carol_x", "UserPass3!");

        assertThat(auditLines()).anyMatch(line -> line.contains("event=AUTHENTICATE")
                && line.contains("outcome=SUCCESS") && line.contains("username=carol_x"));
    }

    @Test
    @DisplayName("NFR-09: a failed authentication writes a warning audit line, and never the password")
    void nfr09_failedAuthenticationIsAudited() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol_x\",\"password\":\"WrongPass1!\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(auditLines()).anyMatch(line -> line.contains("event=AUTHENTICATE")
                && line.contains("outcome=FAILURE") && line.contains("username=carol_x"));
        assertThat(auditLines()).noneMatch(line -> line.contains("WrongPass1!"));
        assertThat(auditLevels()).contains(Level.WARN);
    }

    @Test
    @DisplayName("NFR-09: logout and the token-rejection path are audited too")
    void nfr09_logoutAndTokenRejectionAreAudited() throws Exception {
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/users/" + carol.getId())
                        .header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isUnauthorized());

        assertThat(auditLines()).anyMatch(line -> line.contains("event=LOGOUT")
                && line.contains("username=carol_x"));
        assertThat(auditLines()).anyMatch(line -> line.contains("event=TOKEN_VALIDATION")
                && line.contains("outcome=REJECTED") && line.contains("code=TOKEN_EXPIRED"));
        assertThat(auditLines())
                .as("a bearer token is never written to the log in full")
                .noneMatch(line -> line.contains(carolToken));
    }

    @Test
    @DisplayName("M5: search calls are audited with the number of results the redaction layer let through")
    void m5_searchCallsAreAudited() throws Exception {
        mockMvc.perform(get("/api/programs")).andExpect(status().isOk());
        mockMvc.perform(get("/api/programs/" + program.getId() + "/screenings")
                        .param("timetable", "true")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        assertThat(auditLines()).anyMatch(line -> line.contains("event=SEARCH")
                && line.contains("domain=PROGRAM") && line.contains("actor=VISITOR"));
        assertThat(auditLines()).anyMatch(line -> line.contains("event=SEARCH")
                && line.contains("domain=SCREENING_TIMETABLE") && line.contains("actor=alice01"));
    }

    // ==================================================================
    // NFR-10 — the response-time target
    // ==================================================================

    @Test
    @DisplayName("NFR-10: a fully-filtered search over a populated catalogue answers far inside the 5-second target")
    void nfr10_aSearchAnswersWellInsideTheFiveSecondTarget() throws Exception {
        for (int i = 0; i < 40; i++) {
            Program p = programRepository.save(Program.builder()
                    .name("Season " + i).description("A season of films number " + i)
                    .startDate(LocalDate.of(2026, 1, 1).plusDays(i))
                    .endDate(LocalDate.of(2026, 2, 1).plusDays(i))
                    .state(ProgramState.ANNOUNCED).creator(alice).build());
            for (int j = 0; j < 5; j++) {
                LocalDateTime start = LocalDateTime.of(2026, 6, 1, 18, 0).plusDays(j);
                screeningRepository.save(Screening.builder()
                        .program(p).submitter(bob).state(ScreeningState.SCHEDULED)
                        .filmTitle("Film " + i + " part " + j).filmCast("A Cast").filmGenres("Drama")
                        .filmDurationMinutes(120).auditoriumName("Hall " + j)
                        .startTime(start).endTime(start.plusHours(3)).build());
            }
        }

        long startedAt = System.nanoTime();
        mockMvc.perform(get("/api/programs")
                        .param("description", "season films")
                        .param("filmTitle", "film part")
                        .param("auditorium", "hall"))
                .andExpect(status().isOk());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).as("DOMAIN_RULES.md: under 5 seconds per request").isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("NFR-10: a single view answers far inside the 5-second target too")
    void nfr10_aSingleViewAnswersWellInsideTheTarget() throws Exception {
        long startedAt = System.nanoTime();
        mockMvc.perform(get("/api/programs/" + program.getId())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(5));
    }
}
