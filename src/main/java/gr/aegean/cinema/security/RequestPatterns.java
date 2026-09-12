package gr.aegean.cinema.security;

import org.springframework.util.AntPathMatcher;

import java.util.List;

/**
 * Shared {@code "METHOD /ant/path/pattern"} matching for the cross-cutting
 * request filters (rate limiting, idempotency).
 *
 * <p>Keeping the matching in one helper means the endpoint lists of the two
 * concerns are written in one syntax and read by one implementation, instead of
 * each component re-deriving how to split a pattern.
 */
public final class RequestPatterns {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private RequestPatterns() {
    }

    /** True when {@code method} + {@code path} match any {@code "METHOD /pattern"} entry. */
    public static boolean matchesAny(List<String> patterns, String method, String path) {
        for (String pattern : patterns) {
            String[] parts = pattern.split(" ", 2);
            if (parts.length == 2 && parts[0].equalsIgnoreCase(method) && MATCHER.match(parts[1], path)) {
                return true;
            }
        }
        return false;
    }
}
