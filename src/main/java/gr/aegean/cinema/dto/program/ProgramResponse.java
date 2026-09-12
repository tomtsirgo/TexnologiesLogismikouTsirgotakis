package gr.aegean.cinema.dto.program;

import com.fasterxml.jackson.annotation.JsonInclude;
import gr.aegean.cinema.model.enums.ProgramState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The single Program response DTO. It is filled in exactly two shapes, both
 * produced by the one central
 * {@link gr.aegean.cinema.redaction.RedactionService} (FR-RED-01) and never by
 * a controller or by ad hoc code inside a service:
 *
 * <ul>
 *   <li><b>Redacted</b> (VISITOR / plain USER / any non-PROGRAMMER, FR-RED-03):
 *       name, dates, {@link #auditoriums}, description and programmer names
 *       only. Every other field is left null and therefore — thanks to
 *       {@link JsonInclude.Include#NON_NULL} — does not appear in the JSON at
 *       all, which is what "nothing else" in DOMAIN_RULES.md requires. The
 *       {@link #id} is kept because it is the resource's address rather than a
 *       domain field (ASSUMPTIONS.md #41).</li>
 *   <li><b>Full</b> (a PROGRAMMER of this program, FR-RED-05): every field,
 *       including the STAFF set and the lifecycle state.</li>
 * </ul>
 */
@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProgramResponse {
    private Long id;
    private String name;
    private String description;
    private LocalDate startDate;
    private LocalDate endDate;
    /** Full view only: redaction leaves it null (FR-RED-03 does not list the state). */
    private ProgramState state;
    /** Full view only. */
    private LocalDateTime createdAt;
    /** The creator; permanently a PROGRAMMER and never removable (FR-PRG-08). Null when redacted. */
    private String creatorUsername;
    private List<String> programmerUsernames;
    /** Null unless the requester may see the STAFF set (FR-RED-05). */
    private List<String> staffUsernames;
    /**
     * The DERIVED "auditorium" field DOMAIN_RULES.md lists among the fields a
     * VISITOR may see on a program. {@code Program} has no auditorium column;
     * this is computed at redaction time as the distinct, sorted set of
     * auditorium names used by the program's screenings (ASSUMPTIONS.md #7).
     */
    private List<String> auditoriums;
}
