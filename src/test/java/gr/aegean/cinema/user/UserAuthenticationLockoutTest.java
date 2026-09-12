package gr.aegean.cinema.user;

import gr.aegean.cinema.dto.auth.LoginRequest;
import gr.aegean.cinema.dto.auth.LoginResponse;
import gr.aegean.cinema.exception.AuthenticationException;
import gr.aegean.cinema.exception.TokenErrorCode;
import gr.aegean.cinema.model.entity.AuthToken;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.PermanentRole;
import gr.aegean.cinema.repository.UserRepository;
import gr.aegean.cinema.security.AuthorizationService;
import gr.aegean.cinema.security.PasswordUtil;
import gr.aegean.cinema.security.TokenService;
import gr.aegean.cinema.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * FR-USR-15, FR-USR-21, FR-USR-22, FR-USR-23: the authentication handshake and
 * the 3-consecutive-failure lockout.
 */
class UserAuthenticationLockoutTest {

    private static final String HASH = "$2a$10$hashedvalue";

    private UserRepository userRepository;
    private PasswordUtil passwordUtil;
    private TokenService tokenService;
    private AuthServiceImpl authService;
    private User alice;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordUtil = mock(PasswordUtil.class);
        tokenService = mock(TokenService.class);
        authService = new AuthServiceImpl(userRepository, passwordUtil, tokenService,
                mock(AuthorizationService.class));

        alice = User.builder().id(7L).username("alice01").password(HASH)
                .fullName("Alice").permanentRole(PermanentRole.USER)
                .active(true).failedAuthAttempts(0).failedPasswordAttempts(0).build();

        lenient().when(userRepository.findByUsername("alice01")).thenReturn(Optional.of(alice));
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(tokenService.issueToken(any(User.class))).thenAnswer(inv -> token("tok-new"));
    }

    private AuthToken token(String value) {
        return AuthToken.builder().id(1L).tokenValue(value).user(alice)
                .issuedAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusHours(24))
                .revoked(false).build();
    }

    private static LoginRequest login(String username, String password) {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        return req;
    }

    private void attemptWrongPassword() {
        when(passwordUtil.matches("wrong", HASH)).thenReturn(false);
        assertThatThrownBy(() -> authService.login(login("alice01", "wrong")))
                .isInstanceOf(AuthenticationException.class);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("FR-USR-23: three CONSECUTIVE failed authentications deactivate the account")
    void threeConsecutiveFailuresDeactivateTheAccount() {
        attemptWrongPassword();
        assertThat(alice.isActive()).as("still active after 1 failure").isTrue();
        assertThat(alice.getFailedAuthAttempts()).isEqualTo(1);

        attemptWrongPassword();
        assertThat(alice.isActive()).as("still active after 2 failures").isTrue();
        assertThat(alice.getFailedAuthAttempts()).isEqualTo(2);

        attemptWrongPassword();
        assertThat(alice.isActive()).as("deactivated on the 3rd consecutive failure").isFalse();

        // A lockout is a deactivation, and deactivation invalidates the current token.
        verify(tokenService).revokeActiveTokens(alice);
        verify(tokenService, never()).issueToken(any());
    }

    @Test
    @DisplayName("Two failures followed by a success reset the counter to zero - the streak must be CONSECUTIVE")
    void twoFailuresThenSuccessResetsTheCounter() {
        attemptWrongPassword();
        attemptWrongPassword();
        assertThat(alice.getFailedAuthAttempts()).isEqualTo(2);

        when(passwordUtil.matches("Valid123!", HASH)).thenReturn(true);
        LoginResponse response = authService.login(login("alice01", "Valid123!"));

        assertThat(response.getToken()).isEqualTo("tok-new");
        assertThat(alice.getFailedAuthAttempts()).as("a success resets the streak").isZero();
        assertThat(alice.isActive()).isTrue();

        // A third failure AFTER the reset must not lock the account out.
        attemptWrongPassword();
        assertThat(alice.isActive()).isTrue();
        assertThat(alice.getFailedAuthAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-USR-21/22: a successful authentication issues a NEW token, invalidating any previous one")
    void successIssuesNewTokenAndInvalidatesPrevious() {
        when(passwordUtil.matches("Valid123!", HASH)).thenReturn(true);

        LoginResponse response = authService.login(login("alice01", "Valid123!"));

        assertThat(response.getToken()).isEqualTo("tok-new");
        assertThat(response.getUserId()).isEqualTo(7L);
        assertThat(response.getExpiresAt()).isAfter(LocalDateTime.now());
        // TokenService.issueToken is the single place that revokes the previous
        // token before creating the new one - see UserTokenLifecycleTest.
        verify(tokenService).issueToken(alice);
    }

    @Test
    @DisplayName("FR-USR-15: an INACTIVE account cannot authenticate even with the correct password")
    void inactiveAccountCannotAuthenticate() {
        alice.setActive(false);

        assertThatThrownBy(() -> authService.login(login("alice01", "Valid123!")))
                .isInstanceOf(AuthenticationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TokenErrorCode.AUTH_FAILED);

        verify(passwordUtil, never()).matches(any(), any());
        verify(tokenService, never()).issueToken(any());
    }

    @Test
    @DisplayName("An unknown username fails with the generic AUTH_FAILED code, not a token code")
    void unknownUsernameFailsGenerically() {
        when(userRepository.findByUsername("nobody7")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(login("nobody7", "Valid123!")))
                .isInstanceOf(AuthenticationException.class)
                .hasFieldOrPropertyWithValue("errorCode", TokenErrorCode.AUTH_FAILED);
    }

    @Test
    @DisplayName("The login handshake never returns one of the three token-validation codes")
    void loginFailureCodeIsDistinctFromTokenValidationCodes() {
        when(passwordUtil.matches("wrong", HASH)).thenReturn(false);

        AuthenticationException ex = org.junit.jupiter.api.Assertions.assertThrows(
                AuthenticationException.class, () -> authService.login(login("alice01", "wrong")));

        assertThat(ex.getErrorCode()).isNotIn(
                TokenErrorCode.TOKEN_INVALID, TokenErrorCode.TOKEN_EXPIRED,
                TokenErrorCode.TOKEN_NOT_OWNER, TokenErrorCode.TOKEN_MISSING);
    }

    @Test
    @DisplayName("The failure message never discloses which half of the credentials was wrong")
    void failureMessageDoesNotDiscloseWhichCredentialWasWrong() {
        when(userRepository.findByUsername("nobody7")).thenReturn(Optional.empty());
        when(passwordUtil.matches("wrong", HASH)).thenReturn(false);

        String unknownUserMessage = org.junit.jupiter.api.Assertions.assertThrows(
                AuthenticationException.class,
                () -> authService.login(login("nobody7", "Valid123!"))).getMessage();
        String wrongPasswordMessage = org.junit.jupiter.api.Assertions.assertThrows(
                AuthenticationException.class,
                () -> authService.login(login("alice01", "wrong"))).getMessage();

        assertThat(unknownUserMessage).isEqualTo(wrongPasswordMessage);
    }
}
