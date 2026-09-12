package gr.aegean.cinema.dto.screening;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * FR-SCR-22: a review requires BOTH a numeric score and comments. The score is
 * an integer 1-10 inclusive (ASSUMPTIONS.md #5); anything outside that range is
 * malformed input and is refused with 400 by Bean Validation, never by a
 * state-machine conflict.
 */
@Getter
@Setter
public class ReviewRequest {
    @NotNull(message = "The review score is required")
    @Min(value = 1, message = "The review score must be between 1 and 10")
    @Max(value = 10, message = "The review score must be between 1 and 10")
    private Integer score;

    @NotBlank(message = "The review comments are required")
    private String comments;
}
