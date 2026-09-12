package gr.aegean.cinema.user;

import gr.aegean.cinema.dto.user.UpdatePasswordRequest;
import gr.aegean.cinema.exception.BadRequestException;
import gr.aegean.cinema.exception.ForbiddenException;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.PermanentRole;
import gr.aegean.cinema.repository.ProgramRoleRepository;
import gr.aegean.cinema.repository.ScreeningRepository;
import gr.aegean.cinema.repository.UserRepository;
import gr.aegean.cinema.security.AuthorizationService;
import gr.aegean.cinema.security.PasswordUtil;
import gr.aegean.cinema.security.TokenService;
import gr.aegean.cinema.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * FR-USR-09 .. FR-USR-12: the password-change function.
 *
 * Two rules are asserted repeatedly here because they are absolute:
 * the current token is invalidated in EVERY outcome (success and failure), and
 * three consecutive wrong-old-password attempts deactivate the account.
 */
class UserPasswordChangeLockoutTest {

    private static final String OLD_HASH = "HASH_OF_OldPass1!";

    private UserRepository userRepository;
    private TokenService tokenService;
    private AuthorizationService authorizationService;
    private UserServiceImpl userService;
    private final PasswordUtil passwordUtil = new PasswordUtil();
    private User bob;
    private String storedHash;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tokenService = mock(TokenService.class);
        authorizationService = mock(AuthorizationService.class);
        userService = new UserServiceImpl(userRepository, mock(ProgramRoleRepository.class),
                mock(ScreeningRepository.class), passwordUtil, authorizationService, tokenService);

        storedHash = passwordUtil.hash("OldPass1!");
        bob = User.builder().id(9L).username("bob2024").password(storedHash)
                .fullName("Bob").permanentRole(PermanentRole.USER)
                .active(true).failedAuthAttempts(0).failedPasswordAttempts(0).build();

        lenient().when(authorizationService.requireSelf(9L)).thenReturn(bob);
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static UpdatePasswordRequest change(String oldPw, String newPw, String repeat) {
        UpdatePasswordRequest req = new UpdatePasswordRequest();
        req.setOldPassword(oldPw);
        req.setNewPassword(newPw);
        req.setNewPasswordRepeat(repeat);
        return req;
    }

    private void attemptWithWrongOldPassword() {
        assertThatThrownBy(() -> userService.updatePassword(9L, change("NotMyPass1!", "NewPass1!", "NewPass1!")))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "WRONG_OLD_PASSWORD");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("FR-USR-12: three consecutive failed password changes deactivate the account")
    void threeConsecutiveFailedChangesDeactivateTheAccount() {
        attemptWithWrongOldPassword();
        assertThat(bob.isActive()).isTrue();
        assertThat(bob.getFailedPasswordAttempts()).isEqualTo(1);

        attemptWithWrongOldPassword();
        assertThat(bob.isActive()).isTrue();
        assertThat(bob.getFailedPasswordAttempts()).isEqualTo(2);

        attemptWithWrongOldPassword();
        assertThat(bob.isActive()).as("deactivated on the 3rd consecutive failure").isFalse();

        // The token was revoked on each of the three attempts ("in all cases").
        verify(tokenService, times(3)).revokeActiveTokens(bob);
    }

    @Test
    @DisplayName("A successful change resets the consecutive-failure counter")
    void successfulChangeResetsTheCounter() {
        attemptWithWrongOldPassword();
        attemptWithWrongOldPassword();
        assertThat(bob.getFailedPasswordAttempts()).isEqualTo(2);

        userService.updatePassword(9L, change("OldPass1!", "NewPass1!", "NewPass1!"));

        assertThat(bob.getFailedPasswordAttempts()).as("a success breaks the streak").isZero();
        assertThat(bob.isActive()).isTrue();
        assertThat(passwordUtil.matches("NewPass1!", bob.getPassword())).isTrue();

        // A subsequent failure starts a fresh streak instead of tripping the lockout.
        bob.setPassword(storedHash);
        attemptWithWrongOldPassword();
        assertThat(bob.isActive()).isTrue();
        assertThat(bob.getFailedPasswordAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-USR-11: a SUCCESSFUL password change still invalidates the current token")
    void successInvalidatesTheCurrentToken() {
        userService.updatePassword(9L, change("OldPass1!", "NewPass1!", "NewPass1!"));

        verify(tokenService).revokeActiveTokens(bob);
    }

    @Test
    @DisplayName("FR-USR-11: a FAILED password change also invalidates the current token")
    void failureInvalidatesTheCurrentToken() {
        attemptWithWrongOldPassword();

        verify(tokenService).revokeActiveTokens(bob);
    }

    @Test
    @DisplayName("FR-USR-09: the new password must be supplied twice and the two copies must match")
    void confirmationMismatchIsRejectedAndStillInvalidatesTheToken() {
        assertThatThrownBy(() -> userService.updatePassword(9L, change("OldPass1!", "NewPass1!", "Different1!")))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PASSWORD_CONFIRMATION_MISMATCH");

        verify(tokenService).revokeActiveTokens(bob);
        assertThat(passwordUtil.matches("OldPass1!", bob.getPassword()))
                .as("the password must be unchanged").isTrue();
    }

    @Test
    @DisplayName("A new password that violates the password pattern is rejected with 400")
    void weakNewPasswordIsRejectedAndStillInvalidatesTheToken() {
        assertThatThrownBy(() -> userService.updatePassword(9L, change("OldPass1!", "weak", "weak")))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_PASSWORD_PATTERN");

        verify(tokenService).revokeActiveTokens(bob);
        assertThat(passwordUtil.matches("OldPass1!", bob.getPassword())).isTrue();
    }

    @Test
    @DisplayName("ASSUMPTIONS #8: a proven-owner failure (bad confirmation) does NOT advance the lockout counter")
    void confirmationMismatchDoesNotCountTowardsTheLockout() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> userService.updatePassword(9L, change("OldPass1!", "NewPass1!", "Other1!!")))
                    .isInstanceOf(BadRequestException.class);
        }

        assertThat(bob.getFailedPasswordAttempts()).isZero();
        assertThat(bob.isActive()).as("five confirmation mismatches must not lock the account").isTrue();
    }

    @Test
    @DisplayName("FR-USR-10: the password change is refused for anyone but the account owner")
    void onlyTheOwnerMayChangeTheirPassword() {
        when(authorizationService.requireSelf(9L))
                .thenThrow(new ForbiddenException("not the owner", "NOT_SELF"));

        assertThatThrownBy(() -> userService.updatePassword(9L, change("OldPass1!", "NewPass1!", "NewPass1!")))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_SELF");

        verifyNoInteractions(tokenService);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("FR-USR-16: an inactive account is blocked from changing its password")
    void inactiveAccountCannotChangePassword() {
        when(authorizationService.requireSelf(9L))
                .thenThrow(new ForbiddenException("inactive", "ACCOUNT_INACTIVE"));

        assertThatThrownBy(() -> userService.updatePassword(9L, change("OldPass1!", "NewPass1!", "NewPass1!")))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ACCOUNT_INACTIVE");
    }
}
