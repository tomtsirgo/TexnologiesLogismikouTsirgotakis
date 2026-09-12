package gr.aegean.cinema.dto.screening;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * FR-SCR-18: handler assignment names EXACTLY ONE STAFF member, so the payload
 * carries a single username and not a collection.
 */
@Getter
@Setter
public class HandlerAssignRequest {
    @NotBlank(message = "The username of the STAFF member is required")
    private String staffUsername;
}
