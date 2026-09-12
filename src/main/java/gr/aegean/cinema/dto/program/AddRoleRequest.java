package gr.aegean.cinema.dto.program;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** Χρησιμοποιείται τόσο για προσθήκη PROGRAMMER όσο και για προσθήκη STAFF σε ένα πρόγραμμα. */
@Getter
@Setter
public class AddRoleRequest {
    @NotBlank(message = "Το username του χρήστη είναι υποχρεωτικό")
    private String username;
}
