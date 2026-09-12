package gr.aegean.cinema.model.entity;

import gr.aegean.cinema.model.enums.ProgramState;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Οντότητα Προγράμματος (Cinema Season). Αντιστοιχεί στο entity "Program" της
 * εκφώνησης. Ένα πρόγραμμα έχει μοναδικό όνομα, μία {@link ProgramState} και
 * συνδέεται με τα screenings του καθώς και με τους χρήστες που έχουν ρόλο
 * PROGRAMMER ή STAFF σε αυτό (μέσω {@link ProgramRole}).
 */
@Entity
@Table(name = "programs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Program {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 200)
    private String name;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private ProgramState state = ProgramState.CREATED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Ο χρήστης που δημιούργησε το πρόγραμμα. Γίνεται αυτόματα PROGRAMMER
     * (FR-PRG-06) και ΔΕΝ μπορεί ΠΟΤΕ να αφαιρεθεί από το σύνολο των
     * PROGRAMMERS (FR-PRG-08), οπότε η ταυτότητά του πρέπει να είναι μόνιμα
     * αποθηκευμένη και όχι συμπερασματική από τη σειρά των {@link ProgramRole}.
     *
     * Σκόπιμα EAGER (single-valued, φθηνό join): το πεδίο διαβάζεται και από
     * μεθόδους που δεν είναι @Transactional (view/search) ενώ ισχύει
     * {@code spring.jpa.open-in-view=false}.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User creator;

    @OneToMany(mappedBy = "program", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ProgramRole> programRoles = new ArrayList<>();

    @OneToMany(mappedBy = "program", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Screening> screenings = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
