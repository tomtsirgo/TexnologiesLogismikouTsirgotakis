package gr.aegean.cinema.redaction;

import gr.aegean.cinema.dto.program.ProgramResponse;
import gr.aegean.cinema.dto.screening.ScreeningResponse;
import gr.aegean.cinema.model.entity.Program;
import gr.aegean.cinema.model.entity.Screening;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.PermanentRole;
import gr.aegean.cinema.model.enums.ProgramRoleType;
import gr.aegean.cinema.model.enums.ProgramState;
import gr.aegean.cinema.model.enums.ScreeningState;
import gr.aegean.cinema.repository.ProgramRoleRepository;
import gr.aegean.cinema.repository.ScreeningRepository;
import gr.aegean.cinema.security.AuthorizationService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * THE central redaction layer (FR-RED-01 / FR-RED-02).
 *
 * <p>DOMAIN_RULES.md: <i>"Implement ONE central redaction layer used by EVERY
 * view and search endpoint. Never redact ad hoc inside a controller."</i> Before
 * this class existed, {@code ProgramServiceImpl} and {@code ScreeningServiceImpl}
 * each carried their own private, duplicated
 * {@code toFullResponse}/{@code toRedactedResponse}/{@code toVisitorResponse}
 * pair (INVENTORY.md), which is exactly how the screening {@code state} field
 * ended up leaking into the VISITOR view. Both services now own no mapping code
 * at all: every {@link ProgramResponse} and {@link ScreeningResponse} the system
 * emits — from a search, from a view, and from every write path — is built here.
 *
 * <h2>The visibility rules, stated once</h2>
 * <table>
 *   <caption>Who sees what</caption>
 *   <tr><th>Requester</th><th>Program</th><th>Screening</th></tr>
 *   <tr><td>VISITOR (anonymous) / plain USER</td>
 *       <td>Only ANNOUNCED programs, redacted (ROLE-01, FR-RED-03)</td>
 *       <td>Only SCHEDULED screenings of an ANNOUNCED program, redacted
 *           (ROLE-02, FR-RED-04)</td></tr>
 *   <tr><td>PROGRAMMER of the program</td>
 *       <td>Full, in any state (FR-RED-05)</td>
 *       <td>Full, for every screening in it (FR-RED-06)</td></tr>
 *   <tr><td>STAFF</td>
 *       <td>VISITOR level (ROLE-11)</td>
 *       <td>Full ONLY for the screenings they handle (FR-RED-07)</td></tr>
 *   <tr><td>SUBMITTER</td>
 *       <td>VISITOR level (ROLE-11/13)</td>
 *       <td>Full ONLY for their own screenings (FR-RED-08)</td></tr>
 *   <tr><td>ADMIN</td>
 *       <td colspan="2">Never a cinema actor at all: treated exactly like an
 *           anonymous VISITOR here, and rejected outright with 403 by
 *           {@link AuthorizationService#requireCinemaActor()} on every
 *           management endpoint (ROLE-14/15/16)</td></tr>
 * </table>
 *
 * <p>"Redacted" means the forbidden fields are left null, and both response DTOs
 * are annotated {@code @JsonInclude(NON_NULL)}, so those fields are absent from
 * the JSON rather than present-and-empty. Only the resource id survives
 * redaction, because it is the resource's address and not one of its domain
 * fields (ASSUMPTIONS.md #41).
 */
@Component
public class RedactionService {

    private final AuthorizationService authorizationService;
    private final ProgramRoleRepository programRoleRepository;
    private final ScreeningRepository screeningRepository;

    public RedactionService(AuthorizationService authorizationService,
                            ProgramRoleRepository programRoleRepository,
                            ScreeningRepository screeningRepository) {
        this.authorizationService = authorizationService;
        this.programRoleRepository = programRoleRepository;
        this.screeningRepository = screeningRepository;
    }

    // ==================================================================
    // Access decisions
    // ==================================================================

    /**
     * An anonymous caller is a VISITOR; so, for redaction purposes, is an ADMIN.
     * ADMIN is user-management only (DOMAIN_RULES.md "Roles", ASSUMPTIONS.md #13)
     * and is already refused with 403 by
     * {@link AuthorizationService#requireCinemaActor()} on every management
     * endpoint. The public browsing endpoints do not pass through that gate, so
     * the exclusion is restated here to guarantee an ADMIN can never obtain a
     * full view through a search or a view-by-id either.
     */
    private boolean isCinemaActor(User requester) {
        return requester != null && requester.getPermanentRole() != PermanentRole.ADMIN;
    }

    /** FR-RED-05: only a PROGRAMMER of THIS program sees it in full. */
    public boolean hasFullProgramAccess(Program program, User requester) {
        return isCinemaActor(requester) && authorizationService.isProgrammerOf(requester, program);
    }

    /**
     * FR-RED-06 / FR-RED-07 / FR-RED-08: a PROGRAMMER of the owning program, the
     * screening's own SUBMITTER, and its assigned STAFF handler — nobody else.
     */
    public boolean hasFullScreeningAccess(Screening screening, User requester) {
        if (!isCinemaActor(requester)) {
            return false;
        }
        return authorizationService.isProgrammerOf(requester, screening.getProgram())
                || authorizationService.isSubmitterOf(requester, screening)
                || authorizationService.isHandlerOf(requester, screening);
    }

    /**
     * ROLE-01: a VISITOR or plain USER may see ANNOUNCED programs only. Anyone
     * with full access sees their program in every state.
     */
    public boolean isProgramVisible(Program program, User requester) {
        return hasFullProgramAccess(program, requester) || program.getState() == ProgramState.ANNOUNCED;
    }

    /**
     * ROLE-02: a VISITOR or plain USER may see SCHEDULED screenings only, and
     * only inside a program that has actually been ANNOUNCED — a screening
     * scheduled in a programme still being decided is not public yet.
     */
    public boolean isScreeningVisible(Screening screening, User requester) {
        return hasFullScreeningAccess(screening, requester)
                || (screening.getState() == ScreeningState.SCHEDULED
                    && screening.getProgram().getState() == ProgramState.ANNOUNCED);
    }

    // ==================================================================
    // Mapping — the only place a response DTO is built
    // ==================================================================

    /** FR-PRG-23: one program, redacted according to the requester's role. */
    public ProgramResponse program(Program program, User requester) {
        return hasFullProgramAccess(program, requester) ? fullProgram(program) : redactedProgram(program);
    }

    /** FR-SCR-46: one screening, redacted according to the requester's role. */
    public ScreeningResponse screening(Screening screening, User requester) {
        return hasFullScreeningAccess(screening, requester) ? fullScreening(screening) : redactedScreening(screening);
    }

    /**
     * The full program view (FR-RED-05). Used for a PROGRAMMER's reads and for
     * every program write path, whose caller has already been proven to be a
     * PROGRAMMER of the program by the service.
     */
    public ProgramResponse fullProgram(Program program) {
        return ProgramResponse.builder()
                .id(program.getId())
                .name(program.getName())
                .description(program.getDescription())
                .startDate(program.getStartDate())
                .endDate(program.getEndDate())
                .state(program.getState())
                .createdAt(program.getCreatedAt())
                .creatorUsername(program.getCreator() != null ? program.getCreator().getUsername() : null)
                .programmerUsernames(usernamesWithRole(program, ProgramRoleType.PROGRAMMER))
                .staffUsernames(usernamesWithRole(program, ProgramRoleType.STAFF))
                .auditoriums(auditoriumsOf(program))
                .build();
    }

    /**
     * FR-RED-03: "name, dates, auditorium, description, programmer names" —
     * and nothing else. No state, no creation date, no creator, no STAFF set.
     */
    private ProgramResponse redactedProgram(Program program) {
        return ProgramResponse.builder()
                .id(program.getId())
                .name(program.getName())
                .description(program.getDescription())
                .startDate(program.getStartDate())
                .endDate(program.getEndDate())
                .programmerUsernames(usernamesWithRole(program, ProgramRoleType.PROGRAMMER))
                .auditoriums(auditoriumsOf(program))
                .build();
    }

    /**
     * The full screening view (FR-RED-06/07/08). Used for the reads of anyone
     * with a stake in the screening and for every screening write path, whose
     * caller the service has already proven to be one of them.
     */
    public ScreeningResponse fullScreening(Screening screening) {
        return ScreeningResponse.builder()
                .id(screening.getId())
                .programId(screening.getProgram().getId())
                .filmTitle(screening.getFilmTitle())
                .filmCast(screening.getFilmCast())
                .filmGenres(screening.getFilmGenres())
                .filmDurationMinutes(screening.getFilmDurationMinutes())
                .auditoriumName(screening.getAuditoriumName())
                .startTime(screening.getStartTime())
                .endTime(screening.getEndTime())
                .state(screening.getState())
                .reviewScore(screening.getReviewScore())
                .reviewComments(screening.getReviewComments())
                .approvalNotes(screening.getApprovalNotes())
                .rejectionReason(screening.getRejectionReason())
                .submitterUsername(screening.getSubmitter().getUsername())
                .handlerUsername(screening.getHandler() != null ? screening.getHandler().getUsername() : null)
                .finallySubmitted(screening.isFinallySubmitted())
                .build();
    }

    /**
     * FR-RED-04: "film title, genre, scheduled time and auditorium. Nothing
     * else." The cast, the duration, the end time, the review, the approval
     * notes, the rejection reason, the submitter, the handler, the
     * final-submission flag and — the leak INVENTORY.md flagged in the migrated
     * code — the lifecycle {@code state} are all withheld.
     */
    private ScreeningResponse redactedScreening(Screening screening) {
        return ScreeningResponse.builder()
                .id(screening.getId())
                .programId(screening.getProgram().getId())
                .filmTitle(screening.getFilmTitle())
                .filmGenres(screening.getFilmGenres())
                .startTime(screening.getStartTime())
                .auditoriumName(screening.getAuditoriumName())
                .build();
    }

    // ==================================================================
    // Derived fields
    // ==================================================================

    /**
     * ASSUMPTIONS.md #7: DOMAIN_RULES.md lists "auditorium" among a program's
     * publicly visible fields, but {@code Program} has no such column — only
     * {@code Screening.auditoriumName} exists. The field is therefore DERIVED
     * here, at redaction time, as the distinct, sorted set of auditorium names
     * used by the program's screenings.
     */
    public List<String> auditoriumsOf(Program program) {
        return screeningRepository.findByProgram(program).stream()
                .map(Screening::getAuditoriumName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private List<String> usernamesWithRole(Program program, ProgramRoleType roleType) {
        return programRoleRepository.findByProgramAndRole(program, roleType).stream()
                .map(programRole -> programRole.getUser().getUsername())
                .sorted()
                .collect(Collectors.toList());
    }
}
