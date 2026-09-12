package gr.aegean.cinema.repository;

import gr.aegean.cinema.model.entity.Program;
import gr.aegean.cinema.model.entity.ProgramRole;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.ProgramRoleType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProgramRoleRepository extends JpaRepository<ProgramRole, Long> {

    Optional<ProgramRole> findByUserAndProgram(User user, Program program);

    List<ProgramRole> findByProgramAndRole(Program program, ProgramRoleType role);

    List<ProgramRole> findByUser(User user);

    boolean existsByUserAndProgram(User user, Program program);

    boolean existsByUserAndProgramAndRole(User user, Program program, ProgramRoleType role);
}
