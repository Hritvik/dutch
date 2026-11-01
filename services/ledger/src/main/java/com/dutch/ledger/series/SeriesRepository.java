package com.dutch.ledger.series;

import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.MemberId;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SeriesRepository {
    void saveNew(RecurringSeries series);

    void update(RecurringSeries series, long expectedVersion);

    Optional<RecurringSeries> find(SeriesId id);

    List<RecurringSeries> findForGroup(GroupId groupId);

    List<RecurringSeries> findForDirectMember(MemberId memberId);

    void insertOccurrence(PendingOccurrence occurrence);

    void updateOccurrence(PendingOccurrence occurrence);

    Optional<PendingOccurrence> findOccurrence(OccurrenceId id);

    Optional<PendingOccurrence> findOccurrence(SeriesId seriesId, LocalDate periodStart);

    List<PendingOccurrence> occurrencesFor(SeriesId seriesId);
}
