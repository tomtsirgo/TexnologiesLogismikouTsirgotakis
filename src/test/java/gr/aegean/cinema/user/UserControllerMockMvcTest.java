package gr.aegean.cinema.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.aegean.cinema.model.entity.AuthToken;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.PermanentRole;
import gr.aegean.cinema.repository.AuthTokenRepository;
import gr.aegean.cinema.repository.ProgramRepository;
import gr.aegean.cinema.repository.ProgramRoleRepository;
import gr.aegean.cinema.repository.ScreeningRepository;
import gr.aegean.cinema.repository.UserRepository;
import gr.aegean.cinema.security.PasswordUtil;
import gr.aegean.cinema.security.TokenAuthInterceptor;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end HTTP-level coverage of the user-management module: the real
 * controller, the real {@link TokenAuthInterceptor}, the real services and an
 * in-memory H2 database.
 *
 * This is where the wiring itself is proven: that the interceptor really runs
 * on every call except register/login, that anonymous VISITOR reads really work
 * with no token, that the three token error codes really reach the client
 * through the global handler, and that no invalid input can produce a 500 or a
 * stack trace.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerMockMvcTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthTokenRepository authTokenRepository;
    @Autowired private ProgramRoleRepository programRoleRepository;
    @Autowired private ScreeningRepository screeningRepository;
    @Autowired private ProgramRepository programRepository;
    @Autowired private PasswordUtil passwordUtil;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User alice;
    private User bob;
    private User admin;

    @BeforeEach
    void resetDatabase() {
        authTokenRepository.deleteAll();
        screeningRepository.deleteAll();
        programRoleRepository.deleteAll();
        programRepository.deleteAll();
        userRepository.deleteAll();

        alice = persist("alice01", "UserPass1!", "Alice Anderson", PermanentRole.USER, true);
        bob = persist("bob2024", "UserPass2!", "Bob Brown", PermanentRole.USER, true);
        admin = persist("admin1", "AdminPass1!", "System Administrator", PermanentRole.ADMIN, true);
    }

    private User persist(String username, String rawPassword, String fullName, PermanentRole role, boolean active) {
        return userRepository.save(User.builder()
                .username(username)
                .password(passwordUtil.hash(rawPassword))
                .fullName(fullName)
                .permanentRole(role)
                .active(active)
                .failedAuthAttempts(0)
                .failedPasswordAttempts(0)
                .build());
    }

    private String json(Object... keyValues) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(keyValues[i]).append("\":");
            Object value = keyValues[i + 1];
            if (value instanceof Boolean || value == null) {
                sb.append(value);
            } else {
                sb.append('"').append(value).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", username, "password", password)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("token").asText();
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    // ==================================================================
    // Registration
    // ==================================================================

    @Test
    @DisplayName("FR-USR-01: POST /api/users/register needs no token and creates an INACTIVE account")
    void registerNeedsNoTokenAndCreatesInactiveAccount() throws Exception {
        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", "newuser1", "password", "NewPass1!", "fullName", "New User")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("newuser1"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.permanentRole").value("USER"));

        assertThat(userRepository.findByUsername("newuser1")).isPresent()
                .get().extracting(User::isActive).isEqualTo(false);
    }

    @Test
    @DisplayName("FR-USR-02: registering an existing username returns 409 USERNAME_TAKEN")
    void duplicateUsernameReturns409() throws Exception {
        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", "alice01", "password", "NewPass1!", "fullName", "Impostor")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("USERNAME_TAKEN"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("FR-USR-03/04: pattern violations return 400 with the specific error code")
    void patternViolationsReturn400() throws Exception {
        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", "1bad", "password", "NewPass1!", "fullName", "Bad Name")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_USERNAME_PATTERN"));

        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", "gooduser", "password", "weak", "fullName", "Weak Pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PASSWORD_PATTERN"));
    }

    // ==================================================================
    // Authentication, lockout, logout
    // ==================================================================

    @Test
    @DisplayName("FR-USR-21/22: a second login issues a new token and kills the first one")
    void secondLoginInvalidatesTheFirstToken() throws Exception {
        String firstToken = loginAndGetToken("alice01", "UserPass1!");
        String secondToken = loginAndGetToken("alice01", "UserPass1!");

        assertThat(secondToken).isNotEqualTo(firstToken);

        mockMvc.perform(get("/api/users/" + alice.getId())
                        .header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_EXPIRED"));

        mockMvc.perform(get("/api/users/" + alice.getId())
                        .header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("FR-USR-23: three failed logins deactivate the account over HTTP")
    void threeFailedLoginsDeactivateTheAccount() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("username", "alice01", "password", "WrongPass1!")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("AUTH_FAILED"));
        }

        assertThat(reload(alice).isActive()).as("deactivated after the 3rd failure").isFalse();

        // FR-USR-15: and now even the CORRECT password is refused.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", "alice01", "password", "UserPass1!")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_FAILED"));
    }

    @Test
    @DisplayName("Two failures then a success reset the streak over HTTP")
    void twoFailuresThenSuccessResetsTheStreak() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("username", "alice01", "password", "WrongPass1!")))
                    .andExpect(status().isUnauthorized());
        }
        loginAndGetToken("alice01", "UserPass1!");
        assertThat(reload(alice).getFailedAuthAttempts()).isZero();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", "alice01", "password", "WrongPass1!")))
                .andExpect(status().isUnauthorized());

        assertThat(reload(alice).isActive()).as("still active - the streak was broken").isTrue();
    }

    @Test
    @DisplayName("FR-USR-24: logout invalidates the caller's own token")
    void logoutInvalidatesTheOwnToken() throws Exception {
        String token = loginAndGetToken("alice01", "UserPass1!");

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/" + alice.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("FR-USR-25/26: an ADMIN may force-logout a USER but not another ADMIN")
    void adminForceLogout() throws Exception {
        String aliceToken = loginAndGetToken("alice01", "UserPass1!");
        String adminToken = loginAndGetToken("admin1", "AdminPass1!");
        persist("admin2", "AdminPass2!", "Second Admin", PermanentRole.ADMIN, true);

        mockMvc.perform(post("/api/auth/logout/alice01").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/" + alice.getId()).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_EXPIRED"));

        mockMvc.perform(post("/api/auth/logout/admin2").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORCE_LOGOUT_ADMIN_FORBIDDEN"));
    }

    // ==================================================================
    // Token validation: the three distinct codes
    // ==================================================================

    @Test
    @DisplayName("FR-USR-28/29: a protected endpoint without a token is 401 TOKEN_MISSING")
    void protectedEndpointWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/users/" + alice.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_MISSING"));
    }

    @Test
    @DisplayName("FR-USR-30: an unknown token is 401 TOKEN_INVALID")
    void unknownTokenIsInvalid() throws Exception {
        mockMvc.perform(get("/api/users/" + alice.getId())
                        .header("Authorization", "Bearer " + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_INVALID"));

        mockMvc.perform(get("/api/users/" + alice.getId())
                        .header("Authorization", "Bearer not-even-a-uuid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("FR-USR-31: a token past its expiry is 401 TOKEN_EXPIRED")
    void expiredTokenIsExpired() throws Exception {
        AuthToken expired = authTokenRepository.save(AuthToken.builder()
                .tokenValue(UUID.randomUUID().toString())
                .user(alice)
                .issuedAt(LocalDateTime.now().minusDays(3))
                .expiresAt(LocalDateTime.now().minusDays(2))
                .revoked(false)
                .build());

        mockMvc.perform(get("/api/users/" + alice.getId())
                        .header("Authorization", "Bearer " + expired.getTokenValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("FR-USR-32/33: a valid token presented by a different requester deactivates BOTH accounts")
    void validTokenWrongOwnerDeactivatesBothAccounts() throws Exception {
        String aliceToken = loginAndGetToken("alice01", "UserPass1!");
        String bobToken = loginAndGetToken("bob2024", "UserPass2!");

        mockMvc.perform(get("/api/users/" + bob.getId())
                        .header("Authorization", "Bearer " + aliceToken)
                        .header(TokenAuthInterceptor.REQUESTER_USERNAME_HEADER, "bob2024"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_NOT_OWNER"));

        assertThat(reload(alice).isActive()).as("the token owner is deactivated").isFalse();
        assertThat(reload(bob).isActive()).as("the claimed requester is deactivated").isFalse();

        // Both users' tokens are revoked as part of the penalty.
        assertThat(authTokenRepository.countByUserAndRevokedFalse(reload(alice))).isZero();
        assertThat(authTokenRepository.countByUserAndRevokedFalse(reload(bob))).isZero();
        assertThat(bobToken).isNotBlank();
    }

    @Test
    @DisplayName("A plain 403 (own token, someone else's resource) deactivates NOBODY")
    void plainForbiddenDeactivatesNobody() throws Exception {
        String aliceToken = loginAndGetToken("alice01", "UserPass1!");

        mockMvc.perform(get("/api/users/" + bob.getId())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_SELF_OR_ADMIN"));

        assertThat(reload(alice).isActive()).as("the caller must stay active").isTrue();
        assertThat(reload(bob).isActive()).as("the target must stay active").isTrue();
        // And Alice's token is still perfectly usable.
        mockMvc.perform(get("/api/users/" + alice.getId())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A public read endpoint works with NO Authorization header at all")
    void publicReadEndpointWorksAnonymously() throws Exception {
        mockMvc.perform(get("/api/programs"))
                .andExpect(status().isOk());
    }

    // ==================================================================
    // Profile update / password / status / deletion
    // ==================================================================

    @Test
    @DisplayName("FR-USR-07: a username change invalidates the current token")
    void usernameChangeInvalidatesTheToken() throws Exception {
        String token = loginAndGetToken("alice01", "UserPass1!");

        mockMvc.perform(put("/api/users/" + alice.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", "alice_v2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice_v2"));

        mockMvc.perform(get("/api/users/" + alice.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("FR-USR-11: a SUCCESSFUL password change still invalidates the current token")
    void successfulPasswordChangeInvalidatesTheToken() throws Exception {
        String token = loginAndGetToken("alice01", "UserPass1!");

        mockMvc.perform(put("/api/users/" + alice.getId() + "/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("oldPassword", "UserPass1!",
                                "newPassword", "BrandNew1!", "newPasswordRepeat", "BrandNew1!")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/" + alice.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_EXPIRED"));

        // The new password really is in force.
        assertThat(loginAndGetToken("alice01", "BrandNew1!")).isNotBlank();
    }

    @Test
    @DisplayName("FR-USR-11/12: a FAILED password change invalidates the token, and three of them lock the account")
    void failedPasswordChangesInvalidateTokenAndLockOut() throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            String token = loginAndGetToken("alice01", "UserPass1!");
            mockMvc.perform(put("/api/users/" + alice.getId() + "/password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("oldPassword", "NotMyPass1!",
                                    "newPassword", "BrandNew1!", "newPasswordRepeat", "BrandNew1!")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("WRONG_OLD_PASSWORD"));

            // The token is invalidated even though the change failed.
            mockMvc.perform(get("/api/users/" + alice.getId()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("TOKEN_EXPIRED"));
        }

        assertThat(reload(alice).isActive()).as("locked out after 3 failed password changes").isFalse();
    }

    @Test
    @DisplayName("FR-USR-10: an ADMIN may not change another user's password")
    void adminCannotChangeSomebodyElsesPassword() throws Exception {
        String adminToken = loginAndGetToken("admin1", "AdminPass1!");

        mockMvc.perform(put("/api/users/" + alice.getId() + "/password")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("oldPassword", "UserPass1!",
                                "newPassword", "BrandNew1!", "newPasswordRepeat", "BrandNew1!")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_SELF"));

        assertThat(reload(alice).isActive()).isTrue();
    }

    @Test
    @DisplayName("FR-USR-13/14: an ADMIN deactivation invalidates the target's token; FR-USR-16 then blocks them")
    void adminDeactivationInvalidatesTokenAndBlocksProfileChanges() throws Exception {
        String aliceToken = loginAndGetToken("alice01", "UserPass1!");
        String adminToken = loginAndGetToken("admin1", "AdminPass1!");

        mockMvc.perform(put("/api/users/" + alice.getId() + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(put("/api/users/" + alice.getId())
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("fullName", "Still Trying")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("FR-USR-13: a non-ADMIN cannot change an account's status")
    void nonAdminCannotChangeStatus() throws Exception {
        String aliceToken = loginAndGetToken("alice01", "UserPass1!");

        mockMvc.perform(put("/api/users/" + bob.getId() + "/status")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ADMIN_ONLY"));

        assertThat(reload(bob).isActive()).isTrue();
    }

    @Test
    @DisplayName("FR-USR-18/19: a self-delete removes the account and every one of its tokens")
    void selfDeleteRemovesAccountAndTokens() throws Exception {
        String token = loginAndGetToken("alice01", "UserPass1!");
        Long aliceId = alice.getId();

        mockMvc.perform(delete("/api/users/" + aliceId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(aliceId)).isEmpty();
        assertThat(authTokenRepository.findByTokenValue(token))
                .as("every token of the deleted account is gone").isEmpty();
    }

    @Test
    @DisplayName("FR-USR-18: an ADMIN may delete a USER account")
    void adminMayDeleteAUserAccount() throws Exception {
        String adminToken = loginAndGetToken("admin1", "AdminPass1!");

        mockMvc.perform(delete("/api/users/" + bob.getId()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(bob.getId())).isEmpty();
    }

    @Test
    @DisplayName("FR-USR-20: an ADMIN account cannot be deleted - 403 ADMIN_NOT_DELETABLE")
    void adminAccountIsNotDeletable() throws Exception {
        String adminToken = loginAndGetToken("admin1", "AdminPass1!");

        mockMvc.perform(delete("/api/users/" + admin.getId()).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ADMIN_NOT_DELETABLE"));

        assertThat(userRepository.findById(admin.getId())).isPresent();
    }

    // ==================================================================
    // ADMIN is user-management only
    // ==================================================================

    @Test
    @DisplayName("ROLE-15/16: ADMIN is rejected with 403 from program and screening management endpoints")
    void adminIsRejectedFromCinemaManagementEndpoints() throws Exception {
        String adminToken = loginAndGetToken("admin1", "AdminPass1!");

        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "Winter Season", "description", "A season",
                                "startDate", "2026-01-01", "endDate", "2026-02-01")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ADMIN_NOT_ALLOWED"));

        mockMvc.perform(post("/api/programs/1/screenings")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("filmTitle", "Some Film")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ADMIN_NOT_ALLOWED"));
    }

    // ==================================================================
    // NFR-02 / NFR-03 / NFR-04: no 500, no stack trace, ever
    // ==================================================================

    @Test
    @DisplayName("NFR-02/03: every error body carries timestamp, status, errorCode and message - and no stack trace")
    void errorBodiesAreStructuredAndCarryNoStackTrace() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users/" + alice.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.errorCode").value("TOKEN_MISSING"))
                .andExpect(jsonPath("$.message").exists())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("Exception")
                .doesNotContain("\tat ")
                .doesNotContain("gr.aegean.cinema");
    }

    @Test
    @DisplayName("NFR-03/04: malformed, mistyped and incomplete input never produces a 500")
    void invalidInputNeverProducesA500() throws Exception {
        String token = loginAndGetToken("alice01", "UserPass1!");

        // Unparsable JSON body.
        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json at all"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST_BODY"));

        // Missing mandatory fields.
        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        // A path variable of the wrong type.
        mockMvc.perform(get("/api/users/not-a-number").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));

        // A well-formed request for an account that does not exist.
        mockMvc.perform(get("/api/users/987654").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("NOT_SELF_OR_ADMIN"));
    }
}
