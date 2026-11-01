package com.dutch.ledger.series;

import com.dutch.ledger.group.MemberId;
import java.util.Objects;

/**
 * One template participant. EQUAL uses neither value, PERCENTAGE uses basis
 * points, and EXACT uses minor units.
 */
public record SeriesParticipant(MemberId memberId, Integer basisPoints, Long minorUnits) {

    public SeriesParticipant {
        Objects.requireNonNull(memberId, "memberId");
        if (basisPoints != null && (basisPoints < 1 || basisPoints > 10_000)) {
            throw new IllegalArgumentException("basis points must be between 1 and 10000");
        }
        if (minorUnits != null && minorUnits < 0) {
            throw new IllegalArgumentException("a participant amount cannot be negative");
        }
    }

    public static SeriesParticipant of(MemberId memberId) {
        return new SeriesParticipant(memberId, null, null);
    }

    public static SeriesParticipant percentage(MemberId memberId, int basisPoints) {
        return new SeriesParticipant(memberId, basisPoints, null);
    }

    public static SeriesParticipant exact(MemberId memberId, long minorUnits) {
        return new SeriesParticipant(memberId, null, minorUnits);
    }
}
