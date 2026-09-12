package gr.aegean.cinema.model.entity;

import gr.aegean.cinema.model.enums.PermanentRole;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * System user entity. Corresponds to the "User" entity of the assignment.
 *
 * The password is ALWAYS stored hashed (BCrypt) - see
 * {@link gr.aegean.cinema.security.PasswordUtil}.
 *
 * Tokens are NOT stored on this entity: each issued/valid/revoked opaque
 * bearer token is a separate {@link AuthToken} row (see that entity and
 * {@link gr.aegean.cinema.security.TokenService}), owned by this user via a
 * foreign key. This lets the system persist a full token history (issued-at,
 * expires-at, revoked) instead of a single mutable column, while still
 * supporting the "at most one currently-active token per user" behavior
 * (enforced by revoking every prior active token whenever a new one is
 * issued - see {@code TokenService#issueToken}).
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    /** Always stored hashed (BCrypt), never in plain text. */
    @Column(nullable = false)
    private String password;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    /** The user's single permanent role, independent of any program-specific role. */
    @Enumerated(EnumType.STRING)
    @Column(name = "permanent_role", nullable = false, length = 20)
    private PermanentRole permanentRole;

    /** New accounts always start inactive until activated by an ADMIN. */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = false;

    /** Counter of consecutive failed authentication (login) attempts. */
    @Column(name = "failed_auth_attempts", nullable = false)
    @Builder.Default
    private int failedAuthAttempts = 0;

    /** Counter of consecutive failed password-change attempts. */
    @Column(name = "failed_password_attempts", nullable = false)
    @Builder.Default
    private int failedPasswordAttempts = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
