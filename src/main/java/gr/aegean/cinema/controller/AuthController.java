package gr.aegean.cinema.controller;

import gr.aegean.cinema.dto.auth.LoginRequest;
import gr.aegean.cinema.dto.auth.LoginResponse;
import gr.aegean.cinema.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** Δημόσιο endpoint - δεν απαιτεί token. 3 συνεχόμενες αποτυχίες -> deactivation του λογαριασμού. */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /** Αποσύνδεση του τρέχοντος αυθεντικοποιημένου χρήστη (ακυρώνει το δικό του token). */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        authService.logout();
        return ResponseEntity.noContent().build();
    }

    /** Αναγκαστική αποσύνδεση άλλου (μη-ADMIN) χρήστη. Επιτρέπεται μόνο σε ADMIN. */
    @PostMapping("/logout/{username}")
    public ResponseEntity<Void> forceLogout(@PathVariable String username) {
        authService.forceLogout(username);
        return ResponseEntity.noContent().build();
    }
}
