package com.dutch.ledger.series;

import java.time.LocalDate;
import java.time.Instant;

public record OccurrenceView(
        String id,
        String seriesId,
        long templateVersion,
        LocalDate periodStart,
        LocalDate periodEnd,
        Instant dueAt,
        String status,
        String expenseId) {

    public static OccurrenceView from(PendingOccurrence occurrence) {
        return new OccurrenceView(occurrence.id().value(), occurrence.seriesId().value(),
                occurrence.templateVersion(), occurrence.periodStart(), occurrence.periodEnd(),
                occurrence.dueAt(), occurrence.status().name(),
                occurrence.expenseId() == null ? null : occurrence.expenseId().value());
    }
}
