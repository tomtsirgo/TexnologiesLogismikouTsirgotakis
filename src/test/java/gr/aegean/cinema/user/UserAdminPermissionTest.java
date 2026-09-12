package gr.aegean.cinema.user;

import gr.aegean.cinema.dto.user.UpdateStatusRequest;
import gr.aegean.cinema.dto.user.UserResponse;
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
import gr.aegean.cinema.service.impl.AuthServiceImpl;
import gr.aegean.cinema.service.impl.UserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ROLE-14 / ROLE-15 / ROLE-16 and FR-USR-13/14/24/25/26: what ADMIN may do
 * (user management, force-logout) and — just as importantly — what ADMIN may
 * NOT do (anything in the cinema domain).
 */
class UserAdminPermissionTest {

    private UserRepository userRepository;
    private TokenService tokenService;
    private AuthorizationService authorizationService;
    private UserServiceImpl userService;
    private AuthServiceImpl authService;

    private User admin;
    private User alice;
    private User otherAdmin;

    @BeforeEach
    void setUp() {
        CurrentUserContext.clear();
        userRepository = mock(UserRepository.class);
        tokenService = mock(TokenService.class);
        authorizationService = new AuthorizationService(
                mock(ProgramRoleRepository.class), userRepository, tokenService);
        userService = new UserServiceImpl(userRepository, mock(ProgramRoleRepository.class),
                mock(ScreeningRepository.class), new PasswordUtil(), authorizationService, tokenService);
        authService = new AuthServiceImpl(userRepository, mock(PasswordUtil.class), tokenService,
                authorizationService);

        admin = User.builder().id(99L).username("admin1").password("H").fullName("Admin")
                .permanentRole(PermanentRole.ADMIN).active(true).build();
        otherAdmin = User.builder().id(98L).username("admin2").password("H").fullName("Admin Two")
                .permanentRole(PermanentRole.ADMIN).active(true).build();
        alice = User.builder().id(1L).username("alice01").password("H").fullName("Alice")
                .permanentRole(PermanentRole.USER).active(true)
                .failedAuthAttempts(2).failedPasswordAttempts(2).build();

        lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        lenient().when(userRepository.findByUsername("alice01")).thenReturn(Optional.of(alice));
        lenient().when(userRepository.findByUsername("admin2")).thenReturn(Optional.of(otherAdmin));
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
    }

    private static UpdateStatusRequest status(boolean active) {
        UpdateStatusRequest req = new UpdateStatusRequest();
        req.setActive(active);
        return req;
    }

    // ------------------------------------------------------------------
    // ROLE-15 / ROLE-16: ADMIN is user management ONLY
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ROLE-15/16: ADMIN is rejected with 403 from every program and screening management action")
    void adminIsRejectedFromCinemaManagement() {
        CurrentUserContext.set(admin);

        assertThatThrownBy(() -> authorizationService.requireCinemaActor())
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_NOT_ALLOWED");
    }

    @Test
    @DisplayName("A plain USER passes the same cinema-actor gate that stops ADMIN")
    void plainUserPassesTheCinemaActorGate() {
        CurrentUserContext.set(alice);

        assertThat(authorizationService.requireCinemaActor()).isSameAs(alice);
    }

    @Test
    @DisplayName("ROLE-14: a non-ADMIN is rejected from the ADMIN-only gate")
    void nonAdminIsRejectedFromAdminOnlyActions() {
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> authorizationService.requireAdmin())
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_ONLY");
    }

    // ------------------------------------------------------------------
    // FR-USR-13 / FR-USR-14: account status update
    // ------------------------------------------------------------------

    @Test
    @DisplayName("FR-USR-13/14: an ADMIN deactivation invalidates the target's current token")
    void adminDeactivationInvalidatesTheTargetToken() {
        CurrentUserContext.set(admin);

        UserResponse response = userService.updateStatus(1L, status(false));

        assertThat(response.isActive()).isFalse();
        assertThat(alice.isActive()).isFalse();
        verify(tokenService).revokeActiveTokens(alice);
    }

    @Test
    @DisplayName("ADMIN activation clears both consecutive-failure counters and revokes nothing")
    void adminActivationResetsFailureCounters() {
        alice.setActive(false);
        CurrentUserContext.set(admin);

        UserResponse response = userService.updateStatus(1L, status(true));

        assertThat(response.isActive()).isTrue();
        assertThat(alice.getFailedAuthAttempts()).isZero();
        assertThat(alice.getFailedPasswordAttempts()).isZero();
        verify(tokenService, never()).revokeActiveTokens(any());
    }

    @Test
    @DisplayName("FR-USR-13: a non-ADMIN cannot change any account's status, not even their own")
    void nonAdminCannotChangeAccountStatus() {
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> userService.updateStatus(1L, status(false)))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_ONLY");

        assertThat(alice.isActive()).isTrue();
    }

    @Test
    @DisplayName("Changing the status of an unknown account is a 404")
    void statusUpdateOfUnknownAccountIsNotFound() {
        CurrentUserContext.set(admin);
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateStatus(404L, status(true)))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------
    // FR-USR-24 / FR-USR-25 / FR-USR-26: logout and force-logout
    // ------------------------------------------------------------------

    @Test
    @DisplayName("FR-USR-24: logout invalidates the caller's own token")
    void logoutInvalidatesOwnToken() {
        CurrentUserContext.set(alice);

        authService.logout();

        verify(tokenService).revokeActiveTokens(alice);
    }

    @Test
    @DisplayName("Logout requires an authenticated caller")
    void logoutRequiresAuthentication() {
        assertThatThrownBy(() -> authService.logout())
                .isInstanceOf(gr.aegean.cinema.exception.AuthenticationException.class);
        verifyNoInteractions(tokenService);
    }

    @Test
    @DisplayName("FR-USR-25: an ADMIN may force-logout a non-ADMIN account")
    void adminMayForceLogoutANonAdminAccount() {
        CurrentUserContext.set(admin);

        authService.forceLogout("alice01");

        verify(tokenService).revokeActiveTokens(alice);
    }

    @Test
    @DisplayName("FR-USR-26: an ADMIN may NOT force-logout another ADMIN account")
    void adminMayNotForceLogoutAnotherAdmin() {
        CurrentUserContext.set(admin);

        assertThatThrownBy(() -> authService.forceLogout("admin2"))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "FORCE_LOGOUT_ADMIN_FORBIDDEN");

        verify(tokenService, never()).revokeActiveTokens(any());
    }

    @Test
    @DisplayName("A non-ADMIN may not force-logout anyone")
    void nonAdminCannotForceLogout() {
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> authService.forceLogout("alice01"))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_ONLY");

        verify(tokenService, never()).revokeActiveTokens(any());
    }

    @Test
    @DisplayName("Force-logout of an unknown username is a 404")
    void forceLogoutOfUnknownUserIsNotFound() {
        CurrentUserContext.set(admin);
        when(userRepository.findByUsername("ghost99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.forceLogout("ghost99"))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "USER_NOT_FOUND");
    }
}
