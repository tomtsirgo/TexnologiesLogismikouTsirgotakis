package gr.aegean.cinema.service.impl;

import gr.aegean.cinema.dto.auth.LoginRequest;
import gr.aegean.cinema.dto.auth.LoginResponse;
import gr.aegean.cinema.exception.AuthenticationException;
import gr.aegean.cinema.exception.ForbiddenException;
import gr.aegean.cinema.exception.NotFoundException;
import gr.aegean.cinema.exception.TokenErrorCode;
import gr.aegean.cinema.model.entity.AuthToken;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.PermanentRole;
import gr.aegean.cinema.repository.UserRepository;
import gr.aegean.cinema.security.AuditLog;
import gr.aegean.cinema.security.AuthorizationService;
import gr.aegean.cinema.security.PasswordUtil;
import gr.aegean.cinema.security.TokenService;
import gr.aegean.cinema.service.AuthService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication, logout and ADMIN force-logout.
 *
 * Bearer-token validation itself (invalid / expired / not-owner) happens
 * centrally in {@link gr.aegean.cinema.security.TokenAuthInterceptor} before a
 * request ever reaches here.
 *
 * Lockout rule implemented here: three CONSECUTIVE failed authentications
 * deactivate the account (and revoke any token it still holds); a successful
 * authentication resets the counter to zero.
 */
@Service
public class AuthServiceImpl implements AuthService {

    /** Three consecutive failures trigger deactivation (assignment text). */
    public static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final UserRepository userRepository;
    private final PasswordUtil passwordUtil;
    private final TokenService tokenService;
    private final AuthorizationService authorizationService;

    public AuthServiceImpl(UserRepository userRepository, PasswordUtil passwordUtil,
                            TokenService tokenService, AuthorizationService authorizationService) {
        this.userRepository = userRepository;
        this.passwordUtil = passwordUtil;
        this.tokenService = tokenService;
        this.authorizationService = authorizationService;
    }

    /**
     * {@code noRollbackFor}: a failed login is reported by throwing, but the
     * failure-counter increment and the lockout deactivation that precede the
     * throw MUST be committed - otherwise the rollback would silently erase the
     * very state the 3-strike rule depends on.
     */
    @Override
    @Transactional(noRollbackFor = AuthenticationException.class)
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername()).orElse(null);

        // Deliberately never reveal WHICH half of the credentials was wrong.
        if (user == null) {
            AuditLog.authFailure(request.getUsername(), "UNKNOWN_USERNAME", 0);
            throw new AuthenticationException("Wrong credentials (username/password)", TokenErrorCode.AUTH_FAILED);
        }

        if (!user.isActive()) {
            // An inactive account can never authenticate, regardless of the password.
            AuditLog.authFailure(user.getUsername(), "ACCOUNT_INACTIVE", user.getFailedAuthAttempts());
            throw new AuthenticationException("The account is not active", TokenErrorCode.AUTH_FAILED);
        }

        if (!passwordUtil.matches(request.getPassword(), user.getPassword())) {
            user.setFailedAuthAttempts(user.getFailedAuthAttempts() + 1);
            boolean lockedOut = user.getFailedAuthAttempts() >= MAX_CONSECUTIVE_FAILURES;
            AuditLog.authFailure(user.getUsername(), "WRONG_PASSWORD", user.getFailedAuthAttempts());
            if (lockedOut) {
                user.setActive(false);
                // Counter starts fresh once the account is re-activated by an ADMIN.
                user.setFailedAuthAttempts(0);
            }
            userRepository.save(user);
            if (lockedOut) {
                // Deactivation always invalidates the current token, if one exists.
                tokenService.revokeActiveTokens(user);
                AuditLog.lockout(user.getUsername(), user.getId(), "THREE_FAILED_AUTHENTICATIONS");
            }
            throw new AuthenticationException("Wrong credentials (username/password)", TokenErrorCode.AUTH_FAILED);
        }

        // Success: reset the consecutive-failure counter and issue a brand-new
        // token, which revokes every previously issued token of this user.
        user.setFailedAuthAttempts(0);
        user = userRepository.save(user);
        AuthToken token = tokenService.issueToken(user);
        AuditLog.authSuccess(user.getUsername(), user.getId());

        return LoginResponse.builder()
                .token(token.getTokenValue())
                .expiresAt(token.getExpiresAt())
                .userId(user.getId())
                .username(user.getUsername())
                .permanentRole(user.getPermanentRole())
                .build();
    }

    @Override
    @Transactional
    public void logout() {
        User current = authorizationService.requireAuthenticated();
        tokenService.revokeActiveTokens(current);
        AuditLog.logout(current.getUsername(), current.getId());
    }

    @Override
    @Transactional
    public void forceLogout(String targetUsername) {
        User admin = authorizationService.requireAdmin();
        User target = userRepository.findByUsername(targetUsername)
                .orElseThrow(() -> new NotFoundException(
                        "No user found with username " + targetUsername, "USER_NOT_FOUND"));
        if (target.getPermanentRole() == PermanentRole.ADMIN) {
            // Force-logout is scoped to non-ADMIN accounts only (ASSUMPTIONS.md #14).
            AuditLog.accessDenied(admin.getUsername(), "FORCE_LOGOUT:" + targetUsername, "TARGET_IS_ADMIN");
            throw new ForbiddenException(
                    "Force-logout of an ADMIN account is not allowed", "FORCE_LOGOUT_ADMIN_FORBIDDEN");
        }
        tokenService.revokeActiveTokens(target);
        AuditLog.forceLogout(admin.getUsername(), target.getUsername());
    }
}
