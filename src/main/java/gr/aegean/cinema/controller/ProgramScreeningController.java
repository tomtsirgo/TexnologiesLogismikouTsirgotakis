package gr.aegean.cinema.controller;

import gr.aegean.cinema.dto.screening.ScreeningCreateRequest;
import gr.aegean.cinema.dto.screening.ScreeningResponse;
import gr.aegean.cinema.service.ScreeningService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Ενέργειες screenings που είναι εννοιολογικά "εμφωλευμένες" (nested) μέσα
 * σε ένα συγκεκριμένο πρόγραμμα: δημιουργία νέου screening και αναζήτηση
 * των screenings ΕΝΤΟΣ αυτού του προγράμματος.
 */
@RestController
@RequestMapping("/api/programs/{programId}/screenings")
public class ProgramScreeningController {

    private final ScreeningService screeningService;

    public ProgramScreeningController(ScreeningService screeningService) {
        this.screeningService = screeningService;
    }

    /**
     * FR-SCR-01 … FR-SCR-06 and ROLE-05: any authenticated, active, non-ADMIN
     * account may create a screening inside this program. ROLE-19 excludes a
     * PROGRAMMER of this very program, with 409.
     */
    @PostMapping
    public ResponseEntity<ScreeningResponse> create(@PathVariable Long programId, @RequestBody ScreeningCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(screeningService.createScreening(programId, request));
    }

    /**
     * Αναζήτηση με AND semantics· εντός κάθε text filter απαιτούνται ΟΛΕΣ οι λέξεις (case-insensitive).
     * timetable=true -> ταξινόμηση κατά start_time· αλλιώς κατά genre, μετά τίτλο.
     */
    @GetMapping
    public ResponseEntity<List<ScreeningResponse>> search(
            @PathVariable Long programId,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String cast,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo,
            @RequestParam(required = false, defaultValue = "false") boolean timetable) {
        return ResponseEntity.ok(screeningService.searchScreenings(programId, title, cast, genre, dateFrom, dateTo, timetable));
    }
}
