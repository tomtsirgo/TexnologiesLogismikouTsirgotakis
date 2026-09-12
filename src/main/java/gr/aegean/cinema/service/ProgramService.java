package gr.aegean.cinema.service;

import gr.aegean.cinema.dto.program.*;

import java.time.LocalDate;
import java.util.List;

/**
 * The Program (Cinema Season) management module.
 *
 * <p>The seven forward-only transitions of the program state machine
 * (FR-PRG-T1 … FR-PRG-T7) each get their own named function, because the
 * assignment names them individually ("submission start", "handler assignment
 * start", "review start", "schedule making", "final submission start",
 * "decision making", "announcement"). They all funnel into the same guarded
 * step implementation, so there is exactly one place where legality, actor
 * authorization, side effects and auditing are decided.
 * {@link #updateState(Long, ProgramStateUpdateRequest)} is the generic
 * equivalent that names the target state in the body.
 */
public interface ProgramService {

    ProgramResponse createProgram(ProgramCreateRequest request);

    ProgramResponse updateProgram(Long programId, ProgramUpdateRequest request);

    ProgramResponse addProgrammer(Long programId, AddRoleRequest request);

    ProgramResponse removeProgrammer(Long programId, String username);

    ProgramResponse addStaff(Long programId, AddRoleRequest request);

    ProgramResponse removeStaff(Long programId, String username);

    List<ProgramResponse> searchPrograms(String name, String description, LocalDate startFrom, LocalDate endTo,
                                          String filmTitle, String auditorium);

    ProgramResponse viewProgram(Long programId);

    void deleteProgram(Long programId);

    /** Generic one-step transition; the target state is supplied in the request body. */
    ProgramResponse updateState(Long programId, ProgramStateUpdateRequest request);

    /** FR-PRG-T1: CREATED → SUBMISSION. */
    ProgramResponse startSubmission(Long programId);

    /** FR-PRG-T2: SUBMISSION → ASSIGNMENT. */
    ProgramResponse startAssignment(Long programId);

    /** FR-PRG-T3: ASSIGNMENT → REVIEW. */
    ProgramResponse startReview(Long programId);

    /** FR-PRG-T4: REVIEW → SCHEDULING. */
    ProgramResponse startScheduling(Long programId);

    /** FR-PRG-T5: SCHEDULING → FINAL_SUBMISSION. */
    ProgramResponse startFinalSubmission(Long programId);

    /** FR-PRG-T6: FINAL_SUBMISSION → DECISION, auto-rejecting unfinished APPROVED screenings. */
    ProgramResponse startDecision(Long programId);

    /** FR-PRG-T7: DECISION → ANNOUNCED, after which everything is frozen. */
    ProgramResponse announce(Long programId);
}
