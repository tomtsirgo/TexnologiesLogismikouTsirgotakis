package gr.aegean.cinema.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The system's audit trail, written through a dedicated SLF4J logger named
 * {@code AUDIT} so that it can be routed to its own appender/file independently
 * of ordinary application logging.
 *
 * DOMAIN_RULES.md requires "an SLF4J audit log line for every state transition
 * and every authentication event". This class covers the authentication half
 * (registration, login success/failure, lockout, logout, force-logout, token
 * rejection, account status change, deletion); the program half of the
 * state-transition requirement is covered by the PROGRAM_* methods below, and
 * the screening half by the SCREENING_* methods (M4), so that no service ever
 * needs a private logger of its own.
 *
 * Deliberately a static utility rather than an injected bean: audit logging is
 * a pure side effect with no state and no test double worth injecting, and
 * keeping it static avoids threading an extra constructor argument through
 * every service (and therefore through every service's unit test).
 */
public final class AuditLog {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    private AuditLog() {
    }

    /** A user account was created (always inactive). */
    public static void registration(String username, Long userId) {
        AUDIT.info("event=REGISTER outcome=SUCCESS username={} userId={} active=false", username, userId);
    }

    /** Authentication succeeded; a fresh token was issued and any previous one revoked. */
    public static void authSuccess(String username, Long userId) {
        AUDIT.info("event=AUTHENTICATE outcome=SUCCESS username={} userId={}", username, userId);
    }

    /** Authentication failed. {@code reason} never contains the submitted password. */
    public static void authFailure(String username, String reason, int consecutiveFailures) {
        AUDIT.warn("event=AUTHENTICATE outcome=FAILURE username={} reason={} consecutiveFailures={}",
                username, reason, consecutiveFailures);
    }

    /** An account was deactivated by the 3-consecutive-failure rule. */
    public static void lockout(String username, Long userId, String trigger) {
        AUDIT.warn("event=LOCKOUT outcome=ACCOUNT_DEACTIVATED username={} userId={} trigger={}",
                username, userId, trigger);
    }

    /** A password change attempt finished (success or failure); the token is revoked either way. */
    public static void passwordChange(String username, Long userId, String outcome, int consecutiveFailures) {
        AUDIT.info("event=PASSWORD_CHANGE outcome={} username={} userId={} consecutiveFailures={}",
                outcome, username, userId, consecutiveFailures);
    }

    /** A username change invalidated the current token. */
    public static void usernameChange(Long userId, String oldUsername, String newUsername) {
        AUDIT.info("event=USERNAME_CHANGE outcome=SUCCESS userId={} from={} to={} tokenInvalidated=true",
                userId, oldUsername, newUsername);
    }

    /** The caller logged themselves out. */
    public static void logout(String username, Long userId) {
        AUDIT.info("event=LOGOUT outcome=SUCCESS username={} userId={}", username, userId);
    }

    /** An ADMIN forcibly logged out another (non-ADMIN) account. */
    public static void forceLogout(String adminUsername, String targetUsername) {
        AUDIT.warn("event=FORCE_LOGOUT outcome=SUCCESS admin={} target={}", adminUsername, targetUsername);
    }

    /** A bearer token was rejected; {@code code} is the distinct TokenErrorCode name. */
    public static void tokenRejected(String code, String method, String path, String presentedToken) {
        AUDIT.warn("event=TOKEN_VALIDATION outcome=REJECTED code={} request=\"{} {}\" token={}",
                code, method, path, mask(presentedToken));
    }

    /** The valid-token-wrong-owner penalty fired: both accounts deactivated. */
    public static void tokenOwnershipViolation(String tokenOwnerUsername, String claimedRequester) {
        AUDIT.error("event=TOKEN_OWNERSHIP_VIOLATION outcome=BOTH_ACCOUNTS_DEACTIVATED tokenOwner={} claimedRequester={}",
                tokenOwnerUsername, claimedRequester);
    }

    /** An ADMIN activated or deactivated an account. */
    public static void statusChange(String adminUsername, String targetUsername, boolean active) {
        AUDIT.warn("event=ACCOUNT_STATUS_UPDATE outcome=SUCCESS admin={} target={} active={}",
                adminUsername, targetUsername, active);
    }

    /** An account (and every one of its tokens) was deleted. */
    public static void accountDeleted(String actorUsername, String targetUsername, Long targetId) {
        AUDIT.warn("event=ACCOUNT_DELETE outcome=SUCCESS actor={} target={} targetId={} tokensDeleted=true",
                actorUsername, targetUsername, targetId);
    }

    /** A rejected authorization attempt; deliberately does NOT change any account state. */
    public static void accessDenied(String username, String action, String reason) {
        AUDIT.warn("event=ACCESS_DENIED username={} action={} reason={} accountsAffected=0",
                username, action, reason);
    }

    // ------------------------------------------------------------------
    // Program management (M3)
    // ------------------------------------------------------------------

    /** A new program was created; the actor is now its first PROGRAMMER (FR-PRG-06). */
    public static void programCreated(Long programId, String name, String actorUsername) {
        AUDIT.info("event=PROGRAM_CREATE outcome=SUCCESS programId={} name=\"{}\" creator={} state=CREATED",
                programId, name, actorUsername);
    }

    /** A program's mutable fields were changed (only ever possible before ANNOUNCED). */
    public static void programUpdated(Long programId, String actorUsername, String state) {
        AUDIT.info("event=PROGRAM_UPDATE outcome=SUCCESS programId={} actor={} state={}",
                programId, actorUsername, state);
    }

    /** A program was deleted (only ever possible in CREATED, by one of its PROGRAMMERs). */
    public static void programDeleted(Long programId, String name, String actorUsername) {
        AUDIT.warn("event=PROGRAM_DELETE outcome=SUCCESS programId={} name=\"{}\" actor={}",
                programId, name, actorUsername);
    }

    /**
     * One legal forward step of the program state machine. DOMAIN_RULES.md
     * requires an audit line for EVERY state transition; this is the program
     * half of that requirement.
     */
    public static void programTransition(Long programId, String from, String to, String actorUsername) {
        AUDIT.info("event=PROGRAM_STATE_TRANSITION outcome=SUCCESS programId={} from={} to={} actor={}",
                programId, from, to, actorUsername);
    }

    /** A rejected program state transition, with the reason it was refused. */
    public static void programTransitionRejected(Long programId, String from, String to, String actorUsername,
                                                 String reason) {
        AUDIT.warn("event=PROGRAM_STATE_TRANSITION outcome=REJECTED programId={} from={} to={} actor={} reason={}",
                programId, from, to, actorUsername, reason);
    }

    /** A PROGRAMMER or STAFF role was granted inside one program (ROLE-17/18). */
    public static void programRoleAdded(Long programId, String role, String targetUsername, String actorUsername) {
        AUDIT.info("event=PROGRAM_ROLE_ADD outcome=SUCCESS programId={} role={} target={} actor={}",
                programId, role, targetUsername, actorUsername);
    }

    /** A PROGRAMMER or STAFF role was revoked inside one program. */
    public static void programRoleRemoved(Long programId, String role, String targetUsername, String actorUsername) {
        AUDIT.warn("event=PROGRAM_ROLE_REMOVE outcome=SUCCESS programId={} role={} target={} actor={}",
                programId, role, targetUsername, actorUsername);
    }

    /**
     * The DECISION-entry side effect: an APPROVED screening that was never
     * finally submitted is auto-rejected by the system (FR-PRG-T6), with the
     * reason recorded on the screening itself.
     */
    public static void screeningAutoRejected(Long screeningId, Long programId, String reason) {
        AUDIT.warn("event=SCREENING_AUTO_REJECT outcome=REJECTED screeningId={} programId={} actor=SYSTEM reason=\"{}\"",
                screeningId, programId, reason);
    }

    // ------------------------------------------------------------------
    // Screening management (M4)
    // ------------------------------------------------------------------

    /** A new screening was created; its creator is its SUBMITTER from that moment (FR-SCR-02). */
    public static void screeningCreated(Long screeningId, Long programId, String submitterUsername) {
        AUDIT.info("event=SCREENING_CREATE outcome=SUCCESS screeningId={} programId={} submitter={} state=CREATED",
                screeningId, programId, submitterUsername);
    }

    /** A screening's mutable details were changed (only ever possible in CREATED — FR-SCR-08/33). */
    public static void screeningUpdated(Long screeningId, String actorUsername, String state) {
        AUDIT.info("event=SCREENING_UPDATE outcome=SUCCESS screeningId={} actor={} state={}",
                screeningId, actorUsername, state);
    }

    /** A screening was withdrawn by its SUBMITTER and therefore deleted (FR-SCR-14/15, FR-SCR-T2). */
    public static void screeningWithdrawn(Long screeningId, Long programId, String actorUsername) {
        AUDIT.warn("event=SCREENING_WITHDRAW outcome=DELETED screeningId={} programId={} actor={}",
                screeningId, programId, actorUsername);
    }

    /**
     * One legal step of the screening state machine (FR-SCR-T1 … FR-SCR-T7).
     * DOMAIN_RULES.md requires an audit line for EVERY state transition; together
     * with {@link #programTransition} and {@link #screeningAutoRejected} this
     * completes that requirement for both domains.
     *
     * @param trigger the function that caused the step (SUBMIT, REVIEW, APPROVE, REJECT, ACCEPT)
     */
    public static void screeningTransition(Long screeningId, String from, String to, String actorUsername,
                                           String trigger) {
        AUDIT.info("event=SCREENING_STATE_TRANSITION outcome=SUCCESS screeningId={} from={} to={} actor={} trigger={}",
                screeningId, from, to, actorUsername, trigger);
    }

    /** A refused screening state transition, with the error code that refused it. */
    public static void screeningTransitionRejected(Long screeningId, String from, String to, String actorUsername,
                                                   String reason) {
        AUDIT.warn("event=SCREENING_STATE_TRANSITION outcome=REJECTED screeningId={} from={} to={} actor={} reason={}",
                screeningId, from, to, actorUsername, reason);
    }

    /** A STAFF member was assigned as the single handler of a screening (FR-SCR-16/17/18). */
    public static void screeningHandlerAssigned(Long screeningId, String staffUsername, String actorUsername) {
        AUDIT.info("event=SCREENING_HANDLER_ASSIGN outcome=SUCCESS screeningId={} handler={} actor={}",
                screeningId, staffUsername, actorUsername);
    }

    /** The SUBMITTER finally submitted the screening; its details are frozen from now on (FR-SCR-30/32). */
    public static void screeningFinallySubmitted(Long screeningId, String actorUsername) {
        AUDIT.info("event=SCREENING_FINAL_SUBMIT outcome=SUCCESS screeningId={} actor={} detailsFrozen=true",
                screeningId, actorUsername);
    }

    // ------------------------------------------------------------------
    // Cross-cutting concerns (M5)
    // ------------------------------------------------------------------

    /**
     * A request was refused by the in-memory token bucket (NFR-06/07). Logged as
     * a warning because a sustained stream of these is the signal an operator
     * cares about — either abuse, or a limit set too low for real traffic.
     */
    public static void rateLimited(String identity, String bucket, String method, String path, int capacityPerMinute) {
        AUDIT.warn("event=RATE_LIMIT outcome=REJECTED identity={} bucket={} request=\"{} {}\" limitPerMinute={} status=429",
                identity, bucket, method, path, capacityPerMinute);
    }

    /**
     * A repeated {@code Idempotency-Key} was answered from the cache and the
     * side effect was NOT executed again (NFR-05). This line is the audit trail
     * that distinguishes "the client retried" from "the system acted twice".
     */
    public static void idempotentReplay(String username, String idempotencyKey, String method, String path,
                                        int replayedStatus) {
        AUDIT.info("event=IDEMPOTENT_REPLAY outcome=CACHED_RESPONSE user={} key={} request=\"{} {}\" "
                        + "replayedStatus={} sideEffectExecuted=false",
                username, idempotencyKey, method, path, replayedStatus);
    }

    /**
     * A search was executed. Recorded because search is the one family of
     * endpoints a VISITOR can reach anonymously, so the trail of what was asked
     * for — and how much it returned after role filtering — is the only record
     * that the redaction layer actually ran.
     */
    public static void searchExecuted(String domain, String actorUsername, int resultCount) {
        AUDIT.info("event=SEARCH outcome=SUCCESS domain={} actor={} visibleResults={}",
                domain, actorUsername, resultCount);
    }

    /** Never write a whole bearer token into the log. */
    private static String mask(String token) {
        if (token == null || token.isBlank()) {
            return "<none>";
        }
        return token.length() <= 8 ? "********" : token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }
}
