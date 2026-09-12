package gr.aegean.cinema.controller;

import gr.aegean.cinema.dto.screening.*;
import gr.aegean.cinema.service.ScreeningService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoints acting on ONE specific screening, identified by its id: every
 * function of the screening lifecycle and therefore every transition of the
 * screening state machine (FR-SCR-T1 … FR-SCR-T8).
 *
 * <p>The controller stays deliberately thin: it maps HTTP to a service call and
 * nothing else. Every actor rule, state rule and error code lives in
 * {@link gr.aegean.cinema.service.impl.ScreeningServiceImpl}, so that the same
 * rules hold for any other caller of the service.
 */
@RestController
@RequestMapping("/api/screenings")
public class ScreeningController {

    private final ScreeningService screeningService;

    public ScreeningController(ScreeningService screeningService) {
        this.screeningService = screeningService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScreeningResponse> view(@PathVariable Long id) {
        return ResponseEntity.ok(screeningService.viewScreening(id));
    }

    /** FR-SCR-07/08/09/33: only the SUBMITTER, only while the screening is in CREATED. */
    @PutMapping("/{id}")
    public ResponseEntity<ScreeningResponse> update(@PathVariable Long id, @RequestBody ScreeningUpdateRequest request) {
        return ResponseEntity.ok(screeningService.updateScreening(id, request));
    }

    /** FR-SCR-T1: CREATED -> SUBMITTED. Only the SUBMITTER, only while the program is in SUBMISSION. */
    @PostMapping("/{id}/submit")
    public ResponseEntity<ScreeningResponse> submit(@PathVariable Long id) {
        return ResponseEntity.ok(screeningService.submitScreening(id));
    }

    /** FR-SCR-T2: deletes the screening. Only the SUBMITTER, only while it is in CREATED. */
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<Void> withdraw(@PathVariable Long id) {
        screeningService.withdrawScreening(id);
        return ResponseEntity.noContent().build();
    }

    /** FR-SCR-16/17/18: assigns exactly one STAFF handler. Only a PROGRAMMER, only during ASSIGNMENT. */
    @PostMapping("/{id}/handler")
    public ResponseEntity<ScreeningResponse> assignHandler(@PathVariable Long id, @Valid @RequestBody HandlerAssignRequest request) {
        return ResponseEntity.ok(screeningService.assignHandler(id, request));
    }

    /** FR-SCR-T3: SUBMITTED -> REVIEWED. Only the assigned STAFF handler, only while the program is in REVIEW. */
    @PostMapping("/{id}/review")
    public ResponseEntity<ScreeningResponse> review(@PathVariable Long id, @Valid @RequestBody ReviewRequest request) {
        return ResponseEntity.ok(screeningService.reviewScreening(id, request));
    }

    /**
     * FR-SCR-T4: REVIEWED -> APPROVED. Only a PROGRAMMER of the program, only
     * while the program is in SCHEDULING (FR-SCR-23/24, ASSUMPTIONS.md #2 — the
     * migrated code required the SUBMITTER here, which was M0 finding #2).
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<ScreeningResponse> approve(@PathVariable Long id, @RequestBody ApprovalRequest request) {
        return ResponseEntity.ok(screeningService.approveScreening(id, request));
    }

    /** FR-SCR-T5: -> REJECTED manually. Only a PROGRAMMER, only in SCHEDULING or DECISION, reason mandatory. */
    @PostMapping("/{id}/reject")
    public ResponseEntity<ScreeningResponse> reject(@PathVariable Long id, @Valid @RequestBody RejectionRequest request) {
        return ResponseEntity.ok(screeningService.rejectScreening(id, request));
    }

    /** FR-SCR-30/31/32: the final submission, after which the details freeze. Only the SUBMITTER, only in FINAL_SUBMISSION. */
    @PostMapping("/{id}/final-submit")
    public ResponseEntity<ScreeningResponse> finalSubmit(@PathVariable Long id, @RequestBody ScreeningUpdateRequest request) {
        return ResponseEntity.ok(screeningService.finalSubmitScreening(id, request));
    }

    /** FR-SCR-T7: APPROVED and finally submitted -> SCHEDULED. Only a PROGRAMMER, only in DECISION. */
    @PostMapping("/{id}/accept")
    public ResponseEntity<ScreeningResponse> accept(@PathVariable Long id) {
        return ResponseEntity.ok(screeningService.acceptScreening(id));
    }
}
