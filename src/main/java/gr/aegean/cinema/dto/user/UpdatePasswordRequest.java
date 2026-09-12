package gr.aegean.cinema.dto.user;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdatePasswordRequest {
    @NotBlank(message = "Ο παλιός κωδικός είναι υποχρεωτικός")
    private String oldPassword;

    @NotBlank(message = "Ο νέος κωδικός είναι υποχρεωτικός")
    private String newPassword;

    @NotBlank(message = "Η επιβεβαίωση του νέου κωδικού είναι υποχρεωτική")
    private String newPasswordRepeat;
}
