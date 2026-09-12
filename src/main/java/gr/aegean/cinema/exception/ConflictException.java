package gr.aegean.cinema.exception;

import lombok.Getter;

/** 409 - conflict with the current data state (duplicate username, illegal state transition, ...). */
@Getter
public class ConflictException extends RuntimeException {

    private final String errorCode;

    public ConflictException(String message) {
        this(message, "CONFLICT");
    }

    public ConflictException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
}
