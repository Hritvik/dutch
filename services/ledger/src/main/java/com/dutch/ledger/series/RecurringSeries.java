package com.dutch.ledger.series;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.util.Objects;

/**
 * A recurring template that never creates debt by itself. Each due period is an
 * explicit Pending Occurrence that a member may review, skip, or submit.
 */
public record RecurringSeries(
        SeriesId id,
        SeriesScope scope,
        SeriesTemplate template,
        Cadence cadence,
        int dueDay,
        ZoneId timezone,
        SeriesStatus status,
        LocalDate nextPeriodStart,
        long version,
        Instant createdAt) {

    public RecurringSeries {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(cadence, "cadence");
        Objects.requireNonNull(timezone, "timezone");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(nextPeriodStart, "nextPeriodStart");
        Objects.requireNonNull(createdAt, "createdAt");
        if (version < 1) {
            throw new IllegalArgumentException("a series version starts at one");
        }
        cadence.validateDueDay(dueDay);
        if (!nextPeriodStart.equals(cadence.periodStart(nextPeriodStart))) {
            throw new IllegalArgumentException("nextPeriodStart must start a due period");
        }
    }

    public static RecurringSeries create(SeriesId id, SeriesScope scope, SeriesTemplate template,
                                         Cadence cadence, int dueDay, ZoneId timezone,
                                         LocalDate nextPeriodStart, Instant createdAt) {
        return new RecurringSeries(id, scope, template, cadence, dueDay, timezone,
                SeriesStatus.ACTIVE, nextPeriodStart, 1, createdAt);
    }

    public Instant dueAt(LocalDate periodStart) {
        cadence.validateDueDay(dueDay);
        return cadence.dueInstant(periodStart, dueDay, timezone);
    }

    /** Creates one explicit pending item for the next due period; it holds no debt. */
    public PendingOccurrence occurrenceFor(OccurrenceId occurrenceId, LocalDate periodStart) {
        if (status != SeriesStatus.ACTIVE) {
            throw new IllegalStateException("a paused or archived series has no new occurrences");
        }
        if (!periodStart.equals(cadence.periodStart(periodStart))) {
            throw new IllegalArgumentException("an occurrence must start a due period");
        }
        if (periodStart.isBefore(nextPeriodStart)) {
            throw new IllegalArgumentException("that period has already been generated");
        }
        return PendingOccurrence.pending(occurrenceId, id, template, cadence, dueDay, timezone,
                periodStart, version);
    }

    /** Advances the series pointer after an occurrence is generated or skipped. */
    public RecurringSeries withNextPeriodStart(LocalDate next) {
        if (!next.isAfter(nextPeriodStart) || !next.equals(cadence.periodStart(next))) {
            throw new IllegalArgumentException("the next period must move forward and align");
        }
        return new RecurringSeries(id, scope, template, cadence, dueDay, timezone, status,
                next, version + 1, createdAt);
    }

    public RecurringSeries paused() {
        if (status == SeriesStatus.ARCHIVED) {
            throw new IllegalStateException("an archived series cannot be paused");
        }
        return new RecurringSeries(id, scope, template, cadence, dueDay, timezone,
                SeriesStatus.PAUSED, nextPeriodStart, version + 1, createdAt);
    }

    public RecurringSeries resumed() {
        if (status != SeriesStatus.PAUSED) {
            throw new IllegalStateException("only a paused series can resume");
        }
        return new RecurringSeries(id, scope, template, cadence, dueDay, timezone,
                SeriesStatus.ACTIVE, nextPeriodStart, version + 1, createdAt);
    }

    public RecurringSeries archived() {
        return new RecurringSeries(id, scope, template, cadence, dueDay, timezone,
                SeriesStatus.ARCHIVED, nextPeriodStart, version + 1, createdAt);
    }
}
