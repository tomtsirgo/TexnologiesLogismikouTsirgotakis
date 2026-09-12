package gr.aegean.cinema.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {
    @NotBlank(message = "Το username είναι υποχρεωτικό")
    private String username;

    @NotBlank(message = "Το password είναι υποχρεωτικό")
    private String password;
}
