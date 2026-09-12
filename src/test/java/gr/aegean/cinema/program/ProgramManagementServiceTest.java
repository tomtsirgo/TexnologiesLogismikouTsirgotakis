package gr.aegean.cinema.program;

import gr.aegean.cinema.dto.program.AddRoleRequest;
import gr.aegean.cinema.dto.program.ProgramCreateRequest;
import gr.aegean.cinema.dto.program.ProgramResponse;
import gr.aegean.cinema.dto.program.ProgramUpdateRequest;
import gr.aegean.cinema.exception.BadRequestException;
import gr.aegean.cinema.exception.ConflictException;
import gr.aegean.cinema.exception.ForbiddenException;
import gr.aegean.cinema.exception.NotFoundException;
import gr.aegean.cinema.model.entity.Program;
import gr.aegean.cinema.model.entity.ProgramRole;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.PermanentRole;
import gr.aegean.cinema.model.enums.ProgramRoleType;
import gr.aegean.cinema.model.enums.ProgramState;
import gr.aegean.cinema.repository.ProgramRepository;
import gr.aegean.cinema.repository.ProgramRoleRepository;
import gr.aegean.cinema.repository.ScreeningRepository;
import gr.aegean.cinema.repository.UserRepository;
import gr.aegean.cinema.redaction.RedactionService;
import gr.aegean.cinema.security.AuthorizationService;
import gr.aegean.cinema.security.CurrentUserContext;
import gr.aegean.cinema.security.TokenService;
import gr.aegean.cinema.service.impl.ProgramServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Service-level coverage of program creation, update, role management and
 * deletion: FR-PRG-01 … FR-PRG-14, FR-PRG-24, FR-PRG-25 and
 * ROLE-04 / 06 / 07 / 17 / 18.
 *
 * <p>The {@link AuthorizationService} is deliberately the REAL one (only its
 * repositories are doubles), because the rules under test — "ADMIN is never a
 * cinema actor", "only a PROGRAMMER of this program", "ANNOUNCED freezes
 * everything" — live inside it. Mocking it away would make every one of these
 * assertions vacuous.
 */
class ProgramManagementServiceTest {

    private ProgramRepository programRepository;
    private ProgramRoleRepository programRoleRepository;
    private UserRepository userRepository;
    private AuthorizationService authorizationService;
    private ProgramServiceImpl programService;

    private User alice;   // creator of the program under test, therefore PROGRAMMER
    private User bob;     // a second PROGRAMMER, added later
    private User carol;   // an outsider / STAFF candidate
    private User admin;

    private Program program;

    @BeforeEach
    void setUp() {
        CurrentUserContext.clear();
        programRepository = mock(ProgramRepository.class);
        programRoleRepository = mock(ProgramRoleRepository.class);
        userRepository = mock(UserRepository.class);
        ScreeningRepository screeningRepository = mock(ScreeningRepository.class);
        authorizationService = new AuthorizationService(
                programRoleRepository, userRepository, mock(TokenService.class));
        RedactionService redactionService =
                new RedactionService(authorizationService, programRoleRepository, screeningRepository);
        programService = new ProgramServiceImpl(programRepository, programRoleRepository, screeningRepository,
                userRepository, authorizationService, redactionService);

        alice = user(1L, "alice01", PermanentRole.USER);
        bob = user(2L, "bob2024", PermanentRole.USER);
        carol = user(3L, "carol_x", PermanentRole.USER);
        admin = user(99L, "admin1", PermanentRole.ADMIN);

        program = Program.builder()
                .id(10L).name("Summer Fest").description("A summer season")
                .startDate(LocalDate.of(2026, 6, 1)).endDate(LocalDate.of(2026, 6, 30))
                .state(ProgramState.CREATED).creator(alice)
                .build();

        when(programRepository.findById(10L)).thenReturn(Optional.of(program));
        when(programRepository.save(any(Program.class))).thenAnswer(inv -> inv.getArgument(0));
        when(programRoleRepository.save(any(ProgramRole.class))).thenAnswer(inv -> inv.getArgument(0));
        when(programRoleRepository.findByProgramAndRole(any(), any())).thenReturn(List.of());
        when(userRepository.findByUsername("alice01")).thenReturn(Optional.of(alice));
        when(userRepository.findByUsername("bob2024")).thenReturn(Optional.of(bob));
        when(userRepository.findByUsername("carol_x")).thenReturn(Optional.of(carol));
        when(userRepository.findByUsername("admin1")).thenReturn(Optional.of(admin));

        // Alice holds PROGRAMMER on the program under test; nobody else holds anything.
        makeProgrammer(alice, program);
    }

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static User user(long id, String username, PermanentRole role) {
        return User.builder().id(id).username(username).password("H").fullName(username)
                .permanentRole(role).active(true).build();
    }

    private void makeProgrammer(User user, Program target) {
        when(programRoleRepository.existsByUserAndProgramAndRole(user, target, ProgramRoleType.PROGRAMMER))
                .thenReturn(true);
        when(programRoleRepository.existsByUserAndProgram(user, target)).thenReturn(true);
        when(programRoleRepository.findByUserAndProgram(user, target)).thenReturn(Optional.of(
                ProgramRole.builder().id(100L + user.getId()).user(user).program(target)
                        .role(ProgramRoleType.PROGRAMMER).build()));
    }

    private void makeStaff(User user, Program target) {
        when(programRoleRepository.existsByUserAndProgramAndRole(user, target, ProgramRoleType.STAFF))
                .thenReturn(true);
        when(programRoleRepository.existsByUserAndProgram(user, target)).thenReturn(true);
        when(programRoleRepository.findByUserAndProgram(user, target)).thenReturn(Optional.of(
                ProgramRole.builder().id(200L + user.getId()).user(user).program(target)
                        .role(ProgramRoleType.STAFF).build()));
    }

    private static ProgramCreateRequest createRequest(String name) {
        ProgramCreateRequest req = new ProgramCreateRequest();
        req.setName(name);
        req.setDescription("A brand new season");
        req.setStartDate(LocalDate.of(2027, 1, 1));
        req.setEndDate(LocalDate.of(2027, 2, 1));
        return req;
    }

    private static AddRoleRequest addRole(String username) {
        AddRoleRequest req = new AddRoleRequest();
        req.setUsername(username);
        return req;
    }

    // ==================================================================
    // Creation — FR-PRG-01 … FR-PRG-06, ROLE-04
    // ==================================================================

    @Test
    @DisplayName("FR-PRG-01: program creation is rejected with 409 when the name is already taken")
    void creationRejectsDuplicateName() {
        CurrentUserContext.set(alice);
        when(programRepository.existsByName("Summer Fest")).thenReturn(true);

        assertThatThrownBy(() -> programService.createProgram(createRequest("Summer Fest")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PROGRAM_NAME_TAKEN");

        verify(programRepository, never()).save(any(Program.class));
    }

    @Test
    @DisplayName("FR-PRG-01: program creation succeeds when the name is free")
    void creationAcceptsAFreeName() {
        CurrentUserContext.set(alice);
        when(programRepository.existsByName("Winter Fest")).thenReturn(false);
        when(programRepository.save(any(Program.class))).thenAnswer(inv -> {
            Program p = inv.getArgument(0);
            p.setId(55L);
            return p;
        });

        ProgramResponse response = programService.createProgram(createRequest("Winter Fest"));

        assertThat(response.getName()).isEqualTo("Winter Fest");
    }

    @Test
    @DisplayName("FR-PRG-05/FR-PRG-06: a new program starts in CREATED and its creator becomes a PROGRAMMER")
    void creationStartsInCreatedAndMakesTheCreatorAProgrammer() {
        CurrentUserContext.set(alice);
        when(programRepository.existsByName("Winter Fest")).thenReturn(false);
        when(programRepository.save(any(Program.class))).thenAnswer(inv -> {
            Program p = inv.getArgument(0);
            p.setId(55L);
            return p;
        });

        ProgramResponse response = programService.createProgram(createRequest("Winter Fest"));

        assertThat(response.getState()).isEqualTo(ProgramState.CREATED);
        assertThat(response.getCreatorUsername()).isEqualTo("alice01");
        verify(programRoleRepository).save(argThat(role ->
                role.getUser() == alice
                        && role.getRole() == ProgramRoleType.PROGRAMMER
                        && role.getProgram().getId().equals(55L)));
    }

    @Test
    @DisplayName("FR-PRG-03/FR-PRG-04: the service never sets the id or the creation date itself")
    void creationLeavesIdAndCreationDateToThePersistenceLayer() {
        CurrentUserContext.set(alice);
        when(programRepository.existsByName("Winter Fest")).thenReturn(false);

        programService.createProgram(createRequest("Winter Fest"));

        verify(programRepository).save(argThat(p -> p.getId() == null && p.getCreatedAt() == null));
    }

    @Test
    @DisplayName("FR-PRG-02: a program whose end date precedes its start date is rejected with 400")
    void creationRejectsInvertedDates() {
        CurrentUserContext.set(alice);
        ProgramCreateRequest request = createRequest("Winter Fest");
        request.setStartDate(LocalDate.of(2027, 3, 1));
        request.setEndDate(LocalDate.of(2027, 1, 1));

        assertThatThrownBy(() -> programService.createProgram(request))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "INVALID_PROGRAM_DATES");
    }

    @Test
    @DisplayName("ROLE-04: a plain USER holding no program role at all may create a program")
    void aPlainUserMayCreateAProgram() {
        CurrentUserContext.set(carol); // carol holds no role in anything
        when(programRepository.existsByName("Carol's Fest")).thenReturn(false);

        ProgramResponse response = programService.createProgram(createRequest("Carol's Fest"));

        assertThat(response.getCreatorUsername()).isEqualTo("carol_x");
    }

    @Test
    @DisplayName("ROLE-04: an ADMIN is rejected with 403 from program creation, like every cinema action")
    void anAdminMayNotCreateAProgram() {
        CurrentUserContext.set(admin);

        assertThatThrownBy(() -> programService.createProgram(createRequest("Admin Fest")))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_NOT_ALLOWED");

        verify(programRepository, never()).save(any(Program.class));
    }

    @Test
    @DisplayName("ROLE-04: an inactive account may not create a program")
    void anInactiveAccountMayNotCreateAProgram() {
        carol.setActive(false);
        CurrentUserContext.set(carol);

        assertThatThrownBy(() -> programService.createProgram(createRequest("Ghost Fest")))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ACCOUNT_INACTIVE");
    }

    // ==================================================================
    // Update — FR-PRG-07, FR-PRG-09, ROLE-06, ROLE-07
    // ==================================================================

    @Test
    @DisplayName("FR-PRG-07/ROLE-06: a PROGRAMMER of the program may change its name, description and dates")
    void aProgrammerMayUpdateNameDescriptionAndDates() {
        CurrentUserContext.set(alice);
        when(programRepository.existsByName("Summer Fest 2026")).thenReturn(false);

        ProgramUpdateRequest request = new ProgramUpdateRequest();
        request.setName("Summer Fest 2026");
        request.setDescription("Rewritten description");
        request.setStartDate(LocalDate.of(2026, 7, 1));
        request.setEndDate(LocalDate.of(2026, 7, 31));

        ProgramResponse response = programService.updateProgram(10L, request);

        assertThat(response.getName()).isEqualTo("Summer Fest 2026");
        assertThat(response.getDescription()).isEqualTo("Rewritten description");
        assertThat(response.getStartDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(response.getEndDate()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    @DisplayName("FR-PRG-07: renaming a program onto an existing name is rejected with 409")
    void updateRejectsADuplicateName() {
        CurrentUserContext.set(alice);
        when(programRepository.existsByName("Winter Fest")).thenReturn(true);

        ProgramUpdateRequest request = new ProgramUpdateRequest();
        request.setName("Winter Fest");

        assertThatThrownBy(() -> programService.updateProgram(10L, request))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PROGRAM_NAME_TAKEN");

        assertThat(program.getName()).isEqualTo("Summer Fest");
    }

    @Test
    @DisplayName("ROLE-07: a user who is not a PROGRAMMER of this program gets 403 when updating it")
    void anOutsiderCannotUpdateTheProgram() {
        CurrentUserContext.set(carol);

        ProgramUpdateRequest request = new ProgramUpdateRequest();
        request.setDescription("hijacked");

        assertThatThrownBy(() -> programService.updateProgram(10L, request))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_PROGRAMMER");

        assertThat(program.getDescription()).isEqualTo("A summer season");
    }

    @Test
    @DisplayName("FR-PRG-09/FR-PRG-T9: no program update is accepted once the program is ANNOUNCED")
    void noUpdateIsAcceptedAfterAnnouncement() {
        program.setState(ProgramState.ANNOUNCED);
        CurrentUserContext.set(alice);

        ProgramUpdateRequest request = new ProgramUpdateRequest();
        request.setDescription("too late");

        assertThatThrownBy(() -> programService.updateProgram(10L, request))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PROGRAM_ANNOUNCED_FROZEN");

        assertThat(program.getDescription()).isEqualTo("A summer season");
    }

    @Test
    @DisplayName("FR-PRG-09: a program update IS accepted in every state before ANNOUNCED")
    void updatesAreAcceptedInEveryStateBeforeAnnounced() {
        CurrentUserContext.set(alice);
        for (ProgramState state : ProgramState.values()) {
            if (state == ProgramState.ANNOUNCED) {
                continue;
            }
            program.setState(state);
            ProgramUpdateRequest request = new ProgramUpdateRequest();
            request.setDescription("description written in " + state);

            programService.updateProgram(10L, request);

            assertThat(program.getDescription()).isEqualTo("description written in " + state);
        }
    }

    @Test
    @DisplayName("FR-PRG-07: updating a program that does not exist is a 404")
    void updatingAnUnknownProgramIsNotFound() {
        CurrentUserContext.set(alice);
        when(programRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> programService.updateProgram(404L, new ProgramUpdateRequest()))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PROGRAM_NOT_FOUND");
    }

    // ==================================================================
    // PROGRAMMER set — FR-PRG-08, FR-PRG-10, FR-PRG-11
    // ==================================================================

    @Test
    @DisplayName("FR-PRG-10: an existing PROGRAMMER may add another PROGRAMMER")
    void aProgrammerMayAddAnotherProgrammer() {
        CurrentUserContext.set(alice);

        programService.addProgrammer(10L, addRole("bob2024"));

        verify(programRoleRepository).save(argThat(role ->
                role.getUser() == bob && role.getRole() == ProgramRoleType.PROGRAMMER));
    }

    @Test
    @DisplayName("FR-PRG-10: a non-PROGRAMMER may not add a PROGRAMMER and gets 403")
    void anOutsiderMayNotAddAProgrammer() {
        CurrentUserContext.set(carol);

        assertThatThrownBy(() -> programService.addProgrammer(10L, addRole("bob2024")))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_PROGRAMMER");

        verify(programRoleRepository, never()).save(any(ProgramRole.class));
    }

    @Test
    @DisplayName("FR-PRG-11/ROLE-17: adding a PROGRAMMER who already holds a role in the program is a 409")
    void addingAProgrammerWhoAlreadyHoldsARoleIsRejected() {
        CurrentUserContext.set(alice);
        makeStaff(bob, program);

        assertThatThrownBy(() -> programService.addProgrammer(10L, addRole("bob2024")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ROLE_ALREADY_ASSIGNED");

        verify(programRoleRepository, never()).save(any(ProgramRole.class));
    }

    @Test
    @DisplayName("FR-PRG-10: adding an unknown username as PROGRAMMER is a 404")
    void addingAnUnknownUserAsProgrammerIsNotFound() {
        CurrentUserContext.set(alice);
        when(userRepository.findByUsername("ghost99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> programService.addProgrammer(10L, addRole("ghost99")))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "USER_NOT_FOUND");
    }

    @Test
    @DisplayName("FR-PRG-10: an ADMIN account can never be granted a role inside a program")
    void anAdminCannotBeGrantedAProgramRole() {
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> programService.addProgrammer(10L, addRole("admin1")))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_NOT_ALLOWED");

        verify(programRoleRepository, never()).save(any(ProgramRole.class));
    }

    @Test
    @DisplayName("FR-PRG-08: the creator can never be removed from the PROGRAMMERS set")
    void theCreatorCanNeverBeRemovedFromTheProgrammersSet() {
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> programService.removeProgrammer(10L, "alice01"))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "CREATOR_NOT_REMOVABLE");

        verify(programRoleRepository, never()).delete(any(ProgramRole.class));
    }

    @Test
    @DisplayName("FR-PRG-08: the creator is protected even from another PROGRAMMER of the same program")
    void anotherProgrammerCannotRemoveTheCreatorEither() {
        makeProgrammer(bob, program);
        CurrentUserContext.set(bob);

        assertThatThrownBy(() -> programService.removeProgrammer(10L, "alice01"))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "CREATOR_NOT_REMOVABLE");

        verify(programRoleRepository, never()).delete(any(ProgramRole.class));
    }

    @Test
    @DisplayName("FR-PRG-08: a PROGRAMMER who is NOT the creator can be removed")
    void aNonCreatorProgrammerCanBeRemoved() {
        makeProgrammer(bob, program);
        CurrentUserContext.set(alice);

        programService.removeProgrammer(10L, "bob2024");

        verify(programRoleRepository).delete(argThat(role ->
                role.getUser() == bob && role.getRole() == ProgramRoleType.PROGRAMMER));
    }

    @Test
    @DisplayName("FR-PRG-08: removing a PROGRAMMER role the target does not hold is a 404")
    void removingARoleThatIsNotHeldIsNotFound() {
        CurrentUserContext.set(alice);
        when(programRoleRepository.findByUserAndProgram(carol, program)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> programService.removeProgrammer(10L, "carol_x"))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ROLE_NOT_ASSIGNED");
    }

    // ==================================================================
    // STAFF set — FR-PRG-12, FR-PRG-13, FR-PRG-14
    // ==================================================================

    @Test
    @DisplayName("FR-PRG-12: an existing PROGRAMMER may add a STAFF member while the program is in CREATED")
    void aProgrammerMayAddStaffWhileInCreated() {
        CurrentUserContext.set(alice);

        programService.addStaff(10L, addRole("carol_x"));

        verify(programRoleRepository).save(argThat(role ->
                role.getUser() == carol && role.getRole() == ProgramRoleType.STAFF));
    }

    @Test
    @DisplayName("FR-PRG-12: a non-PROGRAMMER may not add a STAFF member and gets 403")
    void anOutsiderMayNotAddStaff() {
        CurrentUserContext.set(carol);

        assertThatThrownBy(() -> programService.addStaff(10L, addRole("bob2024")))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_PROGRAMMER");

        verify(programRoleRepository, never()).save(any(ProgramRole.class));
    }

    @Test
    @DisplayName("FR-PRG-13/ROLE-17: adding a STAFF member who already holds a role in the program is a 409")
    void addingStaffWhoAlreadyHoldsARoleIsRejected() {
        CurrentUserContext.set(alice);

        // Alice already holds PROGRAMMER here, so she cannot also become STAFF.
        assertThatThrownBy(() -> programService.addStaff(10L, addRole("alice01")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ROLE_ALREADY_ASSIGNED");

        verify(programRoleRepository, never()).save(any(ProgramRole.class));
    }

    @ParameterizedTest
    @EnumSource(value = ProgramState.class, names = "CREATED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("FR-PRG-14: the STAFF set is frozen in every state after CREATED — additions are 409")
    void staffAdditionsAreFrozenAfterCreated(ProgramState state) {
        program.setState(state);
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> programService.addStaff(10L, addRole("carol_x")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "STAFF_SET_FROZEN");

        verify(userRepository, never()).findByUsername("carol_x");
        verify(programRoleRepository, never()).save(any(ProgramRole.class));
    }

    @ParameterizedTest
    @EnumSource(value = ProgramState.class, names = "CREATED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("FR-PRG-14: the STAFF set is frozen in every state after CREATED — removals are 409 too")
    void staffRemovalsAreFrozenAfterCreated(ProgramState state) {
        makeStaff(carol, program);
        program.setState(state);
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> programService.removeStaff(10L, "carol_x"))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "STAFF_SET_FROZEN");

        verify(programRoleRepository, never()).delete(any(ProgramRole.class));
    }

    @Test
    @DisplayName("FR-PRG-14: a STAFF member can still be removed while the program is in CREATED")
    void staffCanBeRemovedWhileTheProgramIsInCreated() {
        makeStaff(carol, program);
        CurrentUserContext.set(alice);

        programService.removeStaff(10L, "carol_x");

        verify(programRoleRepository).delete(argThat(role ->
                role.getUser() == carol && role.getRole() == ProgramRoleType.STAFF));
    }

    // ==================================================================
    // ROLE-17 / ROLE-18 — role cardinality across programs
    // ==================================================================

    @Test
    @DisplayName("ROLE-18: the same user may hold STAFF in one program and PROGRAMMER in another")
    void aUserMayHoldDifferentRolesInDifferentPrograms() {
        Program other = Program.builder()
                .id(20L).name("Autumn Fest").description("Another season")
                .startDate(LocalDate.of(2026, 9, 1)).endDate(LocalDate.of(2026, 9, 30))
                .state(ProgramState.CREATED).creator(bob)
                .build();
        when(programRepository.findById(20L)).thenReturn(Optional.of(other));
        makeProgrammer(bob, other);

        // carol is STAFF of program #10 ...
        CurrentUserContext.set(alice);
        programService.addStaff(10L, addRole("carol_x"));
        verify(programRoleRepository).save(argThat(role ->
                role.getUser() == carol && role.getProgram() == program
                        && role.getRole() == ProgramRoleType.STAFF));

        // ... and that does not stop her becoming PROGRAMMER of program #20.
        CurrentUserContext.set(bob);
        programService.addProgrammer(20L, addRole("carol_x"));
        verify(programRoleRepository).save(argThat(role ->
                role.getUser() == carol && role.getProgram() == other
                        && role.getRole() == ProgramRoleType.PROGRAMMER));
    }

    @Test
    @DisplayName("ROLE-17: the at-most-one-role check is scoped to the program, not to the user globally")
    void theRoleUniquenessCheckIsScopedToOneProgram() {
        CurrentUserContext.set(alice);
        makeStaff(carol, program);

        assertThatThrownBy(() -> programService.addProgrammer(10L, addRole("carol_x")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ROLE_ALREADY_ASSIGNED");

        verify(programRoleRepository).existsByUserAndProgram(carol, program);
    }

    // ==================================================================
    // Deletion — FR-PRG-24, FR-PRG-25
    // ==================================================================

    @Test
    @DisplayName("FR-PRG-24/FR-PRG-25: a PROGRAMMER may delete the program while it is in CREATED")
    void aProgrammerMayDeleteAProgramInCreated() {
        CurrentUserContext.set(alice);

        programService.deleteProgram(10L);

        verify(programRepository).delete(program);
    }

    @ParameterizedTest
    @EnumSource(value = ProgramState.class, names = "CREATED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("FR-PRG-24: deletion is rejected with 409 in every state other than CREATED")
    void deletionIsRejectedOutsideCreated(ProgramState state) {
        program.setState(state);
        CurrentUserContext.set(alice);

        assertThatThrownBy(() -> programService.deleteProgram(10L))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("errorCode", "PROGRAM_NOT_DELETABLE");

        verify(programRepository, never()).delete(any(Program.class));
    }

    @Test
    @DisplayName("FR-PRG-25: a user who is not a PROGRAMMER of the program gets 403 when deleting it")
    void anOutsiderCannotDeleteTheProgram() {
        CurrentUserContext.set(carol);

        assertThatThrownBy(() -> programService.deleteProgram(10L))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_PROGRAMMER");

        verify(programRepository, never()).delete(any(Program.class));
    }

    @Test
    @DisplayName("FR-PRG-25: the actor check runs before the state check, so an outsider always sees 403")
    void theActorCheckPrecedesTheStateCheckOnDeletion() {
        program.setState(ProgramState.ANNOUNCED); // would be 409 for a PROGRAMMER
        CurrentUserContext.set(carol);

        assertThatThrownBy(() -> programService.deleteProgram(10L))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_PROGRAMMER");
    }

    @Test
    @DisplayName("FR-PRG-24: an ADMIN is rejected from program deletion before anything else is checked")
    void anAdminCannotDeleteAProgram() {
        CurrentUserContext.set(admin);

        assertThatThrownBy(() -> programService.deleteProgram(10L))
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("errorCode", "ADMIN_NOT_ALLOWED");

        verify(programRepository, never()).findById(any());
    }
}
