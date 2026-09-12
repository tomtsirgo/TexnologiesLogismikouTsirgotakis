package gr.aegean.cinema.security;

import gr.aegean.cinema.model.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * The lookup half of the {@code Idempotency-Key} mechanism (NFR-05).
 *
 * <p>It runs as an interceptor — after {@link TokenAuthInterceptor} — precisely
 * because it needs the authenticated caller: the cache is scoped per user
 * (ASSUMPTIONS.md #15), and {@link CurrentUserContext} is only populated once
 * token validation has succeeded. When a key has already been used for the same
 * caller and operation, the remembered response is written here verbatim and
 * {@code false} is returned, so the controller — and therefore the side effect —
 * is never reached a second time.
 *
 * <p>On a miss it records the cache key as a request attribute; the
 * {@link IdempotencyFilter} that wraps the request picks it up after the
 * response has been produced and stores the body. The work is split across a
 * filter and an interceptor because only a filter can wrap the response to
 * capture its bytes, and only an interceptor sees the authenticated user.
 */
@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

    /** Request attribute carrying the cache key from this interceptor to {@link IdempotencyFilter}. */
    static final String CACHE_KEY_ATTRIBUTE = "gr.aegean.cinema.idempotency.cacheKey";

    private final IdempotencyStore store;

    public IdempotencyInterceptor(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (!IdempotencyStore.isIdempotentEndpoint(method, path)) {
            return true;
        }

        String idempotencyKey = trimToNull(request.getHeader(IdempotencyStore.HEADER));
        if (idempotencyKey == null) {
            // The header is optional: without it the endpoint behaves exactly as before.
            return true;
        }

        String caller = callerIdentity();
        String cacheKey = IdempotencyStore.cacheKey(caller, method, path, idempotencyKey);

        IdempotencyStore.Entry remembered = store.lookup(cacheKey);
        if (remembered != null) {
            AuditLog.idempotentReplay(caller, idempotencyKey, method, path, remembered.status());
            replay(response, remembered);
            return false; // the side effect is NOT executed again
        }

        request.setAttribute(CACHE_KEY_ATTRIBUTE, cacheKey);
        return true;
    }

    /** Writes the first response back byte for byte, with its original status. */
    private void replay(HttpServletResponse response, IdempotencyStore.Entry remembered) throws IOException {
        response.setStatus(remembered.status());
        if (remembered.contentType() != null) {
            response.setContentType(remembered.contentType());
        }
        response.setHeader(IdempotencyStore.REPLAY_HEADER, "true");
        response.setContentLength(remembered.body().length);
        response.getOutputStream().write(remembered.body());
        response.flushBuffer();
    }

    private String callerIdentity() {
        User current = CurrentUserContext.get();
        return current != null ? current.getUsername() : "anonymous";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
