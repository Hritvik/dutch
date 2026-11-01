package com.dutch.ledger.series;

/** The reviewed occurrence and the normal Expense it produced on submission. */
public record SeriesSubmitResult(OccurrenceView occurrence, String expenseId) {
}
