package gr.aegean.cinema.security;

import gr.aegean.cinema.model.entity.AuthToken;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.repository.AuthTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, resolves and revokes the opaque (non-JWT) UUID bearer tokens, which
 * are persisted as {@link AuthToken} rows.
 *
 * Invariant: a user has AT MOST one currently-usable (non-revoked, non-expired)
 * token at any moment, because {@link #issueToken(User)} always revokes every
 * previously active token of the same user first - this is exactly the
 * "successful authentication returns a NEW token and invalidates any previous
 * one" rule.
 *
 * "Invalidate the current token" (logout, username change, password change,
 * deactivation, force-logout, the valid-token-wrong-owner penalty) means
 * {@link #revokeActiveTokens(User)}. Rows are physically deleted only by
 * {@link #deleteAllTokens(User)}, on account deletion.
 *
 * TTL is configurable (app.token.ttl-minutes), default 1440 = 24h
 * (ASSUMPTIONS.md #3).
 */
@Component
public class TokenService {

    @Value("${app.token.ttl-minutes:1440}")
    private long ttlMinutes = 1440;

    private final AuthTokenRepository authTokenRepository;

    public TokenService(AuthTokenRepository authTokenRepository) {
        this.authTokenRepository = authTokenRepository;
    }

    /** Issues a brand-new token for the user, revoking any previously active one first. */
    @Transactional
    public AuthToken issueToken(User user) {
        revokeActiveTokens(user);
        LocalDateTime now = LocalDateTime.now();
        AuthToken token = AuthToken.builder()
                .tokenValue(UUID.randomUUID().toString())
                .user(user)
                .issuedAt(now)
                .expiresAt(now.plusMinutes(ttlMinutes))
                .revoked(false)
                .build();
        return authTokenRepository.save(token);
    }

    /** Revokes every currently non-revoked token belonging to the given user. */
    @Transactional
    public void revokeActiveTokens(User user) {
        List<AuthToken> active = authTokenRepository.findByUserAndRevokedFalse(user);
        if (active.isEmpty()) {
            return;
        }
        for (AuthToken token : active) {
            token.setRevoked(true);
        }
        authTokenRepository.saveAll(active);
    }

    /** Revokes one specific token by its opaque value, if it exists. */
    @Transactional
    public void revokeToken(String tokenValue) {
        authTokenRepository.findByTokenValue(tokenValue).ifPresent(token -> {
            token.setRevoked(true);
            authTokenRepository.save(token);
        });
    }

    /**
     * Physically deletes every token of the user. Used only by account deletion
     * ("deletes the account and all associated tokens").
     */
    @Transactional
    public void deleteAllTokens(User user) {
        authTokenRepository.deleteByUser(user);
    }

    public Optional<AuthToken> findByValue(String tokenValue) {
        return authTokenRepository.findByTokenValue(tokenValue);
    }

    /** True when the user currently holds at least one usable token. */
    public boolean hasActiveToken(User user) {
        return authTokenRepository.findByUserAndRevokedFalse(user).stream().anyMatch(AuthToken::isUsable);
    }

    public long getTtlMinutes() {
        return ttlMinutes;
    }

    /** Package-visible setter used by unit tests that construct the service directly. */
    void setTtlMinutes(long ttlMinutes) {
        this.ttlMinutes = ttlMinutes;
    }
}
