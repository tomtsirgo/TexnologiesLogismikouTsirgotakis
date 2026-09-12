package gr.aegean.cinema.user;

import gr.aegean.cinema.dto.user.RegisterRequest;
import gr.aegean.cinema.dto.user.UserResponse;
import gr.aegean.cinema.exception.BadRequestException;
import gr.aegean.cinema.exception.ConflictException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * FR-USR-01 .. FR-USR-04: registration stores everything but leaves the account
 * INACTIVE, rejects duplicate usernames with 409, and enforces BOTH pattern
 * rules with 400.
 *
 * Uses the REAL {@link PasswordUtil} (the component that owns the two patterns)
 * so that each individual rule of each pattern is genuinely exercised, rather
 * than stubbed away.
 */
class UserRegistrationValidationTest {

    private UserRepository userRepository;
    private TokenService tokenService;
    private UserServiceImpl userService;
    private final PasswordUtil passwordUtil = new PasswordUtil();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tokenService = mock(TokenService.class);
        userService = new UserServiceImpl(
                userRepository,
                mock(ProgramRoleRepository.class),
                mock(ScreeningRepository.class),
                passwordUtil,
                mock(AuthorizationService.class),
                tokenService);
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(42L);
            }
            return u;
        });
    }

    private static RegisterRequest request(String username, String password) {
        RegisterRequest req = new RegisterRequest();
        req.setUsername(username);
        req.setPassword(password);
        req.setFullName("Test Person");
        return req;
    }

    // ------------------------------------------------------------------
    // FR-USR-03 - username pattern: letter first, >= 5 chars, [A-Za-z0-9_]
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "username \"{0}\" is rejected")
    @ValueSource(strings = {
            "1abcd",        // does not start with a letter (digit)
            "_abcd",        // does not start with a letter (underscore)
            "abcd",         // only 4 characters - shorter than the 5-char minimum
            "a",            // far too short
            "ab_c",         // 4 characters even though the charset is legal
            "alice-01",     // hyphen is not in the allowed character set
            "alice 01",     // space is not in the allowed character set
            "alice.01",     // dot is not in the allowed character set
            "alice@01",     // at-sign is not in the allowed character set
            "alice!",       // exclamation mark is not in the allowed character set
            "alice+01",     // plus sign is not in the allowed character set
            "     "         // whitespace only
    })
    void register_rejectsEveryUsernamePatternViolation(String badUsername) {
        assertThatThrownBy(() -> userService.register(request(badUsername, "Valid123!")))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_USERNAME_PATTERN");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("A blank/null username is rejected rather than crashing")
    void register_rejectsNullUsername() {
        assertThatThrownBy(() -> userService.register(request(null, "Valid123!")))
                .isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest(name = "username \"{0}\" is accepted")
    @ValueSource(strings = {"alice", "alice01", "A_____", "carol_x", "Bob2024", "zzzzz"})
    void register_acceptsValidUsernames(String goodUsername) {
        when(userRepository.existsByUsername(goodUsername)).thenReturn(false);

        UserResponse response = userService.register(request(goodUsername, "Valid123!"));

        assertThat(response.getUsername()).isEqualTo(goodUsername);
    }

    // ------------------------------------------------------------------
    // FR-USR-04 - password pattern: >= 8 chars, upper AND lower AND digit AND special
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "password \"{0}\" is rejected")
    @ValueSource(strings = {
            "Ab1!",         // far below the 8-character minimum
            "Abc123!",      // exactly 7 characters - one short
            "abcdefg1!",    // no upper-case letter
            "ABCDEFG1!",    // no lower-case letter
            "Abcdefgh!",    // no digit
            "Abcdefg1",     // no special character
            "abcdefgh",     // lower-case only
            "ABCDEFGH",     // upper-case only
            "12345678",     // digits only
            "!!!!!!!!",     // special characters only
            "PASSWORD1!"    // upper + digit + special but no lower-case
    })
    void register_rejectsEveryPasswordPatternViolation(String badPassword) {
        when(userRepository.existsByUsername("alice01")).thenReturn(false);

        assertThatThrownBy(() -> userService.register(request("alice01", badPassword)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_PASSWORD_PATTERN");
        verify(userRepository, never()).save(any());
    }

    @ParameterizedTest(name = "password \"{0}\" is accepted")
    @ValueSource(strings = {"Valid123!", "Abcdefg1!", "P@ssw0rdd", "xY9#zzzz", "Aa1!aaaa"})
    void register_acceptsValidPasswords(String goodPassword) {
        when(userRepository.existsByUsername("alice01")).thenReturn(false);

        assertThat(userService.register(request("alice01", goodPassword))).isNotNull();
    }

    // ------------------------------------------------------------------
    // FR-USR-02 / FR-USR-01
    // ------------------------------------------------------------------

    @Test
    @DisplayName("FR-USR-02: a duplicate username is a 409 conflict, checked before the password rules")
    void register_rejectsDuplicateUsernameWithConflict() {
        when(userRepository.existsByUsername("alice01")).thenReturn(true);

        assertThatThrownBy(() -> userService.register(request("alice01", "Valid123!")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "USERNAME_TAKEN");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("FR-USR-01: a successful registration stores everything and leaves the account INACTIVE")
    void register_storesEverythingAndLeavesAccountInactive() {
        when(userRepository.existsByUsername("alice01")).thenReturn(false);

        UserResponse response = userService.register(request("alice01", "Valid123!"));

        assertThat(response.isActive()).as("a new account must start inactive").isFalse();
        assertThat(response.getPermanentRole()).isEqualTo(PermanentRole.USER);
        assertThat(response.getFullName()).isEqualTo("Test Person");

        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getFullName()).isEqualTo("Test Person");
        assertThat(saved.getFailedAuthAttempts()).isZero();
        assertThat(saved.getFailedPasswordAttempts()).isZero();
        // The password is stored hashed, never in clear text.
        assertThat(saved.getPassword()).isNotEqualTo("Valid123!");
        assertThat(passwordUtil.matches("Valid123!", saved.getPassword())).isTrue();
    }

    @Test
    @DisplayName("Registration never issues a token - the account is not usable yet")
    void register_doesNotIssueAToken() {
        when(userRepository.existsByUsername("alice01")).thenReturn(false);

        userService.register(request("alice01", "Valid123!"));

        Mockito.verifyNoInteractions(tokenService);
    }
}
