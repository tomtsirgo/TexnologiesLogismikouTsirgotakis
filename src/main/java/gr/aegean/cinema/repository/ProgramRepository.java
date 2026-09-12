package gr.aegean.cinema.repository;

import gr.aegean.cinema.model.entity.Program;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

/**
 * JpaSpecificationExecutor μας επιτρέπει να εκτελούμε δυναμικά (runtime-built)
 * ερωτήματα αναζήτησης βάσει {@link org.springframework.data.jpa.domain.Specification},
 * απαραίτητο για την υλοποίηση του "AND semantics" search της Program αναζήτησης
 * όπου κάθε κριτήριο είναι προαιρετικό.
 */
public interface ProgramRepository extends JpaRepository<Program, Long>, JpaSpecificationExecutor<Program> {

    boolean existsByName(String name);

    Optional<Program> findByName(String name);
}
