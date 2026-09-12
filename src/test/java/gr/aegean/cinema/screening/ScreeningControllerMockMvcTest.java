package gr.aegean.cinema.screening;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.aegean.cinema.model.entity.Screening;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.PermanentRole;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level coverage of the screening module against the real controllers, the
 * real token interceptor, the real services and an in-memory H2 database.
 *
 * <p>This is where the STATUS CODES themselves are proven, and in particular the
 * three that are easy to confuse:
 * <ul>
 *   <li>a wrong actor really reaches the client as <b>403</b>;</li>
 *   <li>a state conflict really reaches it as <b>409</b> — including ROLE-19,
 *       which DOMAIN_RULES.md pins to 409 rather than to the 403 its wording
 *       might suggest, and including every refusal out of a terminal state;</li>
 *   <li>FR-SCR-10 really reaches it as <b>400</b>.</li>
 * </ul>
 * No response body may ever contain a stack trace or produce a 500.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScreeningControllerMockMvcTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthTokenRepository authTokenRepository;
    @Autowired private ProgramRepository programRepository;
    @Autowired private ProgramRoleRepository programRoleRepository;
    @Autowired private ScreeningRepository screeningRepository;
    @Autowired private PasswordUtil passwordUtil;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String aliceToken;   // PROGRAMMER and creator of the program
    private String bobToken;     // SUBMITTER of the screening under test
    private String daveToken;    // STAFF of the program, the assigned handler
    private String erinToken;    // another STAFF member of the program
    private String carolToken;   // an outsider with no role
    private String adminToken;

    private long programId;

    @BeforeEach
    void resetDatabase() throws Exception {
        authTokenRepository.deleteAll();
        screeningRepository.deleteAll();
        programRoleRepository.deleteAll();
        programRepository.deleteAll();
        userRepository.deleteAll();

        persist("alice01", "UserPass1!", PermanentRole.USER);
        persist("bob2024", "UserPass2!", PermanentRole.USER);
        persist("carol_x", "UserPass3!", PermanentRole.USER);
        persist("dave123", "UserPass4!", PermanentRole.USER);
        persist("erin567", "UserPass5!", PermanentRole.USER);
        persist("admin1", "AdminPass1!", PermanentRole.ADMIN);

        aliceToken = login("alice01", "UserPass1!");
        bobToken = login("bob2024", "UserPass2!");
        carolToken = login("carol_x", "UserPass3!");
        daveToken = login("dave123", "UserPass4!");
        erinToken = login("erin567", "UserPass5!");
        adminToken = login("admin1", "AdminPass1!");

        programId = createProgram("Summer Fest");
        addStaff(programId, "dave123");
        addStaff(programId, "erin567");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private void persist(String username, String rawPassword, PermanentRole role) {
        userRepository.save(User.builder()
                .username(username)
                .password(passwordUtil.hash(rawPassword))
                .fullName(username)
                .permanentRole(role)
                .active(true)
                .failedAuthAttempts(0)
                .failedPasswordAttempts(0)
                .build());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private long createProgram(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"A season\","
                                + "\"startDate\":\"2026-06-01\",\"endDate\":\"2026-06-30\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void addStaff(long program, String username) throws Exception {
        mockMvc.perform(post("/api/programs/" + program + "/staff")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\"}"))
                .andExpect(status().isOk());
    }

    /** Drives the program to {@code target} through the legal named endpoints, as Alice. */
    private void driveTo(ProgramState target) throws Exception {
        String[] steps = {"submission-start", "assignment-start", "review-start", "scheduling-start",
                "final-submission-start", "decision-start", "announce"};
        ProgramState[] reached = {ProgramState.SUBMISSION, ProgramState.ASSIGNMENT, ProgramState.REVIEW,
                ProgramState.SCHEDULING, ProgramState.FINAL_SUBMISSION, ProgramState.DECISION,
                ProgramState.ANNOUNCED};
        ProgramState current = programRepository.findById(programId).orElseThrow().getState();
        for (int i = 0; i < steps.length; i++) {
            if (reached[i].ordinal() <= current.ordinal()) {
                continue;
            }
            mockMvc.perform(post("/api/programs/" + programId + "/" + steps[i])
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isOk());
            if (reached[i] == target) {
                return;
            }
        }
    }

    private String screeningBody(int durationMinutes, String start, String end) {
        return "{\"filmTitle\":\"Star Wars\",\"filmCast\":\"Mark Hamill, Carrie Fisher\","
                + "\"filmGenres\":\"SciFi,Adventure\",\"filmDurationMinutes\":" + durationMinutes + ","
                + "\"auditoriumName\":\"Hall A\",\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}";
    }

    private String completeScreeningBody() {
        return screeningBody(120, "2026-06-10T20:00:00", "2026-06-10T22:30:00");
    }

    /** Creates a complete screening over HTTP as Bob and returns its generated id. */
    private long createScreeningAsBob() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/programs/" + programId + "/screenings")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeScreeningBody()))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private Screening reload(long screeningId) {
        return screeningRepository.findById(screeningId).orElseThrow();
    }

    /** Walks one screening all the way to APPROVED and returns its id. */
    private long driveScreeningToApproved() throws Exception {
        driveTo(ProgramState.SUBMISSION);
        long id = createScreeningAsBob();

        mockMvc.perform(post("/api/screenings/" + id + "/submit")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());

        driveTo(ProgramState.ASSIGNMENT);
        mockMvc.perform(post("/api/screenings/" + id + "/handler")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffUsername\":\"dave123\"}"))
                .andExpect(status().isOk());

        driveTo(ProgramState.REVIEW);
        mockMvc.perform(post("/api/screenings/" + id + "/review")
                        .header("Authorization", "Bearer " + daveToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":8,\"comments\":\"A solid fit\"}"))
                .andExpect(status().isOk());

        driveTo(ProgramState.SCHEDULING);
        mockMvc.perform(post("/api/screenings/" + id + "/approve")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalNotes\":\"Shift the start if possible\"}"))
                .andExpect(status().isOk());

        return id;
    }

    // ==================================================================
    // The complete happy path — FR-SCR-T1 … FR-SCR-T7
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-T1/T3/T4/T7: the whole lifecycle runs over HTTP from creation to SCHEDULED")
    void theWholeLifecycleRunsOverHttp() throws Exception {
        long id = driveScreeningToApproved();

        driveTo(ProgramState.FINAL_SUBMISSION);
        mockMvc.perform(post("/api/screenings/" + id + "/final-submit")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"2026-06-10T20:30:00\",\"endTime\":\"2026-06-10T23:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finallySubmitted").value(true));

        driveTo(ProgramState.DECISION);
        mockMvc.perform(post("/api/screenings/" + id + "/accept")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SCHEDULED"));

        assertThat(reload(id).getState()).isEqualTo(ScreeningState.SCHEDULED);
    }

    // ==================================================================
    // Creation — FR-SCR-01 … FR-SCR-06, ROLE-05, ROLE-19
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-01/02/03/04/05/06/ROLE-05: a plain USER creates a screening; id, date, program link, SUBMITTER and CREATED state all come back")
    void creationReturnsAGeneratedScreeningInCreatedState() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/programs/" + programId + "/screenings")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeScreeningBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("CREATED"))
                .andExpect(jsonPath("$.submitterUsername").value("bob2024"))
                .andExpect(jsonPath("$.programId").value(programId))
                .andExpect(jsonPath("$.handlerUsername").doesNotExist())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("id").asLong()).isPositive();

        Screening saved = reload(body.get("id").asLong());
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getProgram().getId()).isEqualTo(programId);
        assertThat(saved.getHandler()).isNull();
    }

    @Test
    @DisplayName("ROLE-19: a PROGRAMMER creating a screening in their own program gets 409, not 403")
    void role19_returns409NotForbidden() throws Exception {
        mockMvc.perform(post("/api/programs/" + programId + "/screenings")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeScreeningBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("PROGRAMMER_CANNOT_SUBMIT_IN_OWN_PROGRAM"));

        assertThat(screeningRepository.count()).isZero();
    }

    @Test
    @DisplayName("ROLE-16/FR-SCR-01: an ADMIN creating a screening gets 403 ADMIN_NOT_ALLOWED")
    void adminCannotCreateAScreening() throws Exception {
        mockMvc.perform(post("/api/programs/" + programId + "/screenings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeScreeningBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ADMIN_NOT_ALLOWED"));

        assertThat(screeningRepository.count()).isZero();
    }

    @Test
    @DisplayName("FR-SCR-01: an anonymous caller with no token cannot create a screening")
    void anonymousCannotCreateAScreening() throws Exception {
        mockMvc.perform(post("/api/programs/" + programId + "/screenings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeScreeningBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_MISSING"));
    }

    @Test
    @DisplayName("FR-SCR-10: a slot shorter than the film duration is a 400, never a 409 and never a 500")
    void fr10_isABadRequestOverHttp() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/programs/" + programId + "/screenings")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(screeningBody(200, "2026-06-10T20:00:00", "2026-06-10T22:30:00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("SCREENING_DURATION_TOO_SHORT"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("Exception");
        assertThat(screeningRepository.count()).isZero();
    }

    @Test
    @DisplayName("FR-SCR-05: creating a screening inside a program that does not exist is a 404 PROGRAM_NOT_FOUND")
    void creationInAnUnknownProgramIs404() throws Exception {
        mockMvc.perform(post("/api/programs/999999/screenings")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeScreeningBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PROGRAM_NOT_FOUND"));
    }

    // ==================================================================
    // Update / submission / withdrawal — FR-SCR-07 … FR-SCR-15, ROLE-12
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-07/FR-SCR-09/ROLE-12: the SUBMITTER updates their own screening while it is in CREATED")
    void submitterUpdatesOwnScreening() throws Exception {
        long id = createScreeningAsBob();

        mockMvc.perform(put("/api/screenings/" + id)
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auditoriumName\":\"Hall B\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditoriumName").value("Hall B"));
    }

    @Test
    @DisplayName("FR-SCR-07/ROLE-12: another user updating somebody else's screening gets 403 NOT_SUBMITTER")
    void outsiderCannotUpdateSomebodyElsesScreening() throws Exception {
        long id = createScreeningAsBob();

        mockMvc.perform(put("/api/screenings/" + id)
                        .header("Authorization", "Bearer " + carolToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filmTitle\":\"hijacked\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_SUBMITTER"));

        assertThat(reload(id).getFilmTitle()).isEqualTo("Star Wars");
    }

    @Test
    @DisplayName("FR-SCR-08/FR-SCR-33: a regular update after the screening leaves CREATED is 409 SCREENING_NOT_UPDATABLE")
    void updateAfterCreatedIs409() throws Exception {
        driveTo(ProgramState.SUBMISSION);
        long id = createScreeningAsBob();
        mockMvc.perform(post("/api/screenings/" + id + "/submit")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/screenings/" + id)
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filmTitle\":\"too late\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SCREENING_NOT_UPDATABLE"));

        assertThat(reload(id).getFilmTitle()).isEqualTo("Star Wars");
    }

    @Test
    @DisplayName("FR-SCR-11/FR-SCR-12/FR-SCR-T1: the SUBMITTER submits the screening and it becomes SUBMITTED")
    void submissionMovesTheScreeningToSubmitted() throws Exception {
        driveTo(ProgramState.SUBMISSION);
        long id = createScreeningAsBob();

        mockMvc.perform(post("/api/screenings/" + id + "/submit")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUBMITTED"));
    }

    @Test
    @DisplayName("FR-SCR-12: submitting while the program is still in CREATED is 409 PROGRAM_NOT_IN_SUBMISSION")
    void submissionBeforeTheProgramOpensIs409() throws Exception {
        long id = createScreeningAsBob();

        mockMvc.perform(post("/api/screenings/" + id + "/submit")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROGRAM_NOT_IN_SUBMISSION"));

        assertThat(reload(id).getState()).isEqualTo(ScreeningState.CREATED);
    }

    @Test
    @DisplayName("FR-SCR-13: submitting an incomplete screening is 409 SCREENING_NOT_COMPLETE")
    void submittingAnIncompleteScreeningIs409() throws Exception {
        driveTo(ProgramState.SUBMISSION);
        MvcResult created = mockMvc.perform(post("/api/programs/" + programId + "/screenings")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filmTitle\":\"A draft\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/screenings/" + id + "/submit")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SCREENING_NOT_COMPLETE"));

        assertThat(reload(id).getState()).isEqualTo(ScreeningState.CREATED);
    }

    @Test
    @DisplayName("FR-SCR-11/ROLE-12: a user who is not the SUBMITTER cannot submit the screening")
    void onlyTheSubmitterMaySubmitOverHttp() throws Exception {
        driveTo(ProgramState.SUBMISSION);
        long id = createScreeningAsBob();

        mockMvc.perform(post("/api/screenings/" + id + "/submit")
                        .header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_SUBMITTER"));
    }

    @Test
    @DisplayName("FR-SCR-14/FR-SCR-15/FR-SCR-T2: the SUBMITTER withdraws a CREATED screening and the row disappears")
    void withdrawalDeletesTheScreeningOverHttp() throws Exception {
        long id = createScreeningAsBob();

        mockMvc.perform(post("/api/screenings/" + id + "/withdraw")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNoContent());

        assertThat(screeningRepository.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("FR-SCR-15: withdrawing a screening that has already been submitted is 409 and deletes nothing")
    void withdrawalAfterSubmissionIs409() throws Exception {
        driveTo(ProgramState.SUBMISSION);
        long id = createScreeningAsBob();
        mockMvc.perform(post("/api/screenings/" + id + "/submit")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/screenings/" + id + "/withdraw")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SCREENING_NOT_WITHDRAWABLE"));

        assertThat(screeningRepository.findById(id)).isPresent();
    }

    @Test
    @DisplayName("FR-SCR-14/ROLE-12: a user who is not the SUBMITTER cannot withdraw the screening")
    void outsiderCannotWithdraw() throws Exception {
        long id = createScreeningAsBob();

        mockMvc.perform(post("/api/screenings/" + id + "/withdraw")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_SUBMITTER"));

        assertThat(screeningRepository.findById(id)).isPresent();
    }

    // ==================================================================
    // Handler assignment — FR-SCR-16 … FR-SCR-19, ROLE-09
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-16/FR-SCR-17/FR-SCR-18/ROLE-09: a PROGRAMMER assigns one STAFF handler during ASSIGNMENT")
    void programmerAssignsAHandlerOverHttp() throws Exception {
        driveTo(ProgramState.SUBMISSION);
        long id = createScreeningAsBob();
        mockMvc.perform(post("/api/screenings/" + id + "/submit")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());
        driveTo(ProgramState.ASSIGNMENT);

        mockMvc.perform(post("/api/screenings/" + id + "/handler")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffUsername\":\"dave123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handlerUsername").value("dave123"));

        // FR-SCR-18: exactly one at a time - a re-assignment replaces it.
        mockMvc.perform(post("/api/screenings/" + id + "/handler")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffUsername\":\"erin567\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handlerUsername").value("erin567"));

        // getId() is safe on a lazily proxied association; getUsername() would not be.
        assertThat(reload(id).getHandler().getId())
                .isEqualTo(userRepository.findByUsername("erin567").orElseThrow().getId());
    }

    @Test
    @DisplayName("FR-SCR-16/ROLE-09: a non-PROGRAMMER assigning a handler gets 403 NOT_PROGRAMMER")
    void outsiderCannotAssignAHandler() throws Exception {
        driveTo(ProgramState.ASSIGNMENT);
        long id = persistScreeningDirectly(ScreeningState.SUBMITTED);

        mockMvc.perform(post("/api/screenings/" + id + "/handler")
                        .header("Authorization", "Bearer " + carolToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffUsername\":\"dave123\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_PROGRAMMER"));

        assertThat(reload(id).getHandler()).isNull();
    }

    @Test
    @DisplayName("FR-SCR-17: assigning a handler outside ASSIGNMENT is 409 PROGRAM_NOT_IN_ASSIGNMENT")
    void assigningOutsideAssignmentIs409() throws Exception {
        driveTo(ProgramState.SUBMISSION);
        long id = createScreeningAsBob();

        mockMvc.perform(post("/api/screenings/" + id + "/handler")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffUsername\":\"dave123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROGRAM_NOT_IN_ASSIGNMENT"));
    }

    @Test
    @DisplayName("FR-SCR-18: naming a user who is not STAFF of this program is 409 NOT_PROGRAM_STAFF")
    void assigningANonStaffUserIs409() throws Exception {
        driveTo(ProgramState.ASSIGNMENT);
        long id = persistScreeningDirectly(ScreeningState.SUBMITTED);

        mockMvc.perform(post("/api/screenings/" + id + "/handler")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffUsername\":\"carol_x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("NOT_PROGRAM_STAFF"));

        assertThat(reload(id).getHandler()).isNull();
    }

    @Test
    @DisplayName("FR-SCR-18: a handler payload without a username is a structured 400, never a 500")
    void anEmptyHandlerPayloadIs400() throws Exception {
        driveTo(ProgramState.ASSIGNMENT);
        long id = persistScreeningDirectly(ScreeningState.SUBMITTED);

        mockMvc.perform(post("/api/screenings/" + id + "/handler")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.staffUsername").exists());
    }

    // ==================================================================
    // Review / approval / rejection — FR-SCR-20 … FR-SCR-29, ROLE-08/10
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-20/FR-SCR-21/FR-SCR-22/ROLE-10/FR-SCR-T3: the assigned handler reviews the screening")
    void assignedHandlerReviewsOverHttp() throws Exception {
        long id = driveScreeningToApproved();

        assertThat(reload(id).getReviewScore()).isEqualTo(8);
        assertThat(reload(id).getReviewComments()).isEqualTo("A solid fit");
    }

    @Test
    @DisplayName("ROLE-10: a STAFF member who is not the assigned handler gets 403 NOT_HANDLER")
    void role10_unassignedStaffCannotReview() throws Exception {
        driveTo(ProgramState.SUBMISSION);
        long id = createScreeningAsBob();
        mockMvc.perform(post("/api/screenings/" + id + "/submit")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());
        driveTo(ProgramState.ASSIGNMENT);
        mockMvc.perform(post("/api/screenings/" + id + "/handler")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffUsername\":\"dave123\"}"))
                .andExpect(status().isOk());
        driveTo(ProgramState.REVIEW);

        mockMvc.perform(post("/api/screenings/" + id + "/review")
                        .header("Authorization", "Bearer " + erinToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":3,\"comments\":\"not mine\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_HANDLER"));

        assertThat(reload(id).getState()).isEqualTo(ScreeningState.SUBMITTED);
    }

    @Test
    @DisplayName("FR-SCR-22: a review score outside 1-10, or with no comments, is a structured 400 (ASSUMPTIONS.md #5)")
    void anOutOfRangeReviewScoreIs400() throws Exception {
        driveTo(ProgramState.REVIEW);
        long id = persistScreeningDirectly(ScreeningState.SUBMITTED);

        mockMvc.perform(post("/api/screenings/" + id + "/review")
                        .header("Authorization", "Bearer " + daveToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":42,\"comments\":\"way too high\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.score").exists());

        mockMvc.perform(post("/api/screenings/" + id + "/review")
                        .header("Authorization", "Bearer " + daveToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.comments").exists());

        assertThat(reload(id).getState()).isEqualTo(ScreeningState.SUBMITTED);
    }

    @Test
    @DisplayName("FR-SCR-23/FR-SCR-24/ROLE-08/FR-SCR-T4: a PROGRAMMER approves the screening in SCHEDULING")
    void programmerApprovesOverHttp() throws Exception {
        long id = driveScreeningToApproved();

        assertThat(reload(id).getState()).isEqualTo(ScreeningState.APPROVED);
        assertThat(reload(id).getApprovalNotes()).isEqualTo("Shift the start if possible");
    }

    @Test
    @DisplayName("FR-SCR-24: the SUBMITTER approving their own screening gets 403 NOT_PROGRAMMER (M0 finding #2 is fixed)")
    void fr24_submitterCannotApproveOverHttp() throws Exception {
        driveTo(ProgramState.SCHEDULING);
        long id = persistScreeningDirectly(ScreeningState.REVIEWED);

        mockMvc.perform(post("/api/screenings/" + id + "/approve")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_PROGRAMMER"));

        assertThat(reload(id).getState()).isEqualTo(ScreeningState.REVIEWED);
    }

    @Test
    @DisplayName("FR-SCR-23: approving outside SCHEDULING is 409 PROGRAM_NOT_IN_SCHEDULING")
    void approvingOutsideSchedulingIs409() throws Exception {
        driveTo(ProgramState.REVIEW);
        long id = persistScreeningDirectly(ScreeningState.REVIEWED);

        mockMvc.perform(post("/api/screenings/" + id + "/approve")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROGRAM_NOT_IN_SCHEDULING"));
    }

    @Test
    @DisplayName("FR-SCR-26/FR-SCR-29/FR-SCR-T5: a PROGRAMMER rejects a REVIEWED screening in SCHEDULING, with a reason")
    void programmerRejectsInSchedulingOverHttp() throws Exception {
        driveTo(ProgramState.SCHEDULING);
        long id = persistScreeningDirectly(ScreeningState.REVIEWED);

        mockMvc.perform(post("/api/screenings/" + id + "/reject")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"The review score is too low\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("The review score is too low"));
    }

    @Test
    @DisplayName("FR-SCR-27: a PROGRAMMER rejects an APPROVED screening in DECISION when the final submission fell short")
    void programmerRejectsInDecisionOverHttp() throws Exception {
        long id = driveScreeningToApproved();
        driveTo(ProgramState.FINAL_SUBMISSION);
        mockMvc.perform(post("/api/screenings/" + id + "/final-submit")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        driveTo(ProgramState.DECISION);

        mockMvc.perform(post("/api/screenings/" + id + "/reject")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"The required changes were not addressed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REJECTED"));

        assertThat(reload(id).getRejectionReason()).contains("not addressed");
    }

    @Test
    @DisplayName("FR-SCR-29: a rejection with no reason is refused with a structured 400 and changes nothing")
    void fr29_aRejectionWithoutAReasonIs400() throws Exception {
        driveTo(ProgramState.SCHEDULING);
        long id = persistScreeningDirectly(ScreeningState.REVIEWED);

        mockMvc.perform(post("/api/screenings/" + id + "/reject")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.reason").exists());

        mockMvc.perform(post("/api/screenings/" + id + "/reject")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        assertThat(reload(id).getState()).isEqualTo(ScreeningState.REVIEWED);
        assertThat(reload(id).getRejectionReason()).isNull();
    }

    @Test
    @DisplayName("FR-SCR-26/ROLE-08: a user who is not a PROGRAMMER cannot reject a screening")
    void outsiderCannotReject() throws Exception {
        driveTo(ProgramState.SCHEDULING);
        long id = persistScreeningDirectly(ScreeningState.REVIEWED);

        mockMvc.perform(post("/api/screenings/" + id + "/reject")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"I would rather not\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_PROGRAMMER"));
    }

    // ==================================================================
    // Final submission / acceptance — FR-SCR-30 … FR-SCR-36
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-32: after a successful final submission the details are frozen — a later update is 409")
    void fr32_detailsFreezeAfterTheFinalSubmissionOverHttp() throws Exception {
        long id = driveScreeningToApproved();
        driveTo(ProgramState.FINAL_SUBMISSION);
        mockMvc.perform(post("/api/screenings/" + id + "/final-submit")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auditoriumName\":\"Hall C\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditoriumName").value("Hall C"));

        mockMvc.perform(put("/api/screenings/" + id)
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auditoriumName\":\"Hall D\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SCREENING_DETAILS_FROZEN"));

        mockMvc.perform(post("/api/screenings/" + id + "/final-submit")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auditoriumName\":\"Hall E\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SCREENING_DETAILS_FROZEN"));

        assertThat(reload(id).getAuditoriumName()).isEqualTo("Hall C");
    }

    @Test
    @DisplayName("FR-SCR-31: a final submission outside FINAL_SUBMISSION is 409 PROGRAM_NOT_IN_FINAL_SUBMISSION")
    void finalSubmissionOutsideTheWindowIs409() throws Exception {
        long id = driveScreeningToApproved();

        mockMvc.perform(post("/api/screenings/" + id + "/final-submit")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROGRAM_NOT_IN_FINAL_SUBMISSION"));

        assertThat(reload(id).isFinallySubmitted()).isFalse();
    }

    @Test
    @DisplayName("FR-SCR-30/ROLE-12: a user who is not the SUBMITTER cannot finally submit the screening")
    void outsiderCannotFinallySubmit() throws Exception {
        long id = driveScreeningToApproved();
        driveTo(ProgramState.FINAL_SUBMISSION);

        mockMvc.perform(post("/api/screenings/" + id + "/final-submit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_SUBMITTER"));
    }

    @Test
    @DisplayName("FR-SCR-36: accepting an APPROVED screening that was never finally submitted is 409 SCREENING_NOT_FINALLY_SUBMITTED")
    void fr36_acceptanceNeedsAFinalSubmission() throws Exception {
        driveTo(ProgramState.DECISION);
        // Persisted AFTER the DECISION sweep, so it is still APPROVED and has no
        // final submission — exactly the case FR-SCR-36 forbids accepting.
        long id = persistScreeningDirectly(ScreeningState.APPROVED);

        mockMvc.perform(post("/api/screenings/" + id + "/accept")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SCREENING_NOT_FINALLY_SUBMITTED"));

        assertThat(reload(id).getState()).isEqualTo(ScreeningState.APPROVED);
    }

    @Test
    @DisplayName("FR-SCR-34/ROLE-08: a user who is not a PROGRAMMER cannot accept a screening")
    void outsiderCannotAccept() throws Exception {
        long id = driveScreeningToApproved();
        driveTo(ProgramState.FINAL_SUBMISSION);
        mockMvc.perform(post("/api/screenings/" + id + "/final-submit")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        driveTo(ProgramState.DECISION);

        mockMvc.perform(post("/api/screenings/" + id + "/accept")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_PROGRAMMER"));

        assertThat(reload(id).getState()).isEqualTo(ScreeningState.APPROVED);
    }

    // ==================================================================
    // FR-SCR-28 / FR-SCR-T6 — the automatic rejection, end to end over HTTP
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-28/FR-SCR-T6: entering DECISION auto-rejects the approved screening nobody finally submitted, with a reason")
    void fr28_theDecisionStepAutoRejectsOverHttp() throws Exception {
        long id = driveScreeningToApproved();

        driveTo(ProgramState.DECISION);

        Screening swept = reload(id);
        assertThat(swept.getState()).isEqualTo(ScreeningState.REJECTED);
        assertThat(swept.getRejectionReason()).isNotBlank();
    }

    // ==================================================================
    // FR-SCR-T8 — terminal states over HTTP
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-T8: a SCHEDULED screening refuses every further function with 409 SCREENING_TERMINAL_STATE")
    void fr_t8_scheduledIsTerminalOverHttp() throws Exception {
        long id = driveScreeningToApproved();
        driveTo(ProgramState.FINAL_SUBMISSION);
        mockMvc.perform(post("/api/screenings/" + id + "/final-submit")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        driveTo(ProgramState.DECISION);
        mockMvc.perform(post("/api/screenings/" + id + "/accept")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/screenings/" + id + "/accept")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SCREENING_TERMINAL_STATE"));

        mockMvc.perform(post("/api/screenings/" + id + "/reject")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"changed my mind\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SCREENING_TERMINAL_STATE"));

        mockMvc.perform(put("/api/screenings/" + id)
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filmTitle\":\"Renamed\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SCREENING_TERMINAL_STATE"));

        assertThat(reload(id).getState()).isEqualTo(ScreeningState.SCHEDULED);
        assertThat(reload(id).getFilmTitle()).isEqualTo("Star Wars");
    }

    @Test
    @DisplayName("FR-SCR-T8: a REJECTED screening refuses every further function with 409 SCREENING_TERMINAL_STATE")
    void fr_t8_rejectedIsTerminalOverHttp() throws Exception {
        driveTo(ProgramState.SCHEDULING);
        long id = persistScreeningDirectly(ScreeningState.REVIEWED);
        mockMvc.perform(post("/api/screenings/" + id + "/reject")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"The first and only reason\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/screenings/" + id + "/approve")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SCREENING_TERMINAL_STATE"));

        mockMvc.perform(post("/api/screenings/" + id + "/reject")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"A second reason\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SCREENING_TERMINAL_STATE"));

        mockMvc.perform(post("/api/screenings/" + id + "/withdraw")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SCREENING_TERMINAL_STATE"));

        Screening rejected = reload(id);
        assertThat(rejected.getState()).isEqualTo(ScreeningState.REJECTED);
        assertThat(rejected.getRejectionReason()).isEqualTo("The first and only reason");
    }

    // ==================================================================
    // Nothing ever escapes as a stack trace or a 500
    // ==================================================================

    @Test
    @DisplayName("FR-SCR-07: a malformed screening id is a structured 400 and never a 500 or a stack trace")
    void malformedScreeningIdIsAStructured400() throws Exception {
        MvcResult result = mockMvc.perform(put("/api/screenings/not-a-number")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filmTitle\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("Exception");
    }

    @Test
    @DisplayName("FR-SCR-07: acting on a screening id that does not exist is a 404 SCREENING_NOT_FOUND")
    void unknownScreeningIdIs404() throws Exception {
        mockMvc.perform(post("/api/screenings/999999/submit")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SCREENING_NOT_FOUND"));
    }

    @Test
    @DisplayName("FR-PRG-T9: once the program is ANNOUNCED no screening function works any more")
    void announcedProgramFreezesEveryScreeningFunction() throws Exception {
        long id = driveScreeningToApproved();
        driveTo(ProgramState.FINAL_SUBMISSION);
        mockMvc.perform(post("/api/screenings/" + id + "/final-submit")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        driveTo(ProgramState.DECISION);
        mockMvc.perform(post("/api/screenings/" + id + "/accept")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        driveTo(ProgramState.ANNOUNCED);

        mockMvc.perform(post("/api/programs/" + programId + "/screenings")
                        .header("Authorization", "Bearer " + carolToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeScreeningBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROGRAM_ANNOUNCED_FROZEN"));

        mockMvc.perform(put("/api/screenings/" + id)
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filmTitle\":\"Renamed\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROGRAM_ANNOUNCED_FROZEN"));
    }

    @Test
    @DisplayName("ROLE-16: an ADMIN is rejected with 403 from every screening state-transition endpoint")
    void role16_adminIsRejectedFromEveryScreeningEndpoint() throws Exception {
        long id = createScreeningAsBob();

        String[] endpoints = {"submit", "withdraw", "handler", "review", "approve", "reject",
                "final-submit", "accept"};
        for (String endpoint : endpoints) {
            mockMvc.perform(post("/api/screenings/" + id + "/" + endpoint)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"staffUsername\":\"dave123\",\"score\":5,\"comments\":\"c\","
                                    + "\"reason\":\"r\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("ADMIN_NOT_ALLOWED"));
        }

        mockMvc.perform(put("/api/screenings/" + id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filmTitle\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ADMIN_NOT_ALLOWED"));

        assertThat(reload(id).getState()).isEqualTo(ScreeningState.CREATED);
    }

    @Test
    @DisplayName("FR-SCR-05: the update endpoint ignores a program id smuggled into the payload")
    void fr05_theProgramLinkCannotBeChangedThroughAnUpdate() throws Exception {
        long id = createScreeningAsBob();
        long otherProgram = createOtherProgram();

        mockMvc.perform(put("/api/screenings/" + id)
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filmTitle\":\"Still here\",\"programId\":" + otherProgram + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.programId").value(programId));

        assertThat(reload(id).getProgram().getId()).isEqualTo(programId);
    }

    // ------------------------------------------------------------------
    // Direct persistence helpers, for states an HTTP walk cannot reach
    // ------------------------------------------------------------------

    /**
     * Persists a screening of Bob's in a given state directly, for the tests that
     * need a screening state the legal HTTP path cannot produce at that point of
     * the program's own lifecycle.
     */
    private long persistScreeningDirectly(ScreeningState state) {
        User bob = userRepository.findByUsername("bob2024").orElseThrow();
        return screeningRepository.save(Screening.builder()
                .program(programRepository.findById(programId).orElseThrow())
                .submitter(bob)
                .state(state)
                .filmTitle("Star Wars").filmCast("Mark Hamill").filmGenres("SciFi")
                .filmDurationMinutes(120).auditoriumName("Hall A")
                .startTime(java.time.LocalDateTime.of(2026, 6, 10, 20, 0))
                .endTime(java.time.LocalDateTime.of(2026, 6, 10, 22, 30))
                .build()).getId();
    }

    private long createOtherProgram() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + carolToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Autumn Fest\",\"description\":\"Another season\","
                                + "\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-30\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}
