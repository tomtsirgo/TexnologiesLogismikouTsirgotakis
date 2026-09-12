package gr.aegean.cinema.model.entity;

import gr.aegean.cinema.model.enums.ProgramRoleType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Οντότητα-σύνδεσμος (join entity) ανάμεσα σε {@link User} και {@link Program},
 * η οποία αναπαριστά τον ρόλο (PROGRAMMER ή STAFF) ενός χρήστη σε ένα
 * συγκεκριμένο πρόγραμμα. Ένας χρήστης μπορεί να έχει ΤΟ ΠΟΛΥ έναν ρόλο ανά
 * πρόγραμμα (unique constraint στο ζεύγος user_id/program_id), αλλά
 * διαφορετικούς ρόλους σε διαφορετικά προγράμματα.
 *
 * Ο ρόλος SUBMITTER δεν μοντελοποιείται εδώ (βλ. {@link Screening#getSubmitter()}).
 */
@Entity
@Table(name = "program_roles", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_program", columnNames = {"user_id", "program_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProgramRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProgramRoleType role;
}
