package gr.aegean.cinema.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The in-memory token bucket DOMAIN_RULES.md asks for, with no new dependency:
 * one bucket per (endpoint group, caller) pair, refilled continuously rather
 * than in fixed windows.
 *
 * <h2>The two buckets (ASSUMPTIONS.md #4)</h2>
 * <ul>
 *   <li>{@link Bucket#SUBMISSION} — screening submission, <b>5 requests per
 *       minute</b> per user;</li>
 *   <li>{@link Bucket#SEARCH} — the search endpoints, <b>30 requests per
 *       minute</b> per caller (per authenticated user, or per client IP for an
 *       anonymous VISITOR).</li>
 * </ul>
 * Both numbers are configurable ({@code app.rate-limit.submission-per-minute},
 * {@code app.rate-limit.search-per-minute}) and the whole mechanism can be
 * switched off with {@code app.rate-limit.enabled=false}, which is what the test
 * profile does so that the M2–M4 regression suites — which legitimately fire
 * many submissions per minute under the same fixed usernames — are not throttled
 * by a cross-cutting concern they are not testing (ASSUMPTIONS.md #42).
 *
 * <h2>Why a token bucket and not a fixed window</h2>
 * A bucket of capacity {@code N} refilled at {@code N} tokens per minute allows
 * a burst of up to {@code N} back-to-back requests and then admits one further
 * request every {@code 60/N} seconds. A fixed window would instead let a caller
 * spend {@code N} requests at the end of one window and {@code N} more at the
 * start of the next — twice the intended rate across the boundary.
 *
 * <p>State is per-JVM and deliberately not persisted: DOMAIN_RULES.md specifies
 * an <em>in-memory</em> limiter.
 */
@Component
public class RateLimiter {

    /** The endpoint groups that carry their own allowance. */
    public enum Bucket {
        /** {@code POST /api/screenings/{id}/submit}. */
        SUBMISSION,
        /** The program and screening search endpoints. */
        SEARCH
    }

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final double SECONDS_PER_MINUTE = 60.0d;

    private final boolean enabled;
    private final int submissionPerMinute;
    private final int searchPerMinute;
    private final LongSupplier nanoTime;

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /** The constructor Spring uses; explicitly annotated because a second, test-only one exists. */
    @Autowired
    public RateLimiter(@Value("${app.rate-limit.enabled:true}") boolean enabled,
                       @Value("${app.rate-limit.submission-per-minute:5}") int submissionPerMinute,
                       @Value("${app.rate-limit.search-per-minute:30}") int searchPerMinute) {
        this(enabled, submissionPerMinute, searchPerMinute, System::nanoTime);
    }

    /**
     * Test seam: an injectable clock, so the refill behaviour can be proven
     * without any test ever sleeping for a minute.
     */
    public RateLimiter(boolean enabled, int submissionPerMinute, int searchPerMinute, LongSupplier nanoTime) {
        this.enabled = enabled;
        this.submissionPerMinute = submissionPerMinute;
        this.searchPerMinute = searchPerMinute;
        this.nanoTime = nanoTime;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int capacityOf(Bucket bucket) {
        return bucket == Bucket.SUBMISSION ? submissionPerMinute : searchPerMinute;
    }

    /**
     * Consumes one token for {@code identity} from {@code bucket}.
     *
     * @return {@code true} when the request is allowed to proceed, {@code false}
     *         when the caller has exhausted the allowance and must be rejected
     *         with 429 {@code RATE_LIMITED}
     */
    public boolean tryConsume(Bucket bucket, String identity) {
        if (!enabled) {
            return true;
        }
        int capacity = capacityOf(bucket);
        if (capacity <= 0) {
            return false;
        }
        TokenBucket tokenBucket = buckets.computeIfAbsent(
                bucket.name() + '|' + identity, key -> new TokenBucket(capacity, nanoTime.getAsLong()));
        return tokenBucket.tryConsume(capacity, nanoTime.getAsLong());
    }

    /**
     * Drops every bucket. Used only between tests, so that one test class's
     * traffic never counts against another's allowance.
     */
    public void reset() {
        buckets.clear();
    }

    /**
     * One caller's bucket. Access is synchronized on the instance rather than
     * guarded by a compare-and-set loop, because the critical section is a
     * handful of arithmetic operations and contention is per-caller, not global.
     */
    private static final class TokenBucket {

        private double tokens;
        private long lastRefillNanos;

        private TokenBucket(int capacity, long nowNanos) {
            this.tokens = capacity;
            this.lastRefillNanos = nowNanos;
        }

        private synchronized boolean tryConsume(int capacity, long nowNanos) {
            refill(capacity, nowNanos);
            if (tokens >= 1.0d) {
                tokens -= 1.0d;
                return true;
            }
            return false;
        }

        /** Continuous refill: {@code capacity} tokens are restored over one minute. */
        private void refill(int capacity, long nowNanos) {
            long elapsedNanos = nowNanos - lastRefillNanos;
            if (elapsedNanos <= 0) {
                return;
            }
            double elapsedSeconds = (double) elapsedNanos / NANOS_PER_SECOND;
            tokens = Math.min(capacity, tokens + elapsedSeconds * (capacity / SECONDS_PER_MINUTE));
            lastRefillNanos = nowNanos;
        }
    }
}
