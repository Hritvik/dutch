package com.dutch.ledger.series;

import com.dutch.ledger.expense.PayerContribution;
import java.time.LocalDate;
import java.util.List;

public record SeriesView(
        String id,
        ScopeView scope,
        String description,
        long totalMinorUnits,
        String currency,
        String splitMethod,
        List<PayerContribution> payers,
        List<SeriesParticipant> participants,
        String cadence,
        int dueDay,
        String timezone,
        String status,
        LocalDate nextPeriodStart,
        long version) {

    public record ScopeView(String type, String groupId, List<String> memberIds) {
    }

    public static SeriesView from(RecurringSeries series) {
        SeriesScope scope = series.scope();
        return new SeriesView(series.id().value(),
                new ScopeView(scope.type().name(),
                        scope.groupId() == null ? null : scope.groupId().value(),
                        scope.memberIds().stream().map(member -> member.value()).toList()),
                series.template().description(), series.template().totalMinorUnits(),
                series.template().currency(), series.template().splitMethod().name(),
                series.template().payers(), series.template().participants(),
                series.cadence().name(), series.dueDay(), series.timezone().getId(),
                series.status().name(), series.nextPeriodStart(), series.version());
    }
}
