package gr.aegean.cinema.dto.screening;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Όλα τα πεδία (πλην του programId που έρχεται από το path) είναι προαιρετικά κατά τη δημιουργία
 *  και συμπληρώνονται σταδιακά μέσω update, μέχρι το screening να είναι "complete" και να μπορεί να υποβληθεί. */
@Getter
@Setter
public class ScreeningCreateRequest {
    private String filmTitle;
    private String filmCast;
    private String filmGenres;
    private Integer filmDurationMinutes;
    private String auditoriumName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
