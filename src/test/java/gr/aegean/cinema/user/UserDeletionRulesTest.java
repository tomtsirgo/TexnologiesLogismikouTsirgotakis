package gr.aegean.cinema.user;

import gr.aegean.cinema.exception.ForbiddenException;
import gr.aegean.cinema.exception.NotFoundException;
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
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * FR-USR-18 .. FR-USR-20: who may delete an account, what deletion removes, and
 * the absolute "ADMIN accounts are non-deletable" rule.
 */
class UserDeletionRulesTest {

    private UserRepository userRepository;
    private ProgramRoleRepository programRoleRepository;
    private ScreeningRepository screeningRepository;
    private TokenService tokenService;
    private AuthorizationService authorizationService;
    private UserServiceImpl userService;

    private User alice;
    private User admin;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        programRoleRepository = mock(ProgramRoleRepository.class);
        screeningRepository = mock(ScreeningRepository.class);
        tokenService = mock(TokenService.class);
        authorizationService = mock(AuthorizationService.class);
        userService = new UserServiceImpl(userRepository, programRoleRepository, screeningRepository,
                new PasswordUtil(), authorizationService, tokenService);

        alice = User.builder().id(1L).username("alice01").password("H").fullName("Alice")
                .permanentRole(PermanentRole.USER).active(true).build();
        admin = User.builder().id(99L).username("admin1").password("H").fullName("Admin")
                .permanentRole(PermanentRole.ADMIN).active(true).build();

        lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        lenient().when(userRepository.findById(99L)).thenReturn(Optional.of(admin));
        lenient().when(programRoleRepository.findByUser(any(User.class))).thenReturn(List.of());
        lenient().when(screeningRepository.findByHandler(any(User.class))).thenReturn(List.of());
        lenient().when(screeningRepository.findBySubmitter(any(User.class))).thenReturn(List.of());
    }

    @Test
    @DisplayName("FR-USR-18/19: a user may self-delete, and the deletion removes every one of their tokens")
    void selfDeleteRemovesAccountAndAllItsTokens() {
        when(authorizationService.requireSelfOrAdmin(1L)).thenReturn(alice);

        userService.deleteUser(1L);

        // Tokens must be gone BEFORE the user row disappears.
        InOrder order = inOrder(tokenService, userRepository);
        order.verify(tokenService).deleteAllTokens(alice);
        order.verify(userRepository).delete(alice);
    }

    @Test
    @DisplayName("FR-USR-18: an ADMIN may delete somebody else's (non-ADMIN) account")
    void adminMayDeleteAnotherUsersAccount() {
        when(authorizationService.requireSelfOrAdmin(1L)).thenReturn(admin);

        userService.deleteUser(1L);

        verify(tokenService).deleteAllTokens(alice);
        verify(userRepository).delete(alice);
    }

    @Test
    @DisplayName("FR-USR-20: an ADMIN account is never deletable - not even by itself")
    void adminAccountCannotBeSelfDeleted() {
        when(authorizationService.requireSelfOrAdmin(99L)).thenReturn(admin);

        assertThatThrownBy(() -> userService.deleteUser(99L))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_NOT_DELETABLE");

        verify(userRepository, never()).delete(any());
        verify(tokenService, never()).deleteAllTokens(any());
    }

    @Test
    @DisplayName("FR-USR-20: an ADMIN account is not deletable by another ADMIN either")
    void adminAccountCannotBeDeletedByAnotherAdmin() {
        User otherAdmin = User.builder().id(98L).username("admin2").password("H").fullName("Admin Two")
                .permanentRole(PermanentRole.ADMIN).active(true).build();
        when(authorizationService.requireSelfOrAdmin(99L)).thenReturn(otherAdmin);

        assertThatThrownBy(() -> userService.deleteUser(99L))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_NOT_DELETABLE");

        verify(userRepository, never()).delete(any());
    }

    @Test
    @DisplayName("A caller who is neither the owner nor an ADMIN is stopped before anything is touched")
    void unrelatedCallerCannotDelete() {
        when(authorizationService.requireSelfOrAdmin(1L))
                .thenThrow(new ForbiddenException("not allowed", "NOT_SELF_OR_ADMIN"));

        assertThatThrownBy(() -> userService.deleteUser(1L))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_SELF_OR_ADMIN");

        verify(userRepository, never()).delete(any());
        verifyNoInteractions(tokenService);
    }

    @Test
    @DisplayName("Deleting a non-existent account is a 404, not a crash")
    void deletingAnUnknownAccountIsNotFound() {
        when(authorizationService.requireSelfOrAdmin(404L)).thenReturn(admin);
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteUser(404L))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "USER_NOT_FOUND");
    }

    @Test
    @DisplayName("ASSUMPTIONS #9: deletion is not vetoed by the user's cinema-domain links")
    void deletionDetachesCinemaDomainLinksInsteadOfRefusing() {
        when(authorizationService.requireSelfOrAdmin(1L)).thenReturn(alice);
        gr.aegean.cinema.model.entity.ProgramRole role = new gr.aegean.cinema.model.entity.ProgramRole();
        gr.aegean.cinema.model.entity.Screening handled = new gr.aegean.cinema.model.entity.Screening();
        handled.setHandler(alice);
        gr.aegean.cinema.model.entity.Screening submitted = new gr.aegean.cinema.model.entity.Screening();
        submitted.setSubmitter(alice);

        when(programRoleRepository.findByUser(alice)).thenReturn(List.of(role));
        when(screeningRepository.findByHandler(alice)).thenReturn(List.of(handled));
        when(screeningRepository.findBySubmitter(alice)).thenReturn(List.of(submitted));

        userService.deleteUser(1L);

        verify(programRoleRepository).deleteAll(List.of(role));
        verify(screeningRepository).saveAll(List.of(handled));
        verify(screeningRepository).deleteAll(List.of(submitted));
        verify(userRepository).delete(alice);
        org.assertj.core.api.Assertions.assertThat(handled.getHandler())
                .as("a screening this user only handled must survive with an empty handler").isNull();
    }
}
