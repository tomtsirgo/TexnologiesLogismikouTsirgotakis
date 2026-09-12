package gr.aegean.cinema.user;

import gr.aegean.cinema.exception.AuthenticationException;
import gr.aegean.cinema.exception.ForbiddenException;
import gr.aegean.cinema.exception.TokenErrorCode;
import gr.aegean.cinema.model.entity.AuthToken;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.PermanentRole;
import gr.aegean.cinema.repository.AuthTokenRepository;
import gr.aegean.cinema.repository.ProgramRoleRepository;
import gr.aegean.cinema.repository.UserRepository;
import gr.aegean.cinema.security.AuthorizationService;
import gr.aegean.cinema.security.CurrentUserContext;
import gr.aegean.cinema.security.TokenAuthInterceptor;
import gr.aegean.cinema.security.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * FR-USR-27 .. FR-USR-33: central token validation, the three DISTINCT failure
 * codes, and the sharp line between
 *
 * <ul>
 *   <li>a <b>token-ownership violation</b> - a live valid token presented by
 *       somebody who declares they are a different user: BOTH accounts are
 *       deactivated; and</li>
 *   <li>a <b>plain authorization failure</b> - a caller using their OWN valid
 *       token to reach somebody else's resource: a simple 403 that deactivates
 *       NOBODY.</li>
 * </ul>
 *
 * The migrated code conflated the two (INVENTORY.md); both directions are
 * asserted here so the regression cannot come back unnoticed.
 */
class UserTokenOwnershipTest {

    private AuthTokenRepository authTokenRepository;
    private UserRepository userRepository;
    private TokenService tokenService;
    private AuthorizationService authorizationService;
    private TokenAuthInterceptor interceptor;

    private User alice;
    private User bob;
    private final List<AuthToken> tokens = new ArrayList<>();

    @BeforeEach
    void setUp() {
        CurrentUserContext.clear();
        authTokenRepository = mock(AuthTokenRepository.class);
        userRepository = mock(UserRepository.class);
        tokenService = new TokenService(authTokenRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(tokenService, "ttlMinutes", 1440L);
        authorizationService = new AuthorizationService(
                mock(ProgramRoleRepository.class), userRepository, tokenService);
        interceptor = new TokenAuthInterceptor(authTokenRepository, userRepository, authorizationService);

        alice = User.builder().id(1L).username("alice01").password("H").fullName("Alice")
                .permanentRole(PermanentRole.USER).active(true).build();
        bob = User.builder().id(2L).username("bob2024").password("H").fullName("Bob")
                .permanentRole(PermanentRole.USER).active(true).build();

        tokens.clear();
        lenient().when(userRepository.findByUsername(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            if ("alice01".equals(name)) return Optional.of(alice);
            if ("bob2024".equals(name)) return Optional.of(bob);
            return Optional.empty();
        });
        lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        lenient().when(userRepository.findById(2L)).thenReturn(Optional.of(bob));
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(authTokenRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(authTokenRepository.findByUserAndRevokedFalse(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            return tokens.stream()
                    .filter(t -> !t.isRevoked() && t.getUser().getId().equals(u.getId()))
                    .toList();
        });
    }

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private AuthToken liveTokenFor(User user) {
        AuthToken token = AuthToken.builder()
                .id((long) (tokens.size() + 1))
                .tokenValue(UUID.randomUUID().toString())
                .user(user)
                .issuedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(24))
                .revoked(false)
                .build();
        tokens.add(token);
        when(authTokenRepository.findByTokenValueWithUser(token.getTokenValue()))
                .thenReturn(Optional.of(token));
        return token;
    }

    private MockHttpServletRequest protectedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/1");
        request.setRequestURI("/api/users/1");
        return request;
    }

    private boolean run(MockHttpServletRequest request) {
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    // ------------------------------------------------------------------
    // FR-USR-27 / FR-USR-29: no token
    // ------------------------------------------------------------------

    @Test
    @DisplayName("FR-USR-27: register and authenticate need no token at all")
    void registerAndLoginRequireNoToken() {
        MockHttpServletRequest register = new MockHttpServletRequest("POST", "/api/users/register");
        register.setRequestURI("/api/users/register");
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/api/auth/login");
        login.setRequestURI("/api/auth/login");

        assertThat(run(register)).isTrue();
        assertThat(run(login)).isTrue();
        assertThat(CurrentUserContext.get()).isNull();
    }

    @Test
    @DisplayName("A public read endpoint works with NO token at all - the caller is an anonymous VISITOR")
    void publicReadEndpointsWorkWithoutAnyToken() {
        MockHttpServletRequest search = new MockHttpServletRequest("GET", "/api/programs");
        search.setRequestURI("/api/programs");
        MockHttpServletRequest view = new MockHttpServletRequest("GET", "/api/programs/4");
        view.setRequestURI("/api/programs/4");
        MockHttpServletRequest screening = new MockHttpServletRequest("GET", "/api/screenings/9");
        screening.setRequestURI("/api/screenings/9");

        assertThat(run(search)).isTrue();
        assertThat(run(view)).isTrue();
        assertThat(run(screening)).isTrue();
        assertThat(CurrentUserContext.get()).as("an anonymous caller has no user context").isNull();
    }

    @Test
    @DisplayName("FR-USR-29: a missing token on a protected endpoint is TOKEN_MISSING")
    void missingTokenOnProtectedEndpoint() {
        assertThatThrownBy(() -> run(protectedRequest()))
                .isInstanceOf(AuthenticationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TokenErrorCode.TOKEN_MISSING);
    }

    // ------------------------------------------------------------------
    // FR-USR-30: TOKEN_INVALID
    // ------------------------------------------------------------------

    @Test
    @DisplayName("FR-USR-30: an unknown token value is TOKEN_INVALID")
    void unknownTokenIsInvalid() {
        when(authTokenRepository.findByTokenValueWithUser("no-such-token")).thenReturn(Optional.empty());
        MockHttpServletRequest request = protectedRequest();
        request.addHeader("Authorization", "Bearer no-such-token");

        assertThatThrownBy(() -> run(request))
                .isInstanceOf(AuthenticationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TokenErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("FR-USR-30: a malformed Authorization header is TOKEN_INVALID, not TOKEN_MISSING")
    void malformedAuthorizationHeaderIsInvalid() {
        MockHttpServletRequest request = protectedRequest();
        request.addHeader("Authorization", "Basic YWxpY2U6c2VjcmV0");

        assertThatThrownBy(() -> run(request))
                .isInstanceOf(AuthenticationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TokenErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("A 'Bearer' header with an empty value is TOKEN_INVALID")
    void emptyBearerValueIsInvalid() {
        MockHttpServletRequest request = protectedRequest();
        request.addHeader("Authorization", "Bearer    ");

        assertThatThrownBy(() -> run(request))
                .isInstanceOf(AuthenticationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TokenErrorCode.TOKEN_INVALID);
    }

    // ------------------------------------------------------------------
    // FR-USR-31: TOKEN_EXPIRED
    // ------------------------------------------------------------------

    @Test
    @DisplayName("FR-USR-31: a token past its expires_at is TOKEN_EXPIRED")
    void expiredTokenIsExpired() {
        AuthToken expired = AuthToken.builder().id(90L).tokenValue("expired-tok").user(alice)
                .issuedAt(LocalDateTime.now().minusHours(48))
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .revoked(false).build();
        when(authTokenRepository.findByTokenValueWithUser("expired-tok")).thenReturn(Optional.of(expired));

        MockHttpServletRequest request = protectedRequest();
        request.addHeader("Authorization", "Bearer expired-tok");

        assertThatThrownBy(() -> run(request))
                .isInstanceOf(AuthenticationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TokenErrorCode.TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("FR-USR-31: a revoked (logged-out) token is TOKEN_EXPIRED, distinct from TOKEN_INVALID")
    void revokedTokenIsExpired() {
        AuthToken token = liveTokenFor(alice);
        token.setRevoked(true);

        MockHttpServletRequest request = protectedRequest();
        request.addHeader("Authorization", "Bearer " + token.getTokenValue());

        assertThatThrownBy(() -> run(request))
                .isInstanceOf(AuthenticationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TokenErrorCode.TOKEN_EXPIRED);
    }

    // ------------------------------------------------------------------
    // The happy path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A live token with no requester claim authenticates its owner")
    void liveTokenAuthenticatesItsOwner() {
        AuthToken token = liveTokenFor(alice);
        MockHttpServletRequest request = protectedRequest();
        request.addHeader("Authorization", "Bearer " + token.getTokenValue());

        assertThat(run(request)).isTrue();
        assertThat(CurrentUserContext.get()).isSameAs(alice);
    }

    @Test
    @DisplayName("A requester claim that names the token's own owner is accepted (by username or by id)")
    void matchingRequesterClaimIsAccepted() {
        AuthToken token = liveTokenFor(alice);

        MockHttpServletRequest byName = protectedRequest();
        byName.addHeader("Authorization", "Bearer " + token.getTokenValue());
        byName.addHeader(TokenAuthInterceptor.REQUESTER_USERNAME_HEADER, "alice01");
        assertThat(run(byName)).isTrue();
        assertThat(CurrentUserContext.get()).isSameAs(alice);

        CurrentUserContext.clear();
        MockHttpServletRequest byId = protectedRequest();
        byId.addHeader("Authorization", "Bearer " + token.getTokenValue());
        byId.addHeader(TokenAuthInterceptor.REQUESTER_ID_HEADER, "1");
        assertThat(run(byId)).isTrue();
        assertThat(CurrentUserContext.get()).isSameAs(alice);

        assertThat(alice.isActive()).isTrue();
    }

    // ------------------------------------------------------------------
    // FR-USR-32 / FR-USR-33: the ONLY double-deactivation path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("FR-USR-32/33: a live token used by a requester who is NOT its owner deactivates BOTH accounts")
    void validTokenPresentedByNonOwnerDeactivatesBothAccounts() {
        AuthToken aliceToken = liveTokenFor(alice);
        AuthToken bobToken = liveTokenFor(bob);

        // Bob's request carries ALICE's token: the token is valid, live and
        // non-revoked, but it does not belong to the declared requester.
        MockHttpServletRequest request = protectedRequest();
        request.addHeader("Authorization", "Bearer " + aliceToken.getTokenValue());
        request.addHeader(TokenAuthInterceptor.REQUESTER_USERNAME_HEADER, "bob2024");

        assertThatThrownBy(() -> run(request))
                .isInstanceOf(AuthenticationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TokenErrorCode.TOKEN_NOT_OWNER);

        assertThat(alice.isActive()).as("the token's owner is deactivated").isFalse();
        assertThat(bob.isActive()).as("the claimed requester is deactivated").isFalse();
        assertThat(aliceToken.isRevoked()).isTrue();
        assertThat(bobToken.isRevoked()).isTrue();
        assertThat(CurrentUserContext.get()).isNull();
    }

    @Test
    @DisplayName("The same violation expressed with a numeric id header is treated identically")
    void ownershipViolationDetectedByIdHeaderToo() {
        AuthToken aliceToken = liveTokenFor(alice);
        MockHttpServletRequest request = protectedRequest();
        request.addHeader("Authorization", "Bearer " + aliceToken.getTokenValue());
        request.addHeader(TokenAuthInterceptor.REQUESTER_ID_HEADER, "2");

        assertThatThrownBy(() -> run(request))
                .isInstanceOf(AuthenticationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TokenErrorCode.TOKEN_NOT_OWNER);

        assertThat(alice.isActive()).isFalse();
        assertThat(bob.isActive()).isFalse();
    }

    @Test
    @DisplayName("A requester claim naming no real account still deactivates the token's owner")
    void ownershipViolationWithUnknownClaimedRequester() {
        AuthToken aliceToken = liveTokenFor(alice);
        MockHttpServletRequest request = protectedRequest();
        request.addHeader("Authorization", "Bearer " + aliceToken.getTokenValue());
        request.addHeader(TokenAuthInterceptor.REQUESTER_USERNAME_HEADER, "ghost99");

        assertThatThrownBy(() -> run(request))
                .isInstanceOf(AuthenticationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TokenErrorCode.TOKEN_NOT_OWNER);

        assertThat(alice.isActive()).isFalse();
        assertThat(bob.isActive()).as("an unrelated account is untouched").isTrue();
    }

    @Test
    @DisplayName("An EXPIRED token used by a non-owner is only TOKEN_EXPIRED - no account is deactivated")
    void expiredTokenNeverTriggersTheDoubleDeactivation() {
        AuthToken expired = AuthToken.builder().id(91L).tokenValue("stale-tok").user(alice)
                .issuedAt(LocalDateTime.now().minusHours(48))
                .expiresAt(LocalDateTime.now().minusMinutes(5))
                .revoked(false).build();
        when(authTokenRepository.findByTokenValueWithUser("stale-tok")).thenReturn(Optional.of(expired));

        MockHttpServletRequest request = protectedRequest();
        request.addHeader("Authorization", "Bearer stale-tok");
        request.addHeader(TokenAuthInterceptor.REQUESTER_USERNAME_HEADER, "bob2024");

        assertThatThrownBy(() -> run(request))
                .isInstanceOf(AuthenticationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TokenErrorCode.TOKEN_EXPIRED);

        assertThat(alice.isActive()).isTrue();
        assertThat(bob.isActive()).isTrue();
    }

    // ------------------------------------------------------------------
    // The regression that must never come back: a plain 403 deactivates NOBODY
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A plain authorization failure (own valid token, someone else's resource) is 403 and deactivates NOBODY")
    void plainAuthorizationFailureDeactivatesNobody() {
        CurrentUserContext.set(alice);

        // Alice, using her own perfectly valid token, asks for Bob's profile.
        assertThatThrownBy(() -> authorizationService.requireSelfOrAdmin(bob.getId()))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_SELF_OR_ADMIN");

        assertThat(alice.isActive()).as("the caller must NOT be deactivated").isTrue();
        assertThat(bob.isActive()).as("the target must NOT be deactivated").isTrue();
        verify(userRepository, never()).save(any(User.class));
        verify(authTokenRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("requireSelf on somebody else's account is also a plain 403 that deactivates NOBODY")
    void requireSelfFailureDeactivatesNobody() {
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> authorizationService.requireSelf(bob.getId()))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_SELF");

        assertThat(alice.isActive()).isTrue();
        assertThat(bob.isActive()).isTrue();
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("An ADMIN reaching another user's profile is allowed and deactivates nobody")
    void adminMayReachAnotherUsersProfile() {
        User admin = User.builder().id(99L).username("admin1").password("H").fullName("Admin")
                .permanentRole(PermanentRole.ADMIN).active(true).build();
        CurrentUserContext.set(admin);

        assertThat(authorizationService.requireSelfOrAdmin(bob.getId())).isSameAs(admin);
        assertThat(admin.isActive()).isTrue();
        assertThat(bob.isActive()).isTrue();
    }

    @Test
    @DisplayName("FR-USR-16/17: an inactive account is blocked from authenticated actions with 403 ACCOUNT_INACTIVE")
    void inactiveAccountIsBlockedFromAuthenticatedActions() {
        alice.setActive(false);
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> authorizationService.requireAuthenticated())
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ACCOUNT_INACTIVE");
        assertThatThrownBy(() -> authorizationService.requireSelf(alice.getId()))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ACCOUNT_INACTIVE");
        assertThatThrownBy(() -> authorizationService.requireCinemaActor())
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ACCOUNT_INACTIVE");
    }

    @Test
    @DisplayName("With no authenticated user at all, requireAuthenticated reports TOKEN_MISSING")
    void noContextMeansTokenMissing() {
        assertThatThrownBy(() -> authorizationService.requireAuthenticated())
                .isInstanceOf(AuthenticationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TokenErrorCode.TOKEN_MISSING);
    }
}
