package gr.aegean.cinema.exception;

/**
 * Error codes for the authentication handshake and for bearer-token validation.
 *
 * The assignment demands DISTINCT codes for the three token-validation failure
 * cases ("a specific error must be returned depending on the actual case:
 * invalid/expired/not owner"); {@link #TOKEN_MISSING} separates "no credentials
 * presented at all" from "credentials presented but unrecognised", and
 * {@link #AUTH_FAILED} covers the login handshake itself (ASSUMPTIONS.md #10),
 * which is deliberately NOT one of the token-validation codes.
 */
public enum TokenErrorCode {

    /** No Authorization header (or no Bearer value) on a protected endpoint. */
    TOKEN_MISSING,

    /** The presented token value is unknown/malformed - it matches no issued token. */
    TOKEN_INVALID,

    /** The token exists but is past its expires_at, or has been revoked. */
    TOKEN_EXPIRED,

    /**
     * The token is structurally valid, live and non-revoked, but it belongs to a
     * DIFFERENT user than the one the request claims to act as. This is the only
     * condition that triggers the double-deactivation penalty.
     */
    TOKEN_NOT_OWNER,

    /** Login handshake failure: unknown username, wrong password, or inactive account. */
    AUTH_FAILED
}
