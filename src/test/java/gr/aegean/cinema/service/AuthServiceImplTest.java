package gr.aegean.cinema.service;

import gr.aegean.cinema.dto.auth.LoginRequest;
import gr.aegean.cinema.dto.auth.LoginResponse;
import gr.aegean.cinema.exception.AuthenticationException;
import gr.aegean.cinema.model.entity.AuthToken;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.PermanentRole;
import gr.aegean.cinema.repository.UserRepository;
import gr.aegean.cinema.security.AuthorizationService;
import gr.aegean.cinema.security.PasswordUtil;
import gr.aegean.cinema.security.TokenService;
import gr.aegean.cinema.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordUtil passwordUtil;
    @Mock private TokenService tokenService;
    @Mock private AuthorizationService authorizationService;

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    void login_succeedsAndResetsFailedAttemptsCounter() {
        User user = User.builder().id(1L).username("alice1").password("HASHED")
                .permanentRole(PermanentRole.USER).active(true).failedAuthAttempts(2).build();

        AuthToken issued = AuthToken.builder()
                .id(1L).tokenValue("tok-123").user(user)
                .issuedAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusHours(24))
                .revoked(false).build();

        when(userRepository.findByUsername("alice1")).thenReturn(Optional.of(user));
        when(passwordUtil.matches("Correct1!", "HASHED")).thenReturn(true);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokenService.issueToken(user)).thenReturn(issued);

        LoginRequest req = new LoginRequest();
        req.setUsername("alice1");
        req.setPassword("Correct1!");

        LoginResponse response = authService.login(req);

        assertThat(response.getToken()).isEqualTo("tok-123");
        assertThat(user.getFailedAuthAttempts()).isZero();
    }

    @Test
    void login_deactivatesAccountAfterThirdConsecutiveFailure() {
        User user = User.builder().id(1L).username("alice1").password("HASHED")
                .permanentRole(PermanentRole.USER).active(true).failedAuthAttempts(2).build();

        when(userRepository.findByUsername("alice1")).thenReturn(Optional.of(user));
        when(passwordUtil.matches("wrong", "HASHED")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        LoginRequest req = new LoginRequest();
        req.setUsername("alice1");
        req.setPassword("wrong");

        assertThatThrownBy(() -> authService.login(req)).isInstanceOf(AuthenticationException.class);

        assertThat(user.isActive()).isFalse();
        assertThat(user.getFailedAuthAttempts()).isZero();
        verify(tokenService, never()).issueToken(any());
    }

    @Test
    void login_rejectsInactiveAccountEvenWithCorrectPassword() {
        User user = User.builder().id(1L).username("alice1").password("HASHED")
                .permanentRole(PermanentRole.USER).active(false).build();

        when(userRepository.findByUsername("alice1")).thenReturn(Optional.of(user));

        LoginRequest req = new LoginRequest();
        req.setUsername("alice1");
        req.setPassword("Correct1!");

        assertThatThrownBy(() -> authService.login(req)).isInstanceOf(AuthenticationException.class);
        verify(passwordUtil, never()).matches(any(), any());
    }
}
