package gr.aegean.cinema.dto.program;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** Όλα τα πεδία είναι προαιρετικά: ενημερώνεται μόνο ό,τι δίνεται (partial update). */
@Getter
@Setter
public class ProgramUpdateRequest {
    private String name;
    private String description;
    private LocalDate startDate;
    private LocalDate endDate;
}
