package gr.aegean.cinema.model.enums;

/**
 * Ο μόνιμος (permanent) ρόλος ενός χρήστη, ανεξάρτητος από οποιοδήποτε
 * συγκεκριμένο πρόγραμμα (season). Κάθε {@link gr.aegean.cinema.model.entity.User}
 * έχει ΑΚΡΙΒΩΣ έναν μόνιμο ρόλο.
 */
public enum PermanentRole {
    USER,
    ADMIN
}
