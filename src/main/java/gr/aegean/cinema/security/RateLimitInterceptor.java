package gr.aegean.cinema.security;

import gr.aegean.cinema.exception.RateLimitExceededException;
import gr.aegean.cinema.model.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * Applies the {@link RateLimiter} to the two endpoint groups DOMAIN_RULES.md
 * names: screening submission and the search endpoints.
 *
 * <p>It is a {@link HandlerInterceptor} and not something baked into the service
 * methods on purpose: rate limiting is a property of the HTTP endpoint, not of
 * the business rule, and a service method invoked by another service (or by a
 * test) must not consume somebody's allowance. It is registered AFTER
 * {@link TokenAuthInterceptor} in {@link gr.aegean.cinema.config.WebConfig} so
 * that {@link CurrentUserContext} is already populated and the bucket can be
 * keyed by the authenticated user rather than by the connection.
 *
 * <p>Exceeding the allowance raises {@link RateLimitExceededException}, which
 * {@code GlobalExceptionHandler} renders as HTTP 429 with
 * {@code errorCode=RATE_LIMITED} (ASSUMPTIONS.md #20) — the same
 * {timestamp, status, errorCode, message} envelope as every other error.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    /** DOMAIN_RULES.md: "rate limiting … on screening submission". */
    private static final List<String> SUBMISSION_PATTERNS = List.of(
            "POST /api/screenings/*/submit"
    );

    /**
     * DOMAIN_RULES.md: "… and on search endpoints". The two collection endpoints
     * only: a view-by-id is not a search and carries no filter workload.
     */
    private static final List<String> SEARCH_PATTERNS = List.of(
            "GET /api/programs",
            "GET /api/programs/*/screenings"
    );

    private final RateLimiter rateLimiter;

    public RateLimitInterceptor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        RateLimiter.Bucket bucket = bucketFor(request.getMethod(), request.getRequestURI());
        if (bucket == null) {
            return true;
        }

        String identity = callerIdentity(request);
        if (!rateLimiter.tryConsume(bucket, identity)) {
            AuditLog.rateLimited(identity, bucket.name(), request.getMethod(), request.getRequestURI(),
                    rateLimiter.capacityOf(bucket));
            throw new RateLimitExceededException(
                    "Rate limit exceeded: at most " + rateLimiter.capacityOf(bucket)
                            + " requests per minute are accepted on this endpoint");
        }
        return true;
    }

    private RateLimiter.Bucket bucketFor(String method, String path) {
        if (RequestPatterns.matchesAny(SUBMISSION_PATTERNS, method, path)) {
            return RateLimiter.Bucket.SUBMISSION;
        }
        if (RequestPatterns.matchesAny(SEARCH_PATTERNS, method, path)) {
            return RateLimiter.Bucket.SEARCH;
        }
        return null;
    }

    /**
     * ASSUMPTIONS.md #4: "per authenticated user, or per client IP for an
     * anonymous VISITOR caller". Search is reachable without a token, so an
     * anonymous caller must still be limited somehow, and the connection's
     * address is the only identity such a request has.
     */
    private String callerIdentity(HttpServletRequest request) {
        User current = CurrentUserContext.get();
        if (current != null) {
            return "user:" + current.getUsername();
        }
        String remote = request.getRemoteAddr();
        return "ip:" + (remote == null || remote.isBlank() ? "unknown" : remote);
    }
}
