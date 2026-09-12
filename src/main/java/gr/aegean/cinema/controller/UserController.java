package gr.aegean.cinema.controller;

import gr.aegean.cinema.dto.user.*;
import gr.aegean.cinema.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoints για το User management module (πλην authentication/logout
 * που βρίσκονται στον {@link AuthController}).
 *
 * Το token αναμένεται πάντα στο header: Authorization: Bearer &lt;token&gt;
 * (επικυρώνεται κεντρικά από τον TokenAuthInterceptor πριν φτάσει εδώ).
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** Δημόσιο endpoint - δεν απαιτεί token. Ο νέος λογαριασμός δημιουργείται ΑΝΕΝΕΡΓΟΣ. */
    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(request));
    }

    /** Επιτρέπεται στον ίδιο τον χρήστη ή σε ADMIN. */
    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(@PathVariable Long id, @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userService.updateUser(id, request));
    }

    /** Επιτρέπεται ΜΟΝΟ στον ίδιο τον χρήστη (απαιτεί τον παλιό κωδικό). */
    @PutMapping("/{id}/password")
    public ResponseEntity<Void> updatePassword(@PathVariable Long id, @Valid @RequestBody UpdatePasswordRequest request) {
        userService.updatePassword(id, request);
        return ResponseEntity.noContent().build();
    }

    /** Επιτρέπεται ΜΟΝΟ σε ADMIN. */
    @PutMapping("/{id}/status")
    public ResponseEntity<UserResponse> updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateStatusRequest request) {
        return ResponseEntity.ok(userService.updateStatus(id, request));
    }

    /** Επιτρέπεται στον ίδιο τον χρήστη ή σε ADMIN. Λογαριασμοί ADMIN δεν διαγράφονται ποτέ. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    /** Βοηθητικό endpoint προβολής προφίλ (self ή ADMIN). */
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUser(id));
    }
}
