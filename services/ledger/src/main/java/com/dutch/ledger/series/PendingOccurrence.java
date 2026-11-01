package com.dutch.ledger.series;

import com.dutch.ledger.expense.ExpenseId;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.util.Objects;

/**
 * A reviewable due period. It is evidence only: no Obligation Entry or balance
 * exists until a member explicitly submits it as an Expense.
 */
public record PendingOccurrence(
        OccurrenceId id,
        SeriesId seriesId,
        long templateVersion,
        LocalDate periodStart,
        LocalDate periodEnd,
        Instant dueAt,
        OccurrenceStatus status,
        ExpenseId expenseId) {

    public PendingOccurrence {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(seriesId, "seriesId");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        Objects.requireNonNull(dueAt, "dueAt");
        Objects.requireNonNull(status, "status");
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("the occurrence period is inverted");
        }
        if ((status == OccurrenceStatus.SUBMITTED) != (expenseId != null)) {
            throw new IllegalArgumentException("only a submitted occurrence carries an expense");
        }
    }

    public static PendingOccurrence pending(OccurrenceId id, SeriesId seriesId,
                                            SeriesTemplate template, Cadence cadence,
                                            int dueDay, ZoneId timezone,
                                            LocalDate periodStart, long templateVersion) {
        Objects.requireNonNull(template, "template");
        return new PendingOccurrence(id, seriesId, templateVersion, periodStart,
                cadence.periodEnd(periodStart), cadence.dueInstant(periodStart, dueDay, timezone),
                OccurrenceStatus.PENDING, null);
    }

    public PendingOccurrence submitted(ExpenseId submittedExpense) {
        requirePending();
        return new PendingOccurrence(id, seriesId, templateVersion, periodStart, periodEnd,
                dueAt, OccurrenceStatus.SUBMITTED, Objects.requireNonNull(submittedExpense));
    }

    public PendingOccurrence skipped() {
        requirePending();
        return new PendingOccurrence(id, seriesId, templateVersion, periodStart, periodEnd,
                dueAt, OccurrenceStatus.SKIPPED, null);
    }

    public PendingOccurrence missed() {
        requirePending();
        return new PendingOccurrence(id, seriesId, templateVersion, periodStart, periodEnd,
                dueAt, OccurrenceStatus.MISSED, null);
    }

    private void requirePending() {
        if (status != OccurrenceStatus.PENDING) {
            throw new IllegalStateException("this occurrence is no longer pending");
        }
    }
}
