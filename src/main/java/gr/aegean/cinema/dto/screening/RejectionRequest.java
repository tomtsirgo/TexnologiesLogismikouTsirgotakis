package gr.aegean.cinema.dto.screening;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * FR-SCR-29: "a rejection reason is recorded in EVERY rejection case, manual or
 * automatic". The {@code @NotBlank} here is the HTTP-level half of that rule;
 * the service refuses a blank reason a second time, so the rule holds even for
 * a caller that bypasses the controller.
 */
@Getter
@Setter
public class RejectionRequest {
    @NotBlank(message = "A rejection reason is required")
    private String reason;
}
