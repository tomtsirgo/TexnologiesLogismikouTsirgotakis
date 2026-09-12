package gr.aegean.cinema.exception;

import lombok.Getter;

/** 400 - invalid input data. Carries a stable machine-readable code (ASSUMPTIONS.md #19). */
@Getter
public class BadRequestException extends RuntimeException {

    private final String errorCode;

    public BadRequestException(String message) {
        this(message, "BAD_REQUEST");
    }

    public BadRequestException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
}
