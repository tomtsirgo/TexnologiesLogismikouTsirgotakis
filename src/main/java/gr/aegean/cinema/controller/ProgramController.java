package gr.aegean.cinema.controller;

import gr.aegean.cinema.dto.program.*;
import gr.aegean.cinema.service.ProgramService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * REST endpoints για το Program (Cinema Season) management module.
 * Τα GET endpoints (search/view) υποστηρίζουν ΠΡΟΑΙΡΕΤΙΚΗ αυθεντικοποίηση:
 * λειτουργούν και χωρίς token (VISITOR), αλλά αν δοθεί token πρέπει να είναι
 * έγκυρο (βλ. TokenAuthInterceptor).
 */
@RestController
@RequestMapping("/api/programs")
public class ProgramController {

    private final ProgramService programService;

    public ProgramController(ProgramService programService) {
        this.programService = programService;
    }

    /** Κάθε αυθεντικοποιημένος USER μπορεί να δημιουργήσει πρόγραμμα (και γίνεται αυτόματα PROGRAMMER του). */
    @PostMapping
    public ResponseEntity<ProgramResponse> create(@Valid @RequestBody ProgramCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(programService.createProgram(request));
    }

    /** Μόνο PROGRAMMER του συγκεκριμένου προγράμματος. */
    @PutMapping("/{id}")
    public ResponseEntity<ProgramResponse> update(@PathVariable Long id, @RequestBody ProgramUpdateRequest request) {
        return ResponseEntity.ok(programService.updateProgram(id, request));
    }

    /** Προσθήκη νέου PROGRAMMER στο πρόγραμμα. Μόνο υφιστάμενος PROGRAMMER. */
    @PostMapping("/{id}/programmers")
    public ResponseEntity<ProgramResponse> addProgrammer(@PathVariable Long id, @Valid @RequestBody AddRoleRequest request) {
        return ResponseEntity.ok(programService.addProgrammer(id, request));
    }

    /** Αφαίρεση PROGRAMMER. Ο δημιουργός ΔΕΝ αφαιρείται ποτέ (FR-PRG-08). */
    @DeleteMapping("/{id}/programmers/{username}")
    public ResponseEntity<ProgramResponse> removeProgrammer(@PathVariable Long id, @PathVariable String username) {
        return ResponseEntity.ok(programService.removeProgrammer(id, username));
    }

    /** Προσθήκη STAFF μέλους στο πρόγραμμα (μόνο πριν το SUBMISSION). Μόνο υφιστάμενος PROGRAMMER. */
    @PostMapping("/{id}/staff")
    public ResponseEntity<ProgramResponse> addStaff(@PathVariable Long id, @Valid @RequestBody AddRoleRequest request) {
        return ResponseEntity.ok(programService.addStaff(id, request));
    }

    /** Αφαίρεση STAFF μέλους· υπόκειται στο ίδιο "πάγωμα" με την προσθήκη (FR-PRG-14). */
    @DeleteMapping("/{id}/staff/{username}")
    public ResponseEntity<ProgramResponse> removeStaff(@PathVariable Long id, @PathVariable String username) {
        return ResponseEntity.ok(programService.removeStaff(id, username));
    }

    /** Αναζήτηση με AND semantics ανάμεσα στα δοθέντα (προαιρετικά) κριτήρια. Ταξινόμηση: ημερομηνία, μετά όνομα. */
    @GetMapping
    public ResponseEntity<List<ProgramResponse>> search(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endTo,
            @RequestParam(required = false) String filmTitle,
            @RequestParam(required = false) String auditorium) {
        return ResponseEntity.ok(programService.searchPrograms(name, description, startFrom, endTo, filmTitle, auditorium));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProgramResponse> view(@PathVariable Long id) {
        return ResponseEntity.ok(programService.viewProgram(id));
    }

    /** Μόνο PROGRAMMER, και μόνο όσο το πρόγραμμα είναι σε κατάσταση CREATED. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        programService.deleteProgram(id);
        return ResponseEntity.noContent().build();
    }

    /** Διαδοχική μετάβαση κατάστασης (state machine), μόνο ένα βήμα κάθε φορά, μόνο PROGRAMMER. */
    @PutMapping("/{id}/state")
    public ResponseEntity<ProgramResponse> updateState(@PathVariable Long id, @Valid @RequestBody ProgramStateUpdateRequest request) {
        return ResponseEntity.ok(programService.updateState(id, request));
    }

    // ------------------------------------------------------------------
    // Οι επτά ονομαστικές λειτουργίες μετάβασης (FR-PRG-T1 ... FR-PRG-T7).
    // Καθεμιά αντιστοιχεί σε μία λειτουργία της εκφώνησης και καταλήγει στον
    // ίδιο, ενιαίο, ελεγχόμενο μηχανισμό ενός βήματος του service.
    // ------------------------------------------------------------------

    /** FR-PRG-T1: CREATED -> SUBMISSION. */
    @PostMapping("/{id}/submission-start")
    public ResponseEntity<ProgramResponse> startSubmission(@PathVariable Long id) {
        return ResponseEntity.ok(programService.startSubmission(id));
    }

    /** FR-PRG-T2: SUBMISSION -> ASSIGNMENT. */
    @PostMapping("/{id}/assignment-start")
    public ResponseEntity<ProgramResponse> startAssignment(@PathVariable Long id) {
        return ResponseEntity.ok(programService.startAssignment(id));
    }

    /** FR-PRG-T3: ASSIGNMENT -> REVIEW. */
    @PostMapping("/{id}/review-start")
    public ResponseEntity<ProgramResponse> startReview(@PathVariable Long id) {
        return ResponseEntity.ok(programService.startReview(id));
    }

    /** FR-PRG-T4: REVIEW -> SCHEDULING. */
    @PostMapping("/{id}/scheduling-start")
    public ResponseEntity<ProgramResponse> startScheduling(@PathVariable Long id) {
        return ResponseEntity.ok(programService.startScheduling(id));
    }

    /** FR-PRG-T5: SCHEDULING -> FINAL_SUBMISSION. */
    @PostMapping("/{id}/final-submission-start")
    public ResponseEntity<ProgramResponse> startFinalSubmission(@PathVariable Long id) {
        return ResponseEntity.ok(programService.startFinalSubmission(id));
    }

    /** FR-PRG-T6: FINAL_SUBMISSION -> DECISION (με αυτόματη απόρριψη μη τελικά υποβληθέντων screenings). */
    @PostMapping("/{id}/decision-start")
    public ResponseEntity<ProgramResponse> startDecision(@PathVariable Long id) {
        return ResponseEntity.ok(programService.startDecision(id));
    }

    /** FR-PRG-T7: DECISION -> ANNOUNCED (τερματική κατάσταση· τα πάντα παγώνουν). */
    @PostMapping("/{id}/announce")
    public ResponseEntity<ProgramResponse> announce(@PathVariable Long id) {
        return ResponseEntity.ok(programService.announce(id));
    }
}
