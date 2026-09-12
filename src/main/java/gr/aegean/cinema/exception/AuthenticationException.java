package gr.aegean.cinema.exception;

import lombok.Getter;

/** 401 - Αποτυχία αυθεντικοποίησης (λάθος credentials ή πρόβλημα token). */
@Getter
public class AuthenticationException extends RuntimeException {
    private final TokenErrorCode errorCode;

    public AuthenticationException(String message, TokenErrorCode errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
}
