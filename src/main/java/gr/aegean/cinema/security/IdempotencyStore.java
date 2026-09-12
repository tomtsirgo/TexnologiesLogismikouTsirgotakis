package gr.aegean.cinema.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-memory cache behind the {@code Idempotency-Key} header
 * (ASSUMPTIONS.md #15), plus the authoritative list of the endpoints the header
 * is honoured on.
 *
 * <p>DOMAIN_RULES.md requires that "a successfully executed non-idempotent
 * function (e.g. screening creation, submission) must not be executable twice
 * with the same effect", without prescribing a mechanism. The mechanism chosen
 * is the conventional one: a client that wants at-most-once execution attaches
 * an {@code Idempotency-Key} of its own choosing; the first successful response
 * for a (caller, method, path, key) tuple is remembered for 24 hours and any
 * later request carrying the same tuple is answered from the cache, without the
 * side effect being executed a second time.
 *
 * <p>The key includes the caller, so two users cannot collide on the same
 * client-chosen string, and the method and path, so one key reused across
 * different operations does not replay the wrong response.
 *
 * <p>Entries are evicted lazily (on lookup, and by a bounded sweep on write),
 * which is adequate for an in-memory, single-JVM store and avoids a background
 * thread.
 */
@Component
public class IdempotencyStore {

    /** The request header a client uses to ask for at-most-once execution. */
    public static final String HEADER = "Idempotency-Key";

    /** Set on a replayed response so a client (and the Postman suite) can see it was not re-executed. */
    public static final String REPLAY_HEADER = "Idempotency-Replayed";

    /**
     * The authoritative list from ASSUMPTIONS.md #15: screening creation,
     * screening submission and program creation. Every one of them has a real,
     * non-idempotent side effect (a new row, or a state transition); no other
     * endpoint does anything a repeat could duplicate — the lifecycle transitions
     * are already self-guarding, since a second attempt finds the screening or
     * program in the state the first one left it and is refused with 409.
     */
    private static final List<String> IDEMPOTENT_PATTERNS = List.of(
            "POST /api/programs",
            "POST /api/programs/*/screenings",
            "POST /api/screenings/*/submit"
    );

    /** ASSUMPTIONS.md #15: a key is remembered for 24 hours. */
    private final Duration ttl;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public IdempotencyStore(@Value("${app.idempotency.ttl-hours:24}") long ttlHours) {
        this.ttl = Duration.ofHours(ttlHours);
    }

    /** True when the {@code Idempotency-Key} header is honoured for this request. */
    public static boolean isIdempotentEndpoint(String method, String path) {
        return RequestPatterns.matchesAny(IDEMPOTENT_PATTERNS, method, path);
    }

    /** The cache key: caller + operation + client-supplied key. */
    public static String cacheKey(String caller, String method, String path, String idempotencyKey) {
        return caller + '|' + method + '|' + path + '|' + idempotencyKey;
    }

    /** The remembered response, or {@code null} when this key has not been used (or has expired). */
    public Entry lookup(String cacheKey) {
        Entry entry = entries.get(cacheKey);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(Instant.now())) {
            entries.remove(cacheKey, entry);
            return null;
        }
        return entry;
    }

    /** Remembers the first successful response for this key. */
    public void remember(String cacheKey, int status, String contentType, byte[] body) {
        purgeExpired();
        entries.put(cacheKey, new Entry(status, contentType, body == null ? new byte[0] : body.clone(),
                Instant.now().plus(ttl)));
    }

    /** Test seam: forget everything, so one test's keys never leak into another's. */
    public void reset() {
        entries.clear();
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        entries.entrySet().removeIf(e -> e.getValue().isExpired(now));
    }

    /** One remembered response: exactly what was sent the first time. */
    public record Entry(int status, String contentType, byte[] body, Instant expiresAt) {

        boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }
}
