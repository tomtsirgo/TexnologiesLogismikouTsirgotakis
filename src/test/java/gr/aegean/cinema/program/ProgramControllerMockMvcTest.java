package gr.aegean.cinema.program;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.aegean.cinema.model.entity.Program;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP-level coverage of the program module against the real controller, the
 * real token interceptor, the real services and an in-memory H2 database.
 *
 * <p>This is where the HTTP status codes themselves are proven — in particular
 * that an illegal or rollback transition really reaches the client as 409 and
 * not as the 400 the migrated code produced (PROJECT_STATE.md, M0 finding #3),
 * and that an unauthorized actor really gets 403 instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProgramControllerMockMvcTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthTokenRepository authTokenRepository;
    @Autowired private ProgramRepository programRepository;
    @Autowired private ProgramRoleRepository programRoleRepository;
    @Autowired private ScreeningRepository screeningRepository;
    @Autowired private PasswordUtil passwordUtil;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User alice;
    private User bob;
    private User carol;

    private String aliceToken;
    private String bobToken;
    private String adminToken;

    @BeforeEach
    void resetDatabase() throws Exception {
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
        adminToken = login("admin1", "AdminPass1!");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private User persist(String username, String rawPassword, PermanentRole role) {
        return userRepository.save(User.builder()
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

    private String createProgramBody(String name) {
        return "{\"name\":\"" + name + "\",\"description\":\"A season\","
                + "\"startDate\":\"2026-06-01\",\"endDate\":\"2026-06-30\"}";
    }

    /** Creates a program over HTTP as Alice and returns its generated id. */
    private long createProgramAsAlice(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProgramBody(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    /** Drives the program to {@code target} through the legal named endpoints, as Alice. */
    private void driveTo(long programId, ProgramState target) throws Exception {
        String[] steps = {"submission-start", "assignment-start", "review-start", "scheduling-start",
                "final-submission-start", "decision-start", "announce"};
        ProgramState[] reached = {ProgramState.SUBMISSION, ProgramState.ASSIGNMENT, ProgramState.REVIEW,
                ProgramState.SCHEDULING, ProgramState.FINAL_SUBMISSION, ProgramState.DECISION,
                ProgramState.ANNOUNCED};
        for (int i = 0; i < steps.length; i++) {
            mockMvc.perform(post("/api/programs/" + programId + "/" + steps[i])
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value(reached[i].name()));
            if (reached[i] == target) {
                return;
            }
        }
    }

    private Program reload(long programId) {
        return programRepository.findById(programId).orElseThrow();
    }

    // ==================================================================
    // Creation — FR-PRG-01 … FR-PRG-06, ROLE-04
    // ==================================================================

    @Test
    @DisplayName("FR-PRG-03/FR-PRG-04/FR-PRG-05/FR-PRG-06: creation generates id and date, starts in CREATED and makes the creator a PROGRAMMER")
    void creationGeneratesIdAndDateAndMakesCreatorProgrammer() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProgramBody("Summer Fest")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("CREATED"))
                .andExpect(jsonPath("$.creatorUsername").value("alice01"))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("id").asLong()).isPositive();
        assertThat(body.get("createdAt").isNull()).isFalse();

        Program saved = reload(body.get("id").asLong());
        assertThat(saved.getCreator().getUsername()).isEqualTo("alice01");
        assertThat(programRoleRepository.existsByUserAndProgramAndRole(alice, saved, ProgramRoleType.PROGRAMMER))
                .isTrue();
    }

    @Test
    @DisplayName("FR-PRG-01: creating a second program with an existing name returns 409 PROGRAM_NAME_TAKEN")
    void duplicateProgramNameReturns409() throws Exception {
        createProgramAsAlice("Summer Fest");

        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProgramBody("Summer Fest")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROGRAM_NAME_TAKEN"));
    }

    @Test
    @DisplayName("FR-PRG-02: creation without the required name, description or dates returns 400, never 500")
    void creationWithMissingRequiredFieldsReturns400() throws Exception {
        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.description").exists())
                .andExpect(jsonPath("$.fieldErrors.startDate").exists())
                .andExpect(jsonPath("$.fieldErrors.endDate").exists());

        assertThat(programRepository.count()).isZero();
    }

    @Test
    @DisplayName("ROLE-04: any authenticated plain USER may create a program; an ADMIN gets 403 ADMIN_NOT_ALLOWED")
    void plainUserMayCreateButAdminMayNot() throws Exception {
        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProgramBody("Bob's Fest")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProgramBody("Admin's Fest")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ADMIN_NOT_ALLOWED"));

        assertThat(programRepository.count()).isEqualTo(1);
    }

    // ==================================================================
    // Update and role management — FR-PRG-07 … FR-PRG-14, ROLE-06/07/17/18
    // ==================================================================

    @Test
    @DisplayName("FR-PRG-07/ROLE-06: a PROGRAMMER may update the program over HTTP")
    void programmerMayUpdateTheProgram() throws Exception {
        long id = createProgramAsAlice("Summer Fest");

        mockMvc.perform(put("/api/programs/" + id)
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Updated description\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Updated description"));
    }

    @Test
    @DisplayName("ROLE-07: a user with no role in the program gets 403 NOT_PROGRAMMER when updating it")
    void outsiderGets403OnUpdate() throws Exception {
        long id = createProgramAsAlice("Summer Fest");

        mockMvc.perform(put("/api/programs/" + id)
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"hijacked\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_PROGRAMMER"));

        assertThat(reload(id).getDescription()).isEqualTo("A season");
    }

    @Test
    @DisplayName("FR-PRG-10/FR-PRG-11/ROLE-17: a PROGRAMMER may be added once; a second role for the same user is 409")
    void addProgrammerOnceThenConflict() throws Exception {
        long id = createProgramAsAlice("Summer Fest");

        mockMvc.perform(post("/api/programs/" + id + "/programmers")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob2024\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.programmerUsernames", org.hamcrest.Matchers.hasItem("bob2024")));

        mockMvc.perform(post("/api/programs/" + id + "/staff")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob2024\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ROLE_ALREADY_ASSIGNED"));
    }

    @Test
    @DisplayName("FR-PRG-12/FR-PRG-13: a PROGRAMMER may add STAFF, and a duplicate role assignment is 409")
    void addStaffOnceThenConflict() throws Exception {
        long id = createProgramAsAlice("Summer Fest");

        mockMvc.perform(post("/api/programs/" + id + "/staff")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol_x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffUsernames", org.hamcrest.Matchers.hasItem("carol_x")));

        mockMvc.perform(post("/api/programs/" + id + "/staff")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol_x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ROLE_ALREADY_ASSIGNED"));
    }

    @Test
    @DisplayName("FR-PRG-14: adding or removing STAFF after the program leaves CREATED returns 409 STAFF_SET_FROZEN")
    void staffSetIsFrozenAfterCreated() throws Exception {
        long id = createProgramAsAlice("Summer Fest");
        mockMvc.perform(post("/api/programs/" + id + "/staff")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol_x\"}"))
                .andExpect(status().isOk());

        driveTo(id, ProgramState.SUBMISSION);

        mockMvc.perform(post("/api/programs/" + id + "/staff")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob2024\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STAFF_SET_FROZEN"));

        mockMvc.perform(delete("/api/programs/" + id + "/staff/carol_x")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STAFF_SET_FROZEN"));

        assertThat(programRoleRepository.existsByUserAndProgramAndRole(carol, reload(id), ProgramRoleType.STAFF))
                .isTrue();
    }

    @Test
    @DisplayName("FR-PRG-08: the creator cannot be removed from the PROGRAMMERS set, but another PROGRAMMER can")
    void creatorCannotBeRemovedButOtherProgrammersCan() throws Exception {
        long id = createProgramAsAlice("Summer Fest");
        mockMvc.perform(post("/api/programs/" + id + "/programmers")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob2024\"}"))
                .andExpect(status().isOk());

        // Bob, now a PROGRAMMER himself, still cannot remove the creator.
        mockMvc.perform(delete("/api/programs/" + id + "/programmers/alice01")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CREATOR_NOT_REMOVABLE"));

        assertThat(programRoleRepository.existsByUserAndProgramAndRole(alice, reload(id), ProgramRoleType.PROGRAMMER))
                .isTrue();

        // ... whereas Bob himself may be removed.
        mockMvc.perform(delete("/api/programs/" + id + "/programmers/bob2024")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        assertThat(programRoleRepository.existsByUserAndProgramAndRole(bob, reload(id), ProgramRoleType.PROGRAMMER))
                .isFalse();
    }

    @Test
    @DisplayName("ROLE-18: the same user may be STAFF of one program and PROGRAMMER of another at the same time")
    void sameUserMayHoldDifferentRolesInDifferentPrograms() throws Exception {
        long first = createProgramAsAlice("Summer Fest");
        mockMvc.perform(post("/api/programs/" + first + "/staff")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol_x\"}"))
                .andExpect(status().isOk());

        MvcResult created = mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProgramBody("Autumn Fest")))
                .andExpect(status().isCreated())
                .andReturn();
        long second = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/programs/" + second + "/programmers")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol_x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.programmerUsernames", org.hamcrest.Matchers.hasItem("carol_x")));

        assertThat(programRoleRepository.existsByUserAndProgramAndRole(carol, reload(first), ProgramRoleType.STAFF))
                .isTrue();
        assertThat(programRoleRepository.existsByUserAndProgramAndRole(
                carol, reload(second), ProgramRoleType.PROGRAMMER)).isTrue();
    }

    // ==================================================================
    // State machine — FR-PRG-T1 … FR-PRG-T9, ROLE-20
    // ==================================================================

    @Test
    @DisplayName("FR-PRG-T1/T2/T3/T4/T5/T6/T7: all seven named transition endpoints walk the program to ANNOUNCED")
    void allSevenTransitionEndpointsSucceedInOrder() throws Exception {
        long id = createProgramAsAlice("Summer Fest");

        driveTo(id, ProgramState.ANNOUNCED);

        assertThat(reload(id).getState()).isEqualTo(ProgramState.ANNOUNCED);
    }

    @Test
    @DisplayName("FR-PRG-T8: a skip-ahead transition over HTTP returns 409 INVALID_STATE_TRANSITION")
    void skipAheadReturns409() throws Exception {
        long id = createProgramAsAlice("Summer Fest");

        mockMvc.perform(post("/api/programs/" + id + "/review-start")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));

        assertThat(reload(id).getState()).isEqualTo(ProgramState.CREATED);
    }

    @Test
    @DisplayName("FR-PRG-T8: a rollback transition over HTTP returns 409 INVALID_STATE_TRANSITION, not 400")
    void rollbackReturns409NotBadRequest() throws Exception {
        long id = createProgramAsAlice("Summer Fest");
        driveTo(id, ProgramState.ASSIGNMENT);

        mockMvc.perform(put("/api/programs/" + id + "/state")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetState\":\"SUBMISSION\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));

        assertThat(reload(id).getState()).isEqualTo(ProgramState.ASSIGNMENT);
    }

    @Test
    @DisplayName("FR-PRG-T9: no transition leaves the terminal ANNOUNCED state")
    void announcedIsTerminalOverHttp() throws Exception {
        long id = createProgramAsAlice("Summer Fest");
        driveTo(id, ProgramState.ANNOUNCED);

        mockMvc.perform(put("/api/programs/" + id + "/state")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetState\":\"DECISION\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));

        assertThat(reload(id).getState()).isEqualTo(ProgramState.ANNOUNCED);
    }

    @Test
    @DisplayName("FR-PRG-T9/FR-PRG-09: in ANNOUNCED the program can no longer be updated")
    void announcedProgramCannotBeUpdated() throws Exception {
        long id = createProgramAsAlice("Summer Fest");
        driveTo(id, ProgramState.ANNOUNCED);

        mockMvc.perform(put("/api/programs/" + id)
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"too late\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROGRAM_ANNOUNCED_FROZEN"));

        assertThat(reload(id).getDescription()).isEqualTo("A season");
    }

    @Test
    @DisplayName("FR-PRG-T9: in ANNOUNCED a screening of that program can no longer be updated either")
    void announcedProgramFreezesItsScreeningsToo() throws Exception {
        long id = createProgramAsAlice("Summer Fest");
        Program program = reload(id);
        Screening screening = screeningRepository.save(Screening.builder()
                .program(program).submitter(carol).state(ScreeningState.CREATED)
                .filmTitle("Star Wars").filmCast("A cast").filmGenres("SciFi").filmDurationMinutes(120)
                .auditoriumName("Hall A")
                .startTime(LocalDateTime.of(2026, 6, 10, 20, 0))
                .endTime(LocalDateTime.of(2026, 6, 10, 22, 30))
                .build());
        driveTo(id, ProgramState.ANNOUNCED);

        String carolToken = login("carol_x", "UserPass3!");
        mockMvc.perform(put("/api/screenings/" + screening.getId())
                        .header("Authorization", "Bearer " + carolToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filmTitle\":\"Renamed\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROGRAM_ANNOUNCED_FROZEN"));

        assertThat(screeningRepository.findById(screening.getId()).orElseThrow().getFilmTitle())
                .isEqualTo("Star Wars");
    }

    @Test
    @DisplayName("FR-PRG-T6: the decision-making endpoint auto-rejects an APPROVED screening with no final submission")
    void decisionStepAutoRejectsUnfinishedApprovedScreenings() throws Exception {
        long id = createProgramAsAlice("Summer Fest");
        Program program = reload(id);
        Screening unfinished = screeningRepository.save(Screening.builder()
                .program(program).submitter(carol).state(ScreeningState.APPROVED)
                .filmTitle("Unfinished").auditoriumName("Hall A").filmDurationMinutes(90)
                .startTime(LocalDateTime.of(2026, 6, 10, 20, 0))
                .endTime(LocalDateTime.of(2026, 6, 10, 22, 0))
                .build());
        Screening finished = screeningRepository.save(Screening.builder()
                .program(program).submitter(bob).state(ScreeningState.APPROVED)
                .filmTitle("Finished").auditoriumName("Hall B").filmDurationMinutes(90)
                .startTime(LocalDateTime.of(2026, 6, 11, 20, 0))
                .endTime(LocalDateTime.of(2026, 6, 11, 22, 0))
                .finalSubmissionDate(LocalDateTime.of(2026, 5, 1, 10, 0))
                .build());

        driveTo(id, ProgramState.DECISION);

        Screening reloadedUnfinished = screeningRepository.findById(unfinished.getId()).orElseThrow();
        Screening reloadedFinished = screeningRepository.findById(finished.getId()).orElseThrow();
        assertThat(reloadedUnfinished.getState()).isEqualTo(ScreeningState.REJECTED);
        assertThat(reloadedUnfinished.getRejectionReason()).isNotBlank();
        assertThat(reloadedFinished.getState()).isEqualTo(ScreeningState.APPROVED);
    }

    @Test
    @DisplayName("ROLE-20: a non-PROGRAMMER attempting a state transition gets 403 NOT_PROGRAMMER, not 409")
    void unauthorizedTransitionReturns403() throws Exception {
        long id = createProgramAsAlice("Summer Fest");

        mockMvc.perform(post("/api/programs/" + id + "/submission-start")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_PROGRAMMER"));

        assertThat(reload(id).getState()).isEqualTo(ProgramState.CREATED);
    }

    @Test
    @DisplayName("ROLE-20: an ADMIN attempting a state transition gets 403 ADMIN_NOT_ALLOWED")
    void adminTransitionReturns403() throws Exception {
        long id = createProgramAsAlice("Summer Fest");

        mockMvc.perform(post("/api/programs/" + id + "/submission-start")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ADMIN_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("ROLE-20: an anonymous caller with no token cannot trigger a state transition")
    void anonymousTransitionIsRejected() throws Exception {
        long id = createProgramAsAlice("Summer Fest");

        mockMvc.perform(post("/api/programs/" + id + "/submission-start"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_MISSING"));
    }

    // ==================================================================
    // Deletion — FR-PRG-24, FR-PRG-25
    // ==================================================================

    @Test
    @DisplayName("FR-PRG-24/FR-PRG-25: a PROGRAMMER deletes the program in CREATED and it really disappears")
    void programmerDeletesProgramInCreated() throws Exception {
        long id = createProgramAsAlice("Summer Fest");

        mockMvc.perform(delete("/api/programs/" + id)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        assertThat(programRepository.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("FR-PRG-24: deleting a program that has left CREATED returns 409 PROGRAM_NOT_DELETABLE")
    void deletingAfterCreatedReturns409() throws Exception {
        long id = createProgramAsAlice("Summer Fest");
        driveTo(id, ProgramState.SUBMISSION);

        mockMvc.perform(delete("/api/programs/" + id)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROGRAM_NOT_DELETABLE"));

        assertThat(programRepository.findById(id)).isPresent();
    }

    @Test
    @DisplayName("FR-PRG-25: a user who is not a PROGRAMMER of the program gets 403 when deleting it")
    void outsiderCannotDeleteProgram() throws Exception {
        long id = createProgramAsAlice("Summer Fest");

        mockMvc.perform(delete("/api/programs/" + id)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_PROGRAMMER"));

        assertThat(programRepository.findById(id)).isPresent();
    }

    // ==================================================================
    // Nothing ever escapes as a stack trace or a 500
    // ==================================================================

    @Test
    @DisplayName("FR-PRG-07: a malformed program id is a structured 400 and never a 500 or a stack trace")
    void malformedProgramIdIsAStructured400() throws Exception {
        MvcResult result = mockMvc.perform(put("/api/programs/not-a-number")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("Exception");
    }

    @Test
    @DisplayName("FR-PRG-07: acting on a program id that does not exist is a 404 PROGRAM_NOT_FOUND")
    void unknownProgramIdIs404() throws Exception {
        mockMvc.perform(put("/api/programs/999999")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PROGRAM_NOT_FOUND"));
    }

    @Test
    @DisplayName("FR-PRG-10: adding a role for an unknown username is a 404 USER_NOT_FOUND")
    void unknownRoleTargetIs404() throws Exception {
        long id = createProgramAsAlice("Summer Fest");

        mockMvc.perform(post("/api/programs/" + id + "/programmers")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost99\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("FR-PRG-01: a program name is unique; the duplicate is never persisted (no partial write)")
    void aRejectedCreationPersistsNothing() throws Exception {
        createProgramAsAlice("Summer Fest");
        long before = programRoleRepository.count();

        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProgramBody("Summer Fest")))
                .andExpect(status().isConflict());

        assertThat(programRepository.count()).isEqualTo(1);
        assertThat(programRoleRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("FR-PRG-02: a program whose end date precedes its start date is a 400 INVALID_PROGRAM_DATES")
    void invertedDatesAre400() throws Exception {
        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Backwards\",\"description\":\"d\","
                                + "\"startDate\":\"2026-09-01\",\"endDate\":\"2026-06-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PROGRAM_DATES"));

        assertThat(programRepository.count()).isZero();
    }
}
