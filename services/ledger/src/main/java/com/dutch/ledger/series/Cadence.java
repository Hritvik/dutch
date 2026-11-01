package com.dutch.ledger.series;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.Instant;

/** Weekly or monthly recurrence; all boundaries are local dates in the series zone. */
public enum Cadence {
    WEEKLY,
    MONTHLY;

    public LocalDate periodStart(LocalDate date) {
        return switch (this) {
            case WEEKLY -> date.with(DayOfWeek.MONDAY);
            case MONTHLY -> date.withDayOfMonth(1);
        };
    }

    public LocalDate periodEnd(LocalDate periodStart) {
        return switch (this) {
            case WEEKLY -> periodStart.plusDays(6);
            case MONTHLY -> periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        };
    }

    public LocalDate nextPeriodStart(LocalDate periodStart) {
        return switch (this) {
            case WEEKLY -> periodStart.plusWeeks(1);
            case MONTHLY -> periodStart.plusMonths(1);
        };
    }

    /** A due day is 1..7 for weekly and 1..31 (clamped) for monthly. */
    public LocalDate dueDate(LocalDate periodStart, int dueDay) {
        return switch (this) {
            case WEEKLY -> periodStart.plusDays(dayOfWeek(dueDay) - 1L);
            case MONTHLY -> {
                int day = Math.min(dueDay, YearMonth.from(periodStart).lengthOfMonth());
                yield periodStart.withDayOfMonth(day);
            }
        };
    }

    public Instant dueInstant(LocalDate periodStart, int dueDay, ZoneId zone) {
        return dueDate(periodStart, dueDay).atStartOfDay(zone).toInstant();
    }

    public void validateDueDay(int dueDay) {
        int max = this == WEEKLY ? 7 : 31;
        if (dueDay < 1 || dueDay > max) {
            throw new IllegalArgumentException("dueDay must be between 1 and " + max);
        }
    }

    private static int dayOfWeek(int dueDay) {
        return dueDay;
    }
}
