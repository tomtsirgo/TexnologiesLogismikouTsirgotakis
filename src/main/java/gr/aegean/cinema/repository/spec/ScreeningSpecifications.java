package gr.aegean.cinema.repository.spec;

import gr.aegean.cinema.model.entity.Program;
import gr.aegean.cinema.model.entity.Screening;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;

/**
 * Dynamic {@code Specification<Screening>} builders for searching the
 * screenings of ONE program (FR-SCR-37 … FR-SCR-41).
 *
 * <p>AND semantics between the different filters (title / cast / genre / date
 * range) falls out of the service chaining only the supplied criteria with
 * {@link Specification#and(Specification)} (FR-SCR-38); inside each text filter
 * ALL words entered must appear in the field, case-insensitively
 * (FR-SCR-39/40), which is delegated to the shared
 * {@link TextSearch#containsAllWords}.
 */
public final class ScreeningSpecifications {

    private ScreeningSpecifications() {
    }

    /** FR-SCR-37: a screening search is always scoped to a single program. */
    public static Specification<Screening> belongsToProgram(Program program) {
        return (root, query, cb) -> cb.equal(root.get("program"), program);
    }

    /**
     * Case-insensitive "every word must appear in this field" matching, used for
     * the film title, the cast and the genres.
     */
    public static Specification<Screening> textFieldContainsAllWords(String field, String value) {
        return (root, query, cb) -> TextSearch.containsAllWords(cb, cb.lower(root.get(field)), value);
    }

    public static Specification<Screening> startTimeFrom(LocalDateTime from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("startTime"), from);
    }

    public static Specification<Screening> startTimeTo(LocalDateTime to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("startTime"), to);
    }
}
