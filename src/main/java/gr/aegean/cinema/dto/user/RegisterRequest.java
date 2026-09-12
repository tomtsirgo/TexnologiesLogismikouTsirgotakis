package gr.aegean.cinema.dto.user;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest {
    @NotBlank(message = "Το username είναι υποχρεωτικό")
    private String username;

    @NotBlank(message = "Το password είναι υποχρεωτικό")
    private String password;

    @NotBlank(message = "Το ονοματεπώνυμο είναι υποχρεωτικό")
    private String fullName;
}
