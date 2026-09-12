package gr.aegean.cinema.security;

import gr.aegean.cinema.exception.AuthenticationException;
import gr.aegean.cinema.exception.TokenErrorCode;
import gr.aegean.cinema.model.entity.AuthToken;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.repository.AuthTokenRepository;
import gr.aegean.cinema.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The single token-validation point of the system. It runs before EVERY
 * controller method except registration and authentication, implementing:
 * "for every service function call (apart from authentication) the user token
 * must be validated (valid, not expired, belongs to the requester). Upon
 * failure a specific error must be returned depending on the actual case
 * (invalid/expired/not owner)."
 *
 * <h2>The three distinct failure codes</h2>
 * <ul>
 *   <li>{@link TokenErrorCode#TOKEN_INVALID} - the presented value matches no
 *       issued token (unknown or malformed), or the Authorization header is not
 *       a well-formed {@code Bearer <uuid>}.</li>
 *   <li>{@link TokenErrorCode#TOKEN_EXPIRED} - the token exists but is past its
 *       {@code expires_at}, or has been revoked (logout, password/username
 *       change, deactivation, force-logout, re-authentication).</li>
 *   <li>{@link TokenErrorCode#TOKEN_NOT_OWNER} - the token is live and valid but
 *       belongs to a different user than the one the request declares itself to
 *       be. This, and only this, triggers the double-deactivation penalty (see
 *       {@link AuthorizationService#punishTokenOwnershipViolation}).</li>
 * </ul>
 *
 * <h2>How "the requester" is identified independently of the token</h2>
 * The token alone cannot disagree with itself, so the request must state who it
 * claims to be for a mismatch to be detectable at all. The caller does that with
 * the optional {@code X-Requester-Username} (or {@code X-Requester-Id}) header.
 * When the header is absent the request simply acts as the token's owner and no
 * ownership check is possible or needed; when it is present and names somebody
 * else, the token is being used by a non-owner and the penalty fires. Asking for
 * another user's resource with one's OWN token is NOT this case - that is an
 * ordinary 403 decided later, in the service layer. See ASSUMPTIONS.md #25.
 *
 * <h2>Anonymous access</h2>
 * Public browsing endpoints are listed in {@link #OPTIONAL_AUTH_PATTERNS}: with
 * no Authorization header at all they proceed as an anonymous VISITOR rather
 * than failing, which is what makes VISITOR search/view work with no token.
 */
@Component
public class TokenAuthInterceptor implements HandlerInterceptor {

    /** Header by which a request explicitly declares which account it acts as. */
    public static final String REQUESTER_USERNAME_HEADER = "X-Requester-Username";
    /** Numeric-id alternative to {@link #REQUESTER_USERNAME_HEADER}. */
    public static final String REQUESTER_ID_HEADER = "X-Requester-Id";

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** Endpoints where NO token is required at all: registration and authentication only. */
    private static final List<String> NO_AUTH_PATTERNS = List.of(
            "POST /api/users/register",
            "POST /api/auth/login"
    );

    /** Public browsing endpoints: the token is OPTIONAL (anonymous VISITOR if absent). */
    private static final List<String> OPTIONAL_AUTH_PATTERNS = List.of(
            "GET /api/programs",
            "GET /api/programs/*",
            "GET /api/programs/*/screenings",
            "GET /api/screenings",
            "GET /api/screenings/*"
    );

    private final AuthTokenRepository authTokenRepository;
    private final UserRepository userRepository;
    private final AuthorizationService authorizationService;

    public TokenAuthInterceptor(AuthTokenRepository authTokenRepository,
                                UserRepository userRepository,
                                AuthorizationService authorizationService) {
        this.authTokenRepository = authTokenRepository;
        this.userRepository = userRepository;
        this.authorizationService = authorizationService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        String path = request.getRequestURI();

        if (matchesAny(NO_AUTH_PATTERNS, method, path)) {
            return true;
        }

        String header = request.getHeader("Authorization");
        boolean optional = matchesAny(OPTIONAL_AUTH_PATTERNS, method, path);

        if (header == null || header.isBlank()) {
            if (optional) {
                return true; // anonymous VISITOR
            }
            AuditLog.tokenRejected(TokenErrorCode.TOKEN_MISSING.name(), method, path, null);
            throw new AuthenticationException(
                    "Missing authentication token (Authorization: Bearer <token>)", TokenErrorCode.TOKEN_MISSING);
        }

        String tokenValue = extractBearerValue(header);
        if (tokenValue == null) {
            // A header was sent but it is not a well-formed "Bearer <value>" - malformed, not missing.
            AuditLog.tokenRejected(TokenErrorCode.TOKEN_INVALID.name(), method, path, header);
            throw new AuthenticationException(
                    "Malformed Authorization header; expected 'Bearer <token>'", TokenErrorCode.TOKEN_INVALID);
        }

        AuthToken authToken = authTokenRepository.findByTokenValueWithUser(tokenValue).orElse(null);
        if (authToken == null) {
            AuditLog.tokenRejected(TokenErrorCode.TOKEN_INVALID.name(), method, path, tokenValue);
            throw new AuthenticationException("Unknown or invalid token", TokenErrorCode.TOKEN_INVALID);
        }

        if (authToken.isRevoked()
                || authToken.getExpiresAt() == null
                || !authToken.getExpiresAt().isAfter(LocalDateTime.now())) {
            AuditLog.tokenRejected(TokenErrorCode.TOKEN_EXPIRED.name(), method, path, tokenValue);
            throw new AuthenticationException(
                    "The token has expired or has been invalidated", TokenErrorCode.TOKEN_EXPIRED);
        }

        User owner = authToken.getUser();

        // The token is structurally valid, live and non-revoked: only now can the
        // "belongs to the requester" part of the rule be evaluated.
        String claimedIdentity = claimedIdentity(request);
        if (claimedIdentity != null && !identifies(owner, claimedIdentity)) {
            AuditLog.tokenRejected(TokenErrorCode.TOKEN_NOT_OWNER.name(), method, path, tokenValue);
            User claimedRequester = resolveClaimed(claimedIdentity);
            // The penalty is committed first and the exception thrown afterwards, so the
            // deactivations are never rolled back by the exception that reports them.
            throw authorizationService.punishTokenOwnershipViolation(owner, claimedRequester, claimedIdentity);
        }

        CurrentUserContext.set(owner);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // ALWAYS clear the ThreadLocal so it never leaks into another request served by the same thread.
        CurrentUserContext.clear();
    }

    private String claimedIdentity(HttpServletRequest request) {
        String username = trimToNull(request.getHeader(REQUESTER_USERNAME_HEADER));
        if (username != null) {
            return username;
        }
        return trimToNull(request.getHeader(REQUESTER_ID_HEADER));
    }

    /** True when the claimed identity (username or numeric id) denotes the token's owner. */
    private boolean identifies(User owner, String claimedIdentity) {
        if (claimedIdentity.equals(owner.getUsername())) {
            return true;
        }
        return owner.getId() != null && claimedIdentity.equals(String.valueOf(owner.getId()));
    }

    private User resolveClaimed(String claimedIdentity) {
        User byUsername = userRepository.findByUsername(claimedIdentity).orElse(null);
        if (byUsername != null) {
            return byUsername;
        }
        try {
            return userRepository.findById(Long.valueOf(claimedIdentity)).orElse(null);
        } catch (NumberFormatException ex) {
            return null; // the claimed identity matches no account at all
        }
    }

    private String extractBearerValue(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return null;
        }
        String token = header.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean matchesAny(List<String> patterns, String method, String path) {
        for (String p : patterns) {
            String[] parts = p.split(" ", 2);
            if (!parts[0].equalsIgnoreCase(method)) {
                continue;
            }
            if (MATCHER.match(parts[1], path)) {
                return true;
            }
        }
        return false;
    }
}
