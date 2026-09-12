package gr.aegean.cinema.dto.auth;

import gr.aegean.cinema.model.enums.PermanentRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private LocalDateTime expiresAt;
    private Long userId;
    private String username;
    private PermanentRole permanentRole;
}
