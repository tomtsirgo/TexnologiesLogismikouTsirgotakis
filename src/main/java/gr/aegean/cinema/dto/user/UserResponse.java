package gr.aegean.cinema.dto.user;

import gr.aegean.cinema.model.enums.PermanentRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class UserResponse {
    private Long id;
    private String username;
    private String fullName;
    private PermanentRole permanentRole;
    private boolean active;
}
