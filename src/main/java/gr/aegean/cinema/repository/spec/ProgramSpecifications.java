package gr.aegean.cinema.repository.spec;

import gr.aegean.cinema.model.entity.Program;
import gr.aegean.cinema.model.entity.Screening;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;

/**
 * Dynamic {@code Specification<Program>} builders for program search
 * (FR-PRG-15 … FR-PRG-19).
 *
 * <p>Each method returns a partial specification testing exactly ONE criterion;
 * the service chains only the criteria that were actually supplied with
 * {@link Specification#and(Specification)}, which is what implements the
 * required AND semantics (FR-PRG-16) and, as a side effect, "no criteria
 * supplied → everything the requester may see" (FR-PRG-17).
 *
 * <p>Every text filter goes through {@link TextSearch#containsAllWords}, so all
 * of them are case-insensitive (FR-PRG-18) and require EVERY word entered to
 * appear in the field (FR-PRG-19). The migrated code used a single whole-string
 * {@code LIKE %value%} for {@link #nameContains}/{@link #descriptionContains},
 * silently failing FR-PRG-19 while the screening side implemented it correctly
 * (INVENTORY.md); both sides now share one implementation.
 */
public final class ProgramSpecifications {

    private ProgramSpecifications() {
    }

    public static Specification<Program> nameContains(String name) {
        return (root, query, cb) -> TextSearch.containsAllWords(cb, cb.lower(root.get("name")), name);
    }

    public static Specification<Program> descriptionContains(String description) {
        return (root, query, cb) ->
                TextSearch.containsAllWords(cb, cb.lower(root.get("description")), description);
    }

    /** ASSUMPTIONS.md #18: the date filters test the PROGRAM's own season dates. */
    public static Specification<Program> startsFrom(LocalDate from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("startDate"), from);
    }

    public static Specification<Program> endsBefore(LocalDate to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("endDate"), to);
    }

    /**
     * Programs holding at least one screening whose film title contains every
     * word of the filter. The join makes one program match through one of its
     * screenings, and {@code distinct} keeps a program with several matching
     * screenings from appearing more than once.
     */
    public static Specification<Program> hasFilmTitle(String filmTitle) {
        return (root, query, cb) -> {
            query.distinct(true);
            Join<Program, Screening> screenings = root.join("screenings", JoinType.INNER);
            return TextSearch.containsAllWords(cb, cb.lower(screenings.get("filmTitle")), filmTitle);
        };
    }

    /** Programs holding at least one screening in an auditorium matching every word of the filter. */
    public static Specification<Program> hasAuditorium(String auditorium) {
        return (root, query, cb) -> {
            query.distinct(true);
            Join<Program, Screening> screenings = root.join("screenings", JoinType.INNER);
            return TextSearch.containsAllWords(cb, cb.lower(screenings.get("auditoriumName")), auditorium);
        };
    }
}
