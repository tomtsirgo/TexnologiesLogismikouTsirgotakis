package gr.aegean.cinema.dto.user;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateStatusRequest {
    @NotNull(message = "Το πεδίο active είναι υποχρεωτικό")
    private Boolean active;
}
