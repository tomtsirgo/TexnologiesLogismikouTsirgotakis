package gr.aegean.cinema.dto.program;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class ProgramCreateRequest {
    @NotBlank(message = "Το όνομα του προγράμματος είναι υποχρεωτικό")
    private String name;

    @NotBlank(message = "Η περιγραφή είναι υποχρεωτική")
    private String description;

    @NotNull(message = "Η ημερομηνία έναρξης είναι υποχρεωτική")
    private LocalDate startDate;

    @NotNull(message = "Η ημερομηνία λήξης είναι υποχρεωτική")
    private LocalDate endDate;
}
