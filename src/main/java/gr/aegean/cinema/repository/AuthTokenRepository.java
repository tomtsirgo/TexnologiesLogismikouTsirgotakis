package gr.aegean.cinema.repository;

import gr.aegean.cinema.model.entity.AuthToken;
import gr.aegean.cinema.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {

    /** Used by the TokenAuthInterceptor to resolve which user (if any) owns a bearer token. */
    Optional<AuthToken> findByTokenValue(String tokenValue);

    /**
     * Same lookup, but eagerly fetching the owning user. The interceptor runs
     * outside any transaction and with {@code spring.jpa.open-in-view=false}, so
     * the owner must be materialised inside the query rather than through the
     * LAZY association proxy.
     */
    @Query("select t from AuthToken t join fetch t.user where t.tokenValue = :tokenValue")
    Optional<AuthToken> findByTokenValueWithUser(@Param("tokenValue") String tokenValue);

    /** All currently non-revoked tokens of a user - used to enforce "at most one active token". */
    List<AuthToken> findByUserAndRevokedFalse(User user);

    /** Every token ever issued to a user, revoked or not. */
    List<AuthToken> findByUser(User user);

    /**
     * Physically removes every token row of a user. Called on account deletion,
     * which must remove "the account and all associated tokens" - done explicitly
     * here rather than relying on the database's ON DELETE CASCADE, so the rule
     * holds on every schema (including the Hibernate-generated test schema).
     */
    void deleteByUser(User user);

    long countByUserAndRevokedFalse(User user);
}
