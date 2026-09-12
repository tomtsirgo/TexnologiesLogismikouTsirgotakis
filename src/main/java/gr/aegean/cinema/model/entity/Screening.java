package gr.aegean.cinema.model.entity;

import gr.aegean.cinema.model.enums.ScreeningState;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Οντότητα Προβολής (Screening). Αντιστοιχεί στο entity "Screening" της
 * εκφώνησης. Οι πληροφορίες ταινίας (τίτλος, cast, genres, διάρκεια) και
 * αίθουσας (auditorium) αποθηκεύονται ΑΠΕΥΘΕΙΑΣ μέσα στην εγγραφή του
 * screening (flat fields), όπως ζητά η εκφώνηση, και όχι σε ξεχωριστές
 * οντότητες Film/Auditorium.
 *
 * Ρόλοι που σχετίζονται με ένα screening:
 * - submitter: ο χρήστης που το δημιούργησε (ρόλος SUBMITTER, μόνο γι' αυτό το screening)
 * - handler: το μέλος STAFF που έχει ανατεθεί ως υπεύθυνος προβολής (μπορεί να είναι null
 *   μέχρι να γίνει ανάθεση κατά το ASSIGNMENT του προγράμματος)
 */
@Entity
@Table(name = "screenings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Screening {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

    /** Ο χρήστης που δημιούργησε/υπέβαλε το screening (ρόλος SUBMITTER). Υποχρεωτικό. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submitter_id", nullable = false)
    private User submitter;

    /** Το μέλος STAFF που έχει οριστεί ως handler (projection/stage manager). Αρχικά null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handler_id")
    private User handler;

    @Column(name = "film_title", length = 300)
    private String filmTitle;

    @Column(name = "film_cast", length = 2000)
    private String filmCast;

    /** Genres αποθηκευμένα ως comma-separated κείμενο (π.χ. "Drama,Thriller"). */
    @Column(name = "film_genres", length = 500)
    private String filmGenres;

    @Column(name = "film_duration_minutes")
    private Integer filmDurationMinutes;

    @Column(name = "auditorium_name", length = 200)
    private String auditoriumName;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    /** Δίνεται χειροκίνητα· η διαφορά της με την start_time πρέπει να είναι >= της διάρκειας της ταινίας. */
    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ScreeningState state = ScreeningState.CREATED;

    /** Συμπληρώνεται ΜΟΝΟ κατά τη διαδικασία review (state=REVIEW του προγράμματος). */
    @Column(name = "review_score")
    private Integer reviewScore;

    @Column(name = "review_comments", length = 3000)
    private String reviewComments;

    /** Προαιρετικές παρατηρήσεις που μπαίνουν κατά την έγκριση (approval), για τυχόν τελικές αλλαγές. */
    @Column(name = "approval_notes", length = 2000)
    private String approvalNotes;

    @Column(name = "rejection_reason", length = 2000)
    private String rejectionReason;

    /**
     * Ημερομηνία τελικής υποβολής (final submission). Παραμένει null μέχρι ο
     * SUBMITTER να κάνει final submit κατά το FINAL_SUBMISSION του
     * προγράμματος. Χρησιμοποιείται για να ελεγχθεί αν ένα APPROVED screening
     * μπορεί να γίνει SCHEDULED, καθώς και για τον αυτόματο απορρίψεων ελέγχο
     * κατά τη μετάβαση σε DECISION.
     */
    @Column(name = "final_submission_date")
    private LocalDateTime finalSubmissionDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public boolean isFinallySubmitted() {
        return finalSubmissionDate != null;
    }
}
