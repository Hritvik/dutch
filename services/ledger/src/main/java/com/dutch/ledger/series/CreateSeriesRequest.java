package com.dutch.ledger.series;

import com.dutch.ledger.expense.SplitMethod;
import java.time.LocalDate;
import java.util.List;

/** The explicit inputs for creating a recurring Series template. */
public record CreateSeriesRequest(
        ScopeRequest scope,
        String description,
        long totalMinorUnits,
        String currency,
        SplitMethod splitMethod,
        List<PayerRequest> payers,
        List<ParticipantRequest> participants,
        String cadence,
        int dueDay,
        String timezone,
        LocalDate nextPeriodStart) {

    public record ScopeRequest(String type, String groupId, List<String> memberIds) {
    }

    public record PayerRequest(String memberId, long minorUnits) {
    }

    public record ParticipantRequest(String memberId, Integer basisPoints, Long minorUnits) {
    }
}
