package gr.aegean.cinema.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Επικύρωση προτύπων (patterns) username/password σύμφωνα με τους ΑΚΡΙΒΕΙΣ
 * κανόνες της εκφώνησης, και hashing κωδικών με BCrypt.
 *
 * Username: ξεκινά με γράμμα, μήκος >= 5, επιτρέπονται μόνο γράμματα/ψηφία/underscore.
 * Password: μήκος >= 8, τουλάχιστον 1 κεφαλαίο, 1 πεζό, 1 ψηφίο, 1 ειδικό χαρακτήρα.
 */
@Component
public class PasswordUtil {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{4,}$");

    private static final Pattern PASSWORD_UPPER = Pattern.compile(".*[A-Z].*");
    private static final Pattern PASSWORD_LOWER = Pattern.compile(".*[a-z].*");
    private static final Pattern PASSWORD_DIGIT = Pattern.compile(".*[0-9].*");
    private static final Pattern PASSWORD_SPECIAL = Pattern.compile(".*[^A-Za-z0-9].*");

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public boolean isUsernameValid(String username) {
        return username != null && USERNAME_PATTERN.matcher(username).matches();
    }

    public boolean isPasswordValid(String password) {
        return password != null
                && password.length() >= 8
                && PASSWORD_UPPER.matcher(password).matches()
                && PASSWORD_LOWER.matcher(password).matches()
                && PASSWORD_DIGIT.matcher(password).matches()
                && PASSWORD_SPECIAL.matcher(password).matches();
    }

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String hashedPassword) {
        return encoder.matches(rawPassword, hashedPassword);
    }
}
