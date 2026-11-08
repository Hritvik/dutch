package com.dutch.ledger.safety;

import com.dutch.ledger.direct.DirectExpenseRepository;
import com.dutch.ledger.group.GroupAccessDeniedException;
import com.dutch.ledger.group.GroupRepository;
import com.dutch.ledger.group.GroupView;
import com.dutch.ledger.group.MemberId;
import com.dutch.ledger.group.MemberResolver;
import com.dutch.ledger.series.RecurringSeries;
import com.dutch.ledger.series.SeriesRepository;
import com.dutch.ledger.series.SeriesView;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member's own authorized Ledger projection. It never exposes Obligation
 * Entries, internal stream/lock IDs, or another member's data.
 */
@Service
public class PrivacyExportService {

    private final GroupRepository groups;
    private final DirectExpenseRepository directExpenses;
    private final SeriesRepository series;
    private final MemberResolver members;

    public PrivacyExportService(GroupRepository groups, DirectExpenseRepository directExpenses,
                                SeriesRepository series, MemberResolver members) {
        this.groups = groups;
        this.directExpenses = directExpenses;
        this.series = series;
        this.members = members;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> export(String appSubject) {
        // A data-subject export is a legal right: it must not depend on the profile
        // readiness that gates financial work.
        MemberId actor = members.resolveForDataSubjectRights(appSubject)
                .orElseThrow(GroupAccessDeniedException::new);
        List<GroupView> visibleGroups = groups.findReadableBy(actor).stream()
                .map(group -> group.view())
                .toList();
        List<PrivacyDirectExpenseView> visibleDirect = directExpenses.findInvolving(actor).stream()
                .map(PrivacyDirectExpenseView::from)
                .toList();

        Map<String, SeriesView> visibleSeries = new LinkedHashMap<>();
        for (GroupView group : visibleGroups) {
            for (RecurringSeries found : series.findForGroup(group.id())) {
                visibleSeries.put(found.id().value(), SeriesView.from(found));
            }
        }
        for (RecurringSeries found : series.findForDirectMember(actor)) {
            visibleSeries.put(found.id().value(), SeriesView.from(found));
        }

        return Map.of(
                "memberId", actor.value(),
                "exportedAt", Instant.now().toString(),
                "groups", visibleGroups,
                "directExpenses", visibleDirect,
                "series", List.copyOf(visibleSeries.values()));
    }
}
