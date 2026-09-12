package gr.aegean.cinema.security;

import gr.aegean.cinema.exception.AuthenticationException;
import gr.aegean.cinema.exception.ConflictException;
import gr.aegean.cinema.exception.ForbiddenException;
import gr.aegean.cinema.exception.TokenErrorCode;
import gr.aegean.cinema.model.entity.Program;
import gr.aegean.cinema.model.entity.Screening;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.PermanentRole;
import gr.aegean.cinema.model.enums.ProgramRoleType;
import gr.aegean.cinema.model.enums.ProgramState;
import gr.aegean.cinema.repository.ProgramRoleRepository;
import gr.aegean.cinema.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Centralizes every authorization check reused across the services: permanent
 * role checks (ADMIN / non-ADMIN), account-active checks, program-scoped role
 * checks (PROGRAMMER / STAFF / SUBMITTER / handler), and the token-ownership
 * penalty.
 *
 * <h2>The two cases that must never be confused</h2>
 * <ol>
 *   <li><b>Plain authorization failure</b> - a caller presents their OWN valid
 *       token but asks for a resource that belongs to somebody else (e.g. user
 *       A calling {@code PUT /api/users/B}). This is an ordinary permission
 *       problem: {@link ForbiddenException} (HTTP 403), and NO account's
 *       {@code active} flag is touched. Handled by
 *       {@link #requireSelfOrAdmin(Long)} / {@link #requireSelf(Long)}.</li>
 *   <li><b>Token-ownership violation</b> - a structurally valid, live,
 *       non-revoked token is presented by a requester who declares an identity
 *       that is NOT the token's owner (i.e. the token appears stolen). Only
 *       THIS path deactivates BOTH accounts and revokes both users' tokens,
 *       returning {@link TokenErrorCode#TOKEN_NOT_OWNER}. Handled by
 *       {@link #punishTokenOwnershipViolation(User, User, String)}, invoked
 *       from {@link TokenAuthInterceptor} - never from a service.</li>
 * </ol>
 * The migrated code conflated the two (INVENTORY.md); they are now separate.
 */
@Service
public class AuthorizationService {

    private final ProgramRoleRepository programRoleRepository;
    private final UserRepository userRepository;
    private final TokenService tokenService;

    public AuthorizationService(ProgramRoleRepository programRoleRepository, UserRepository userRepository,
                                 TokenService tokenService) {
        this.programRoleRepository = programRoleRepository;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
    }

    // ------------------------------------------------------------------
    // Authentication / account state
    // ------------------------------------------------------------------

    /**
     * Requires an authenticated, ACTIVE caller. An inactive account is blocked
     * from profile changes and from every cinema-management function
     * (DOMAIN_RULES.md), which is enforced here once, centrally.
     */
    public User requireAuthenticated() {
        User current = CurrentUserContext.get();
        if (current == null) {
            throw new AuthenticationException("Authentication is required", TokenErrorCode.TOKEN_MISSING);
        }
        if (!current.isActive()) {
            AuditLog.accessDenied(current.getUsername(), "AUTHENTICATED_ACTION", "ACCOUNT_INACTIVE");
            throw new ForbiddenException(
                    "The account is inactive and cannot perform this action", "ACCOUNT_INACTIVE");
        }
        return current;
    }

    /** Requires an authenticated, active caller holding the ADMIN permanent role. */
    public User requireAdmin() {
        User current = requireAuthenticated();
        if (current.getPermanentRole() != PermanentRole.ADMIN) {
            AuditLog.accessDenied(current.getUsername(), "ADMIN_ACTION", "NOT_ADMIN");
            throw new ForbiddenException("This action is allowed only for ADMIN", "ADMIN_ONLY");
        }
        return current;
    }

    /**
     * Requires an authenticated, active, NON-ADMIN caller. ADMIN is a
     * user-management-only role and must be rejected with 403 from EVERY program
     * and screening management endpoint (DOMAIN_RULES.md "Roles"; ASSUMPTIONS.md
     * #13). Every cinema-domain service method starts with this call, so the rule
     * lives in exactly one place.
     */
    public User requireCinemaActor() {
        User current = requireAuthenticated();
        if (current.getPermanentRole() == PermanentRole.ADMIN) {
            AuditLog.accessDenied(current.getUsername(), "CINEMA_MANAGEMENT_ACTION", "ADMIN_NOT_ALLOWED");
            throw new ForbiddenException(
                    "ADMIN accounts are restricted to user management and cannot perform "
                            + "program or screening management actions",
                    "ADMIN_NOT_ALLOWED");
        }
        return current;
    }

    // ------------------------------------------------------------------
    // Self-or-admin checks (plain 403 on failure - NO deactivation)
    // ------------------------------------------------------------------

    /**
     * The caller must BE the target user, or hold ADMIN. Anything else is an
     * ordinary permission failure: 403, and no account is deactivated.
     */
    public User requireSelfOrAdmin(Long targetUserId) {
        User current = requireAuthenticated();
        if (current.getPermanentRole() == PermanentRole.ADMIN) {
            return current;
        }
        if (!current.getId().equals(targetUserId)) {
            AuditLog.accessDenied(current.getUsername(), "USER_RESOURCE_ACCESS:" + targetUserId, "NOT_SELF_OR_ADMIN");
            throw new ForbiddenException(
                    "This action is allowed only for the account owner or an ADMIN",
                    "NOT_SELF_OR_ADMIN");
        }
        return current;
    }

    /**
     * The caller must BE the target user - not even ADMIN is accepted (used for
     * password change, which requires the old password). Failure is a plain 403
     * and deactivates nobody.
     */
    public User requireSelf(Long targetUserId) {
        User current = requireAuthenticated();
        if (!current.getId().equals(targetUserId)) {
            AuditLog.accessDenied(current.getUsername(), "USER_SELF_ACTION:" + targetUserId, "NOT_SELF");
            throw new ForbiddenException(
                    "This action is allowed only for the account owner", "NOT_SELF");
        }
        return current;
    }

    // ------------------------------------------------------------------
    // Token-ownership violation (the ONLY double-deactivation path)
    // ------------------------------------------------------------------

    /**
     * Applies the assignment's token-theft penalty: "if a valid token belongs to
     * another user than the requester, both accounts must be deactivated".
     *
     * <p>Reached only from {@link TokenAuthInterceptor}, and only when a live,
     * non-revoked, non-expired token is presented together with an explicit
     * requester identity that resolves to a different user. Both the token's
     * owner and the claimed requester are deactivated and have all of their
     * tokens revoked, and a {@link TokenErrorCode#TOKEN_NOT_OWNER} error is
     * raised.
     *
     * <p>This method deliberately RETURNS the exception instead of throwing it:
     * it is {@code @Transactional}, and throwing a runtime exception out of it
     * would roll the transaction back and silently undo the very deactivations
     * the rule demands. The caller commits the penalty first, then throws what
     * this method hands back.
     *
     * @param tokenOwner       the user the presented token actually belongs to
     * @param claimedRequester the user the request claims to act as (may be null
     *                         when the claimed identity matches no account, in
     *                         which case only the token owner is deactivated)
     * @param claimedIdentity  the raw claimed identity, for the audit line
     * @return the {@link TokenErrorCode#TOKEN_NOT_OWNER} exception the caller must throw
     */
    @Transactional
    public AuthenticationException punishTokenOwnershipViolation(User tokenOwner, User claimedRequester,
                                                                 String claimedIdentity) {
        deactivateAndRevoke(tokenOwner);
        if (claimedRequester != null && !claimedRequester.getId().equals(tokenOwner.getId())) {
            deactivateAndRevoke(claimedRequester);
        }
        AuditLog.tokenOwnershipViolation(tokenOwner.getUsername(), claimedIdentity);
        return new AuthenticationException(
                "The presented token belongs to a different user than the requester. "
                        + "Both accounts have been deactivated for security reasons.",
                TokenErrorCode.TOKEN_NOT_OWNER);
    }

    private void deactivateAndRevoke(User user) {
        user.setActive(false);
        userRepository.save(user);
        tokenService.revokeActiveTokens(user);
    }

    // ------------------------------------------------------------------
    // Program- and screening-scoped role checks
    // ------------------------------------------------------------------

    public boolean isProgrammerOf(User user, Program program) {
        return programRoleRepository.existsByUserAndProgramAndRole(user, program, ProgramRoleType.PROGRAMMER);
    }

    public boolean isStaffOf(User user, Program program) {
        return programRoleRepository.existsByUserAndProgramAndRole(user, program, ProgramRoleType.STAFF);
    }

    public void requireProgrammerOf(User user, Program program) {
        if (!isProgrammerOf(user, program)) {
            AuditLog.accessDenied(user.getUsername(), "PROGRAM_MANAGEMENT:" + program.getId(), "NOT_PROGRAMMER");
            throw new ForbiddenException(
                    "This action is allowed only for a PROGRAMMER of this specific program", "NOT_PROGRAMMER");
        }
    }

    /**
     * The ANNOUNCED freeze (FR-PRG-T9 / FR-PRG-09): once a program is ANNOUNCED
     * "everything is frozen - no updates to program or screenings"
     * (DOMAIN_RULES.md). Because it applies to BOTH domains it lives here, in
     * the one component both services already depend on, rather than being
     * re-stated in each of them.
     *
     * <p>The refusal is a 409 and not a 400: nothing about the request is
     * malformed, it simply conflicts with the program's terminal state - the
     * same reasoning that makes an illegal state transition a 409.
     */
    public void requireNotAnnounced(Program program, String what) {
        if (program.getState() == ProgramState.ANNOUNCED) {
            throw new ConflictException(
                    "The program has been ANNOUNCED; " + what + " is no longer possible",
                    "PROGRAM_ANNOUNCED_FROZEN");
        }
    }

    public boolean isSubmitterOf(User user, Screening screening) {
        return screening.getSubmitter() != null && screening.getSubmitter().getId().equals(user.getId());
    }

    /**
     * ROLE-12 / ROLE-13: a SUBMITTER may act on and see the full details of only
     * their OWN screenings. Failure is a plain 403 that changes no account state.
     */
    public void requireSubmitterOf(User user, Screening screening) {
        if (!isSubmitterOf(user, screening)) {
            AuditLog.accessDenied(user.getUsername(), "SCREENING_MANAGEMENT:" + screening.getId(), "NOT_SUBMITTER");
            throw new ForbiddenException(
                    "This action is allowed only for the SUBMITTER of this screening", "NOT_SUBMITTER");
        }
    }

    public boolean isHandlerOf(User user, Screening screening) {
        return screening.getHandler() != null && screening.getHandler().getId().equals(user.getId());
    }

    /**
     * ROLE-10 / ROLE-11: a STAFF member may review ONLY the screenings they were
     * explicitly assigned to as handler; everywhere else they hold VISITOR-level
     * rights. Failure is a plain 403 that changes no account state.
     */
    public void requireHandlerOf(User user, Screening screening) {
        if (!isHandlerOf(user, screening)) {
            AuditLog.accessDenied(user.getUsername(), "SCREENING_REVIEW:" + screening.getId(), "NOT_HANDLER");
            throw new ForbiddenException(
                    "This action is allowed only for the assigned STAFF handler of this screening", "NOT_HANDLER");
        }
    }
}
