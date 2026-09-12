package gr.aegean.cinema.dto.program;

import gr.aegean.cinema.model.enums.ProgramState;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProgramStateUpdateRequest {
    @NotNull(message = "Η επιθυμητή κατάσταση (targetState) είναι υποχρεωτική")
    private ProgramState targetState;
}
