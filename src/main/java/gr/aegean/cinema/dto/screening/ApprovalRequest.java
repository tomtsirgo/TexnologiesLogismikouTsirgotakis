package gr.aegean.cinema.dto.screening;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApprovalRequest {
    /** Προαιρετικές παρατηρήσεις για τυχόν απαιτούμενες τελικές αλλαγές. */
    private String approvalNotes;
}
