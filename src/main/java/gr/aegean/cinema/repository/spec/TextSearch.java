package gr.aegean.cinema.repository.spec;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

import java.util.Arrays;

/**
 * The ONE implementation of DOMAIN_RULES.md's text-filter semantics, shared by
 * {@link ProgramSpecifications} and {@link ScreeningSpecifications}:
 *
 * <blockquote>"Text filters are case-insensitive, and ALL words entered must
 * appear in the field (title="star war" matches "Star Wars")."</blockquote>
 *
 * <p>The input is split on whitespace and every word becomes its own
 * {@code LIKE %word%} predicate against the lower-cased column; the predicates
 * are then ANDed together, so a field matches only when it contains every word.
 * A single whole-string {@code LIKE} would NOT satisfy the rule — that was the
 * defect INVENTORY.md recorded against {@code ProgramSpecifications}
 * (FR-PRG-19), and single-sourcing the logic here is what keeps the two
 * specification classes from drifting apart again.
 *
 * <p>Word order is irrelevant and the words need not be adjacent, which is what
 * makes {@code "war star"} match {@code "Star Wars"} just as {@code "star war"}
 * does.
 */
final class TextSearch {

    private TextSearch() {
    }

    /**
     * @param cb    the active criteria builder
     * @param field the column expression to match against; the caller supplies it
     *              already lower-cased so the comparison is case-insensitive
     * @param value the raw, user-supplied filter text (one or more words)
     * @return a predicate that is true only if EVERY word of {@code value}
     *         appears somewhere in {@code field}
     */
    static Predicate containsAllWords(CriteriaBuilder cb, Expression<String> field, String value) {
        String[] words = value.trim().toLowerCase().split("\\s+");
        Predicate[] predicates = Arrays.stream(words)
                .filter(word -> !word.isEmpty())
                .map(word -> cb.like(field, "%" + word + "%"))
                .toArray(Predicate[]::new);
        // An all-whitespace filter constrains nothing; the callers already refuse
        // blank filters, so this is a defensive identity rather than a live branch.
        return predicates.length == 0 ? cb.conjunction() : cb.and(predicates);
    }
}
