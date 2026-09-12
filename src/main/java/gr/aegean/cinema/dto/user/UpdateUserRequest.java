package gr.aegean.cinema.dto.user;

import lombok.Getter;
import lombok.Setter;

/** Το password ΔΕΝ αλλάζει εδώ -- έχει ξεχωριστό endpoint/DTO (UpdatePasswordRequest). */
@Getter
@Setter
public class UpdateUserRequest {
    private String username;
    private String fullName;
}
