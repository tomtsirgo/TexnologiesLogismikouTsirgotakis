package gr.aegean.cinema.model.enums;

/**
 * Ρόλος που έχει ένας χρήστης ΣΕ ΣΧΕΣΗ ΜΕ ΕΝΑ ΣΥΓΚΕΚΡΙΜΕΝΟ πρόγραμμα (season).
 * Αποθηκεύεται στην οντότητα {@link gr.aegean.cinema.model.entity.ProgramRole}.
 * Ένας χρήστης μπορεί να έχει ΤΟ ΠΟΛΥ έναν από αυτούς τους ρόλους ανά πρόγραμμα
 * (π.χ. δεν μπορεί να είναι ταυτόχρονα PROGRAMMER και STAFF στο ίδιο πρόγραμμα).
 * Ο ρόλος SUBMITTER ΔΕΝ αποθηκεύεται εδώ· προκύπτει έμμεσα από το ποιος
 * δημιούργησε ένα συγκεκριμένο screening.
 */
public enum ProgramRoleType {
    PROGRAMMER,
    STAFF
}
