package gr.aegean.cinema.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

/**
 * The storing half of the {@code Idempotency-Key} mechanism (NFR-05).
 *
 * <p>A {@link jakarta.servlet.Filter} is used — rather than another interceptor
 * — for one reason: capturing what was actually sent to the client requires
 * wrapping the {@link HttpServletResponse}, and an interceptor runs inside the
 * DispatcherServlet, too late to replace the response object. So this filter
 * wraps the response for the handful of endpoints that honour the header,
 * and after the request has been served it remembers the response bytes under
 * the cache key {@link IdempotencyInterceptor} left behind.
 *
 * <p>Only successful (2xx) responses are remembered, which is what
 * DOMAIN_RULES.md's wording requires: it is a <em>successfully executed</em>
 * function that must not run twice. A failed attempt leaves no trace, so the
 * client may retry the same key after fixing the request.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    private final IdempotencyStore store;

    public IdempotencyFilter(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        boolean candidate = IdempotencyStore.isIdempotentEndpoint(request.getMethod(), request.getRequestURI())
                && hasKey(request);
        if (!candidate) {
            chain.doFilter(request, response);
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapper);
            rememberIfFirstSuccess(request, wrapper);
        } finally {
            // Whatever happened, the buffered bytes must reach the real response.
            wrapper.copyBodyToResponse();
        }
    }

    /**
     * The attribute is set by {@link IdempotencyInterceptor} only on a cache
     * MISS, so a replayed response is never re-stored and a request whose token
     * validation failed (the interceptor never ran) is never stored at all.
     */
    private void rememberIfFirstSuccess(HttpServletRequest request, ContentCachingResponseWrapper wrapper) {
        Object cacheKey = request.getAttribute(IdempotencyInterceptor.CACHE_KEY_ATTRIBUTE);
        if (!(cacheKey instanceof String key)) {
            return;
        }
        int status = wrapper.getStatus();
        if (status < 200 || status >= 300) {
            return; // only a SUCCESSFULLY executed function is made non-repeatable
        }
        store.remember(key, status, wrapper.getContentType(), wrapper.getContentAsByteArray());
    }

    private boolean hasKey(HttpServletRequest request) {
        String value = request.getHeader(IdempotencyStore.HEADER);
        return value != null && !value.trim().isEmpty();
    }
}
