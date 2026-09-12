package gr.aegean.cinema.service.impl;

import gr.aegean.cinema.dto.user.*;
import gr.aegean.cinema.exception.BadRequestException;
import gr.aegean.cinema.exception.ConflictException;
import gr.aegean.cinema.exception.ForbiddenException;
import gr.aegean.cinema.exception.NotFoundException;
import gr.aegean.cinema.model.entity.ProgramRole;
import gr.aegean.cinema.model.entity.Screening;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.PermanentRole;
import gr.aegean.cinema.repository.ProgramRoleRepository;
import gr.aegean.cinema.repository.ScreeningRepository;
import gr.aegean.cinema.repository.UserRepository;
import gr.aegean.cinema.security.AuditLog;
import gr.aegean.cinema.security.AuthorizationService;
import gr.aegean.cinema.security.PasswordUtil;
import gr.aegean.cinema.security.TokenService;
import gr.aegean.cinema.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Every function of the assignment's "user management system" except
 * authentication/logout (which live in {@link AuthServiceImpl}): registration,
 * information update, password update, account status update, deletion and the
 * profile view.
 *
 * Lockout rule implemented here: three CONSECUTIVE failed password changes
 * deactivate the account; a successful change resets the counter. The current
 * token is invalidated on EVERY password-change outcome, success or failure.
 */
@Service
public class UserServiceImpl implements UserService {

    /** Three consecutive failures trigger deactivation (assignment text). */
    public static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static final String USERNAME_RULE =
            "Invalid username: it must start with a letter, be at least 5 characters long, "
                    + "and contain only letters, digits or underscore";
    private static final String PASSWORD_RULE =
            "Invalid password: at least 8 characters, including an upper-case letter, a lower-case "
                    + "letter, a digit and a special character";

    private final UserRepository userRepository;
    private final ProgramRoleRepository programRoleRepository;
    private final ScreeningRepository screeningRepository;
    private final PasswordUtil passwordUtil;
    private final AuthorizationService authorizationService;
    private final TokenService tokenService;

    public UserServiceImpl(UserRepository userRepository,
                            ProgramRoleRepository programRoleRepository,
                            ScreeningRepository screeningRepository,
                            PasswordUtil passwordUtil,
                            AuthorizationService authorizationService,
                            TokenService tokenService) {
        this.userRepository = userRepository;
        this.programRoleRepository = programRoleRepository;
        this.screeningRepository = screeningRepository;
        this.passwordUtil = passwordUtil;
        this.authorizationService = authorizationService;
        this.tokenService = tokenService;
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    /**
     * Stores every submitted field but leaves the account INACTIVE until an
     * ADMIN activates it. Rejects a duplicate username with 409 and a
     * username/password pattern violation with 400.
     */
    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (!passwordUtil.isUsernameValid(request.getUsername())) {
            throw new BadRequestException(USERNAME_RULE, "INVALID_USERNAME_PATTERN");
        }
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new ConflictException(
                    "The username '" + request.getUsername() + "' is already in use", "USERNAME_TAKEN");
        }
        if (!passwordUtil.isPasswordValid(request.getPassword())) {
            throw new BadRequestException(PASSWORD_RULE, "INVALID_PASSWORD_PATTERN");
        }

        User user = User.builder()
                .username(request.getUsername())
                .password(passwordUtil.hash(request.getPassword()))
                .fullName(request.getFullName())
                .permanentRole(PermanentRole.USER)
                .active(false) // New accounts ALWAYS start inactive.
                .failedAuthAttempts(0)
                .failedPasswordAttempts(0)
                .build();

        user = userRepository.save(user);
        AuditLog.registration(user.getUsername(), user.getId());
        return toResponse(user);
    }

    // ------------------------------------------------------------------
    // User information update (never the password)
    // ------------------------------------------------------------------

    /**
     * Updates any field EXCEPT the password. Allowed for the account owner or an
     * ADMIN; anybody else gets a plain 403 and no account is deactivated. A
     * username change invalidates the current token.
     */
    @Override
    @Transactional
    public UserResponse updateUser(Long targetId, UpdateUserRequest request) {
        authorizationService.requireSelfOrAdmin(targetId);

        User target = userRepository.findById(targetId)
                .orElseThrow(() -> new NotFoundException("No user found with id " + targetId, "USER_NOT_FOUND"));

        if (request.getUsername() != null && !request.getUsername().equals(target.getUsername())) {
            if (!passwordUtil.isUsernameValid(request.getUsername())) {
                throw new BadRequestException(USERNAME_RULE, "INVALID_USERNAME_PATTERN");
            }
            if (userRepository.existsByUsername(request.getUsername())) {
                throw new ConflictException(
                        "The username '" + request.getUsername() + "' is already in use", "USERNAME_TAKEN");
            }
            String previous = target.getUsername();
            target.setUsername(request.getUsername());
            target = userRepository.save(target);
            // Rule: changing the username invalidates the current token.
            tokenService.revokeActiveTokens(target);
            AuditLog.usernameChange(target.getId(), previous, target.getUsername());
        }

        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            target.setFullName(request.getFullName());
        }

        target = userRepository.save(target);
        return toResponse(target);
    }

    // ------------------------------------------------------------------
    // Password update
    // ------------------------------------------------------------------

    /**
     * Requires the old password plus the new password supplied twice. The
     * current token is invalidated in ALL cases, success and failure alike.
     *
     * Only a WRONG OLD PASSWORD counts towards the 3-strike lockout
     * (ASSUMPTIONS.md #8): once the caller has proven ownership by supplying the
     * correct old password, a mismatched confirmation or a weak new password is
     * a plain 400 that still revokes the token but does not advance the counter.
     *
     * {@code noRollbackFor}: every failure path here writes state that must
     * survive the exception reporting it - the incremented counter, the lockout
     * deactivation, and the unconditional token revocation.
     */
    @Override
    @Transactional(noRollbackFor = BadRequestException.class)
    public void updatePassword(Long targetId, UpdatePasswordRequest request) {
        // Not even an ADMIN may change somebody else's password - the old one is required.
        User target = authorizationService.requireSelf(targetId);

        if (!passwordUtil.matches(request.getOldPassword(), target.getPassword())) {
            target.setFailedPasswordAttempts(target.getFailedPasswordAttempts() + 1);
            boolean lockedOut = target.getFailedPasswordAttempts() >= MAX_CONSECUTIVE_FAILURES;
            int failures = target.getFailedPasswordAttempts();
            if (lockedOut) {
                target.setActive(false);
                target.setFailedPasswordAttempts(0);
            }
            userRepository.save(target);
            tokenService.revokeActiveTokens(target); // "in all cases"
            AuditLog.passwordChange(target.getUsername(), target.getId(), "FAILURE_WRONG_OLD_PASSWORD", failures);
            if (lockedOut) {
                AuditLog.lockout(target.getUsername(), target.getId(), "THREE_FAILED_PASSWORD_CHANGES");
            }
            throw new BadRequestException("The old password is incorrect", "WRONG_OLD_PASSWORD");
        }

        // Correct old password: the consecutive-failure streak is broken.
        target.setFailedPasswordAttempts(0);

        if (!request.getNewPassword().equals(request.getNewPasswordRepeat())) {
            userRepository.save(target);
            tokenService.revokeActiveTokens(target); // "in all cases"
            AuditLog.passwordChange(target.getUsername(), target.getId(), "FAILURE_CONFIRMATION_MISMATCH", 0);
            throw new BadRequestException(
                    "The new password and its confirmation do not match", "PASSWORD_CONFIRMATION_MISMATCH");
        }

        if (!passwordUtil.isPasswordValid(request.getNewPassword())) {
            userRepository.save(target);
            tokenService.revokeActiveTokens(target); // "in all cases"
            AuditLog.passwordChange(target.getUsername(), target.getId(), "FAILURE_WEAK_NEW_PASSWORD", 0);
            throw new BadRequestException(PASSWORD_RULE, "INVALID_PASSWORD_PATTERN");
        }

        target.setPassword(passwordUtil.hash(request.getNewPassword()));
        userRepository.save(target);
        tokenService.revokeActiveTokens(target); // "in all cases"
        AuditLog.passwordChange(target.getUsername(), target.getId(), "SUCCESS", 0);
    }

    // ------------------------------------------------------------------
    // Account status update (ADMIN only)
    // ------------------------------------------------------------------

    /** ADMIN-only activate/deactivate. Deactivation invalidates the current token. */
    @Override
    @Transactional
    public UserResponse updateStatus(Long targetId, UpdateStatusRequest request) {
        User admin = authorizationService.requireAdmin();

        User target = userRepository.findById(targetId)
                .orElseThrow(() -> new NotFoundException("No user found with id " + targetId, "USER_NOT_FOUND"));

        boolean activate = Boolean.TRUE.equals(request.getActive());
        target.setActive(activate);
        if (activate) {
            // A fresh start after re-activation: no stale failure streak.
            target.setFailedAuthAttempts(0);
            target.setFailedPasswordAttempts(0);
        }
        target = userRepository.save(target);
        if (!activate) {
            tokenService.revokeActiveTokens(target);
        }
        AuditLog.statusChange(admin.getUsername(), target.getUsername(), activate);
        return toResponse(target);
    }

    // ------------------------------------------------------------------
    // Deletion
    // ------------------------------------------------------------------

    /**
     * Self-delete or ADMIN delete. Removes the account AND every token it owns.
     * ADMIN accounts are non-deletable and the attempt is rejected with 403
     * (ASSUMPTIONS.md #26).
     *
     * There is no referential-integrity veto (ASSUMPTIONS.md #9): the user's
     * program roles are removed, screenings they submitted go with them, and
     * screenings they merely handled have the handler cleared.
     */
    @Override
    @Transactional
    public void deleteUser(Long targetId) {
        User actor = authorizationService.requireSelfOrAdmin(targetId);

        User target = userRepository.findById(targetId)
                .orElseThrow(() -> new NotFoundException("No user found with id " + targetId, "USER_NOT_FOUND"));

        if (target.getPermanentRole() == PermanentRole.ADMIN) {
            AuditLog.accessDenied(actor.getUsername(), "DELETE_USER:" + targetId, "TARGET_IS_ADMIN");
            throw new ForbiddenException("ADMIN accounts cannot be deleted", "ADMIN_NOT_DELETABLE");
        }

        String targetUsername = target.getUsername();

        // Detach the user from the cinema domain before removing the row, so the
        // delete never depends on database-level ON DELETE behaviour.
        List<ProgramRole> roles = programRoleRepository.findByUser(target);
        if (!roles.isEmpty()) {
            programRoleRepository.deleteAll(roles);
        }
        List<Screening> handled = screeningRepository.findByHandler(target);
        for (Screening screening : handled) {
            screening.setHandler(null);
        }
        if (!handled.isEmpty()) {
            screeningRepository.saveAll(handled);
        }
        List<Screening> submitted = screeningRepository.findBySubmitter(target);
        if (!submitted.isEmpty()) {
            screeningRepository.deleteAll(submitted);
        }

        // "Deletes the account and all associated tokens."
        tokenService.deleteAllTokens(target);
        userRepository.delete(target);

        AuditLog.accountDeleted(actor.getUsername(), targetUsername, targetId);
    }

    // ------------------------------------------------------------------
    // Profile view
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUser(Long targetId) {
        authorizationService.requireSelfOrAdmin(targetId);
        User target = userRepository.findById(targetId)
                .orElseThrow(() -> new NotFoundException("No user found with id " + targetId, "USER_NOT_FOUND"));
        return toResponse(target);
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .fullName(user.getFullName())
                .permanentRole(user.getPermanentRole())
                .active(user.isActive())
                .build();
    }
}
