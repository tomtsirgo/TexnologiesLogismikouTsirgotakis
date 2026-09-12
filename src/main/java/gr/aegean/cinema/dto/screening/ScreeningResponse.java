package gr.aegean.cinema.dto.screening;

import com.fasterxml.jackson.annotation.JsonInclude;
import gr.aegean.cinema.model.enums.ScreeningState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * The single Screening response DTO. As with
 * {@link gr.aegean.cinema.dto.program.ProgramResponse}, it is filled in exactly
 * two shapes by the one central
 * {@link gr.aegean.cinema.redaction.RedactionService} (FR-RED-02):
 *
 * <ul>
 *   <li><b>Redacted</b> (VISITOR / plain USER / anybody without a stake in this
 *       screening, FR-RED-04): film title, genres, scheduled start time and
 *       auditorium — and nothing else. In particular NOT the lifecycle
 *       {@code state}, which the migrated code leaked here (INVENTORY.md).</li>
 *   <li><b>Full</b> (a PROGRAMMER of the program, the screening's SUBMITTER, or
 *       its assigned STAFF handler — FR-RED-06/07/08): every field.</li>
 * </ul>
 *
 * <p>Every field is nullable and {@link JsonInclude.Include#NON_NULL} is in
 * force, so a redacted response does not merely blank the forbidden fields — it
 * omits them from the JSON entirely. {@link #finallySubmitted} is a boxed
 * {@code Boolean} for exactly that reason: as a primitive it would have
 * serialized as {@code false} in the redacted view and leaked a field FR-RED-04
 * does not allow.
 */
@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScreeningResponse {
    private Long id;
    private Long programId;
    private String filmTitle;
    private String filmCast;
    private String filmGenres;
    private Integer filmDurationMinutes;
    private String auditoriumName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** Full view only: FR-RED-04 does not list the state among the public fields. */
    private ScreeningState state;
    private Integer reviewScore;
    private String reviewComments;
    private String approvalNotes;
    private String rejectionReason;
    private String submitterUsername;
    private String handlerUsername;
    private Boolean finallySubmitted;
}
