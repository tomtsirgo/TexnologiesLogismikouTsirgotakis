package gr.aegean.cinema.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * An opaque (non-JWT) bearer authentication token issued to a {@link User}.
 *
 * Each successful login/authentication creates a NEW row here (a random UUID
 * string in {@code tokenValue}) and revokes every previously active token of
 * the same user (see {@link gr.aegean.cinema.security.TokenService}), which
 * implements the "successful authentication invalidates any previous token"
 * rule while still persisting tokens as their own database-backed entity
 * (required by the locked tech stack) rather than as a single mutable column
 * on {@code User}.
 *
 * "Invalidating" a token (logout, username change, password change, account
 * deactivation, force-logout, or the valid-token-wrong-owner security
 * penalty) sets {@code revoked=true}; it is never physically deleted except
 * as a side effect of deleting its owning {@link User} (see the
 * {@code ON DELETE CASCADE} foreign key in schema.sql / db/create_tables.sql,
 * which implements "user deletion removes the account and all its tokens").
 */
@Entity
@Table(name = "auth_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Opaque UUID value handed to the client as the bearer token. */
    @Column(name = "token_value", nullable = false, unique = true, length = 100)
    private String tokenValue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    /** 24 hours after issuance by default - see ASSUMPTIONS.md #3. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean revoked = false;

    /** A token is usable only while it is neither revoked nor past its expiry. */
    public boolean isUsable() {
        return !revoked && expiresAt != null && expiresAt.isAfter(LocalDateTime.now());
    }
}
