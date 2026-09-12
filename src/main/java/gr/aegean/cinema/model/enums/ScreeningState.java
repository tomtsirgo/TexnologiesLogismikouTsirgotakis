package gr.aegean.cinema.model.enums;

/**
 * Ο κύκλος ζωής (lifecycle) μιας {@link gr.aegean.cinema.model.entity.Screening}.
 *
 * CREATED -> SUBMITTED -> REVIEWED -> APPROVED -> SCHEDULED (τελική)
 *                                            \--> REJECTED (τελική, από οπουδήποτε)
 */
public enum ScreeningState {
    CREATED,
    SUBMITTED,
    REVIEWED,
    APPROVED,
    SCHEDULED,
    REJECTED
}
