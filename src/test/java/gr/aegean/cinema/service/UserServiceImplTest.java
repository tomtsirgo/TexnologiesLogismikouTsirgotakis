package gr.aegean.cinema.service;

import gr.aegean.cinema.dto.user.UpdateUserRequest;
import gr.aegean.cinema.dto.user.UserResponse;
import gr.aegean.cinema.exception.BadRequestException;
import gr.aegean.cinema.exception.ConflictException;
import gr.aegean.cinema.exception.ForbiddenException;
import gr.aegean.cinema.exception.NotFoundException;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.PermanentRole;
import gr.aegean.cinema.repository.ProgramRoleRepository;
import gr.aegean.cinema.repository.ScreeningRepository;
import gr.aegean.cinema.repository.UserRepository;
import gr.aegean.cinema.security.AuthorizationService;
import gr.aegean.cinema.security.CurrentUserContext;
import gr.aegean.cinema.security.PasswordUtil;
import gr.aegean.cinema.security.TokenService;
import gr.aegean.cinema.service.impl.UserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * FR-USR-05 .. FR-USR-08 and FR-USR-16: the general user-information update.
 *
 * The rules under test: any field EXCEPT the password may change; a username
 * change invalidates the current token; only the owner or an ADMIN may do it;
 * an inactive account may not.
 */
class UserServiceImplTest {

    private UserRepository userRepository;
    private TokenService tokenService;
    private AuthorizationService authorizationService;
    private UserServiceImpl userService;
    private final PasswordUtil passwordUtil = new PasswordUtil();

    private User alice;
    private String originalHash;

    @BeforeEach
    void setUp() {
        CurrentUserContext.clear();
        userRepository = mock(UserRepository.class);
        tokenService = mock(TokenService.class);
        authorizationService = mock(AuthorizationService.class);
        userService = new UserServiceImpl(userRepository, mock(ProgramRoleRepository.class),
                mock(ScreeningRepository.class), passwordUtil, authorizationService, tokenService);

        originalHash = passwordUtil.hash("OldPass1!");
        alice = User.builder().id(1L).username("alice01").password(originalHash).fullName("Alice A")
                .permanentRole(PermanentRole.USER).active(true).build();

        lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(authorizationService.requireSelfOrAdmin(1L)).thenReturn(alice);
    }

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
    }

    private static UpdateUserRequest update(String username, String fullName) {
        UpdateUserRequest req = new UpdateUserRequest();
        req.setUsername(username);
        req.setFullName(fullName);
        return req;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("FR-USR-06: a non-username field can be updated on its own, leaving the token alone")
    void updatingOnlyTheFullNameDoesNotTouchTheToken() {
        UserResponse response = userService.updateUser(1L, update(null, "Alice Anderson"));

        assertThat(response.getFullName()).isEqualTo("Alice Anderson");
        assertThat(response.getUsername()).isEqualTo("alice01");
        verify(tokenService, never()).revokeActiveTokens(any());
    }

    @Test
    @DisplayName("FR-USR-07: changing the username invalidates the current token")
    void changingTheUsernameInvalidatesTheCurrentToken() {
        when(userRepository.existsByUsername("alice_new")).thenReturn(false);

        UserResponse response = userService.updateUser(1L, update("alice_new", null));

        assertThat(response.getUsername()).isEqualTo("alice_new");
        verify(tokenService).revokeActiveTokens(alice);
    }

    @Test
    @DisplayName("Re-submitting the SAME username is not a change and does not invalidate the token")
    void resubmittingTheSameUsernameIsNotAChange() {
        userService.updateUser(1L, update("alice01", "Alice A"));

        verify(tokenService, never()).revokeActiveTokens(any());
        verify(userRepository, never()).existsByUsername(any());
    }

    @Test
    @DisplayName("A new username must satisfy the same pattern rule as at registration")
    void aNewUsernameMustSatisfyThePattern() {
        assertThatThrownBy(() -> userService.updateUser(1L, update("bad", null)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_USERNAME_PATTERN");

        assertThat(alice.getUsername()).isEqualTo("alice01");
        verify(tokenService, never()).revokeActiveTokens(any());
    }

    @Test
    @DisplayName("A username already taken by somebody else is a 409")
    void aTakenUsernameIsRejected() {
        when(userRepository.existsByUsername("bob2024")).thenReturn(true);

        assertThatThrownBy(() -> userService.updateUser(1L, update("bob2024", null)))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "USERNAME_TAKEN");

        assertThat(alice.getUsername()).isEqualTo("alice01");
    }

    @Test
    @DisplayName("FR-USR-08: the update request DTO exposes no way to set a password")
    void theUpdateRequestCannotCarryAPassword() {
        // Structural guarantee: the password simply has no setter on this DTO,
        // so it cannot be smuggled through the general update endpoint.
        assertThat(Arrays.stream(UpdateUserRequest.class.getMethods()).map(Method::getName))
                .noneMatch(name -> name.toLowerCase().contains("password"));

        userService.updateUser(1L, update("alice_new", "Alice Anderson"));

        assertThat(alice.getPassword()).as("the stored password hash is untouched").isEqualTo(originalHash);
    }

    @Test
    @DisplayName("FR-USR-05: a caller who is neither the owner nor an ADMIN gets a plain 403")
    void unrelatedCallerCannotUpdateSomebodyElse() {
        when(authorizationService.requireSelfOrAdmin(1L))
                .thenThrow(new ForbiddenException("not allowed", "NOT_SELF_OR_ADMIN"));

        assertThatThrownBy(() -> userService.updateUser(1L, update(null, "Hacked")))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_SELF_OR_ADMIN");

        assertThat(alice.getFullName()).isEqualTo("Alice A");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("FR-USR-16: an inactive account cannot perform a profile change")
    void inactiveAccountCannotUpdateItsProfile() {
        when(authorizationService.requireSelfOrAdmin(1L))
                .thenThrow(new ForbiddenException("inactive", "ACCOUNT_INACTIVE"));

        assertThatThrownBy(() -> userService.updateUser(1L, update(null, "Whatever")))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ACCOUNT_INACTIVE");
    }

    @Test
    @DisplayName("Updating an unknown account is a 404")
    void updatingAnUnknownAccountIsNotFound() {
        when(authorizationService.requireSelfOrAdmin(404L)).thenReturn(alice);
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateUser(404L, update(null, "Nobody")))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "USER_NOT_FOUND");
    }

    @Test
    @DisplayName("The profile view is gated by the same self-or-ADMIN rule")
    void profileViewIsGatedBySelfOrAdmin() {
        UserResponse response = userService.getUser(1L);
        assertThat(response.getUsername()).isEqualTo("alice01");

        when(authorizationService.requireSelfOrAdmin(1L))
                .thenThrow(new ForbiddenException("not allowed", "NOT_SELF_OR_ADMIN"));
        assertThatThrownBy(() -> userService.getUser(1L)).isInstanceOf(ForbiddenException.class);
    }
}
