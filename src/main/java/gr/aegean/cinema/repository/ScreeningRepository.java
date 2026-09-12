package gr.aegean.cinema.repository;

import gr.aegean.cinema.model.entity.Program;
import gr.aegean.cinema.model.entity.Screening;
import gr.aegean.cinema.model.entity.User;
import gr.aegean.cinema.model.enums.ScreeningState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface ScreeningRepository extends JpaRepository<Screening, Long>, JpaSpecificationExecutor<Screening> {

    List<Screening> findByProgramAndState(Program program, ScreeningState state);

    /**
     * Every screening of one program, in any state. Used by the central
     * redaction layer to derive a program's "auditorium" view field, which is
     * not a stored column (ASSUMPTIONS.md #7).
     */
    List<Screening> findByProgram(Program program);

    List<Screening> findByHandler(User handler);

    List<Screening> findBySubmitter(User submitter);
}
