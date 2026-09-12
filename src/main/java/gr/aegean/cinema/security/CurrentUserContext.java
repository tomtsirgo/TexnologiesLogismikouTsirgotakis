package gr.aegean.cinema.security;

import gr.aegean.cinema.model.entity.User;

/**
 * Κρατά τον αυθεντικοποιημένο χρήστη (αν υπάρχει) του τρέχοντος HTTP request,
 * σε ThreadLocal μεταβλητή. Ο {@link TokenAuthInterceptor} τη γεμίζει στο
 * preHandle() και ΠΑΝΤΑ την καθαρίζει στο afterCompletion() (ώστε να μην
 * "διαρρεύσει" τιμή σε επόμενο request που εξυπηρετείται από το ίδιο thread
 * -- π.χ. σε thread pool του Tomcat).
 *
 * Αν δεν υπάρχει αυθεντικοποιημένος χρήστης (π.χ. ανώνυμος VISITOR σε public
 * endpoint), η τιμή είναι null.
 */
public final class CurrentUserContext {

    private static final ThreadLocal<User> CURRENT_USER = new ThreadLocal<>();

    private CurrentUserContext() {
    }

    public static void set(User user) {
        CURRENT_USER.set(user);
    }

    public static User get() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }

    public static boolean isAuthenticated() {
        return CURRENT_USER.get() != null;
    }
}
