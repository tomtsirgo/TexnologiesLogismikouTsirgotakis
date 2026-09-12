package gr.aegean.cinema.model.enums;

/**
 * The lifecycle of a {@link gr.aegean.cinema.model.entity.Program}. Transitions
 * are STRICTLY sequential and never reversible.
 *
 * CREATED --(submission start)--> SUBMISSION
 * SUBMISSION --(stage-manager assignment start)--> ASSIGNMENT
 * ASSIGNMENT --(review start)--> REVIEW
 * REVIEW --(schedule making)--> SCHEDULING
 * SCHEDULING --(final submission start)--> FINAL_SUBMISSION
 * FINAL_SUBMISSION --(decision making)--> DECISION
 * DECISION --(program announcement)--> ANNOUNCED
 *
 * Per DOMAIN_RULES.md (authoritative - see ASSUMPTIONS.md #1), this state is
 * named FINAL_SUBMISSION - matching pages 6-7 of the assignment PDF - rather
 * than the alternate name used only once, on page 3 of that same PDF.
 */
public enum ProgramState {
    CREATED,
    SUBMISSION,
    ASSIGNMENT,
    REVIEW,
    SCHEDULING,
    FINAL_SUBMISSION,
    DECISION,
    ANNOUNCED
}
