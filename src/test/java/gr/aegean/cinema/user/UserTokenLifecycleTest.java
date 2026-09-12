package gr.aegean.cinema.user;

import gr.aegean.cinema.model.entity.AuthToken;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.PermanentRole;
import gr.aegean.cinema.repository.AuthTokenRepository;
import gr.aegean.cinema.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * The token lifetime rules themselves: opaque UUID value, 24h TTL
 * (ASSUMPTIONS.md #3), at most one usable token per user, and the difference
 * between REVOKING tokens (logout, password change, deactivation, ...) and
 * DELETING them (account deletion).
 */
class UserTokenLifecycleTest {

    private AuthTokenRepository authTokenRepository;
    private TokenService tokenService;
    private User alice;
    private final List<AuthToken> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        authTokenRepository = mock(AuthTokenRepository.class);
        tokenService = new TokenService(authTokenRepository);
        // The @Value default is applied by Spring at runtime; set it explicitly here
        // because the service is constructed directly, outside the container.
        org.springframework.test.util.ReflectionTestUtils.setField(tokenService, "ttlMinutes", 1440L);

        alice = User.builder().id(3L).username("alice01").password("H").fullName("Alice")
                .permanentRole(PermanentRole.USER).active(true).build();

        stored.clear();
        lenient().when(authTokenRepository.save(any(AuthToken.class))).thenAnswer(inv -> {
            AuthToken t = inv.getArgument(0);
            stored.add(t);
            return t;
        });
        lenient().when(authTokenRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(authTokenRepository.findByUserAndRevokedFalse(alice))
                .thenAnswer(inv -> stored.stream().filter(t -> !t.isRevoked()).toList());
    }

    @Test
    @DisplayName("An issued token is an opaque UUID that expires 24 hours later")
    void issuedTokenIsAnOpaqueUuidValidFor24Hours() {
        LocalDateTime before = LocalDateTime.now();

        AuthToken token = tokenService.issueToken(alice);

        assertThat(token.getTokenValue()).isNotBlank();
        // Opaque: it parses as a UUID and carries no readable claims (unlike a JWT).
        assertThat(UUID.fromString(token.getTokenValue())).isNotNull();
        assertThat(token.getTokenValue()).doesNotContain(".").doesNotContain("alice01");
        assertThat(token.isRevoked()).isFalse();
        assertThat(token.isUsable()).isTrue();
        assertThat(token.getUser()).isSameAs(alice);
        assertThat(token.getExpiresAt()).isAfter(before.plusMinutes(1439))
                .isBefore(before.plusMinutes(1441));
    }

    @Test
    @DisplayName("FR-USR-22: issuing a new token revokes every previously active token of that user")
    void issuingANewTokenRevokesThePreviousOne() {
        AuthToken first = tokenService.issueToken(alice);
        assertThat(first.isRevoked()).isFalse();

        AuthToken second = tokenService.issueToken(alice);

        assertThat(first.isRevoked()).as("the previous token must be invalidated").isTrue();
        assertThat(second.isRevoked()).isFalse();
        assertThat(second.getTokenValue()).isNotEqualTo(first.getTokenValue());
        // Invariant: at most ONE usable token per user at any moment.
        assertThat(stored.stream().filter(AuthToken::isUsable)).hasSize(1);
    }

    @Test
    @DisplayName("Revoking active tokens marks them revoked but keeps the rows")
    void revokeActiveTokensMarksThemRevoked() {
        AuthToken token = tokenService.issueToken(alice);

        tokenService.revokeActiveTokens(alice);

        assertThat(token.isRevoked()).isTrue();
        assertThat(token.isUsable()).isFalse();
        verify(authTokenRepository, never()).deleteByUser(any());
    }

    @Test
    @DisplayName("Revoking when the user holds no active token is a harmless no-op")
    void revokeWithNoActiveTokensIsANoOp() {
        tokenService.revokeActiveTokens(alice);

        verify(authTokenRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("A token past its expires_at is no longer usable, even though it is not revoked")
    void expiredTokenIsNotUsable() {
        AuthToken expired = AuthToken.builder().tokenValue(UUID.randomUUID().toString()).user(alice)
                .issuedAt(LocalDateTime.now().minusHours(48))
                .expiresAt(LocalDateTime.now().minusHours(24))
                .revoked(false).build();

        assertThat(expired.isRevoked()).isFalse();
        assertThat(expired.isUsable()).as("expiry alone makes a token unusable").isFalse();
    }

    @Test
    @DisplayName("revokeToken invalidates exactly the one token named by its value")
    void revokeTokenInvalidatesOnlyThatToken() {
        AuthToken token = AuthToken.builder().id(5L).tokenValue("tok-5").user(alice)
                .issuedAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusHours(1))
                .revoked(false).build();
        when(authTokenRepository.findByTokenValue("tok-5")).thenReturn(Optional.of(token));

        tokenService.revokeToken("tok-5");

        assertThat(token.isRevoked()).isTrue();
        ArgumentCaptor<AuthToken> captor = ArgumentCaptor.forClass(AuthToken.class);
        verify(authTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenValue()).isEqualTo("tok-5");
    }

    @Test
    @DisplayName("FR-USR-19: deleting an account physically removes all of its token rows")
    void deleteAllTokensRemovesTheRows() {
        tokenService.deleteAllTokens(alice);

        verify(authTokenRepository).deleteByUser(alice);
    }

    @Test
    @DisplayName("hasActiveToken reflects revocation")
    void hasActiveTokenReflectsRevocation() {
        tokenService.issueToken(alice);
        assertThat(tokenService.hasActiveToken(alice)).isTrue();

        tokenService.revokeActiveTokens(alice);
        assertThat(tokenService.hasActiveToken(alice)).isFalse();
    }
}
