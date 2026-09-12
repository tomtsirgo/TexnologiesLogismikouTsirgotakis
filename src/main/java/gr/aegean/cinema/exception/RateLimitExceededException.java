package gr.aegean.cinema.exception;

import lombok.Getter;

/**
 * 429 — the caller exceeded the in-memory token-bucket allowance for this
 * endpoint (DOMAIN_RULES.md, "Rate limiting (in-memory token bucket) on
 * screening submission and on search endpoints").
 *
 * <p>ASSUMPTIONS.md #20 records why 429 is used even though DOMAIN_RULES.md's
 * "invalid user input is 400/403/404/409" sentence does not list it: rate
 * limiting is not invalid input at all, and 429 is admitted as a narrow,
 * explicit addition for this one cross-cutting concern. The error code is always
 * {@code RATE_LIMITED}.
 */
@Getter
public class RateLimitExceededException extends RuntimeException {

    /** The single error code this exception ever carries (ASSUMPTIONS.md #20). */
    public static final String ERROR_CODE = "RATE_LIMITED";

    private final String errorCode;

    public RateLimitExceededException(String message) {
        super(message);
        this.errorCode = ERROR_CODE;
    }
}
