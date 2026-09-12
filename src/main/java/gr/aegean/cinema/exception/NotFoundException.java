package gr.aegean.cinema.exception;

import lombok.Getter;

/** 404 - the requested entity (user, program, screening) does not exist. */
@Getter
public class NotFoundException extends RuntimeException {

    private final String errorCode;

    public NotFoundException(String message) {
        this(message, "NOT_FOUND");
    }

    public NotFoundException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
}
