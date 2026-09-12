package gr.aegean.cinema.dto.screening;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Χρησιμοποιείται τόσο στο κανονικό update (state=CREATED) όσο και ως "bundle αλλαγών" στο final submission. */
@Getter
@Setter
public class ScreeningUpdateRequest {
    private String filmTitle;
    private String filmCast;
    private String filmGenres;
    private Integer filmDurationMinutes;
    private String auditoriumName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
