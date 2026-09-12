package gr.aegean.cinema.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * The single error-response shape used by EVERY endpoint of the system.
 *
 * DOMAIN_RULES.md ("Cross-cutting") mandates the exact quadruple
 * {@code {timestamp, status, errorCode, message}}; {@code path} and
 * {@code fieldErrors} are purely additive diagnostics that are omitted from
 * the JSON whenever they are null (see {@link JsonInclude}).
 */
@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    private LocalDateTime timestamp;
    private int status;
    /** Stable, machine-readable UPPER_SNAKE_CASE code, one per business rule (ASSUMPTIONS.md #19). */
    private String errorCode;
    private String message;
    private String path;
    /** Populated only for Bean Validation failures: field -> message. */
    private Map<String, String> fieldErrors;
}
