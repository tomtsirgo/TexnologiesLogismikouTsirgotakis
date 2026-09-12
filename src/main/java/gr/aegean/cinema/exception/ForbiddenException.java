package gr.aegean.cinema.exception;

import lombok.Getter;

/**
 * 403 - the caller is authenticated but is not permitted to perform this action.
 *
 * A plain authorization failure NEVER changes any account's {@code active} flag;
 * only the TOKEN_NOT_OWNER condition does (see
 * {@link gr.aegean.cinema.security.AuthorizationService}).
 */
@Getter
public class ForbiddenException extends RuntimeException {

    private final String errorCode;

    public ForbiddenException(String message) {
        this(message, "FORBIDDEN");
    }

    public ForbiddenException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
}
