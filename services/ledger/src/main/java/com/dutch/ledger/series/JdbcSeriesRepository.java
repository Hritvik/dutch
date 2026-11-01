package com.dutch.ledger.series;

import com.dutch.ledger.expense.ExpenseId;
import com.dutch.ledger.expense.PayerContribution;
import com.dutch.ledger.expense.SplitMethod;
import com.dutch.ledger.group.ConcurrentGroupUpdateException;
import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.MemberId;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Series template, lifecycle, and occurrence persistence. */
@Repository
public class JdbcSeriesRepository implements SeriesRepository {

    private final JdbcTemplate jdbc;

    public JdbcSeriesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void saveNew(RecurringSeries series) {
        jdbc.update("""
                INSERT INTO expense_series (series_id, scope_type, group_id, currency,
                    description, total_minor_units, split_method, cadence, due_day, timezone,
                    status, next_period_start, template_version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, series.id().value(), series.scope().type().name(),
                series.scope().groupId() == null ? null : series.scope().groupId().value(),
                series.template().currency(), series.template().description(),
                series.template().totalMinorUnits(), series.template().splitMethod().name(),
                series.cadence().name(), series.dueDay(), series.timezone().getId(),
                series.status().name(), Date.valueOf(series.nextPeriodStart()), series.version(),
                Timestamp.from(series.createdAt()));
        for (PayerContribution payer : series.template().payers()) {
            jdbc.update("""
                    INSERT INTO series_payer (series_id, member_id, minor_units)
                    VALUES (?, ?, ?)
                    """, series.id().value(), payer.memberId().value(), payer.minorUnits());
        }
        for (SeriesParticipant participant : series.template().participants()) {
            jdbc.update("""
                    INSERT INTO series_participant
                        (series_id, member_id, basis_points, minor_units)
                    VALUES (?, ?, ?, ?)
                    """, series.id().value(), participant.memberId().value(),
                    participant.basisPoints(), participant.minorUnits());
        }
    }

    /** Advances status/next period; a template edit is a later command. */
    @Override
    @Transactional
    public void update(RecurringSeries series, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE expense_series
                SET status = ?, next_period_start = ?, template_version = ?, updated_at = now()
                WHERE series_id = ? AND template_version = ?
                """, series.status().name(), Date.valueOf(series.nextPeriodStart()),
                series.version(), series.id().value(), expectedVersion);
        if (updated == 0) {
            throw new ConcurrentGroupUpdateException();
        }
    }

    @Override
    public Optional<RecurringSeries> find(SeriesId id) {
        List<SeriesRow> rows = jdbc.query("""
                SELECT scope_type, group_id, currency, description, total_minor_units,
                    split_method, cadence, due_day, timezone, status, next_period_start,
                    template_version, created_at
                FROM expense_series WHERE series_id = ?
                """, (result, index) -> new SeriesRow(
                result.getString("scope_type"),
                result.getString("group_id"),
                result.getString("currency"),
                result.getString("description"),
                result.getLong("total_minor_units"),
                result.getString("split_method"),
                result.getString("cadence"),
                result.getInt("due_day"),
                result.getString("timezone"),
                result.getString("status"),
                result.getDate("next_period_start").toLocalDate(),
                result.getLong("template_version"),
                result.getTimestamp("created_at").toInstant()), id.value());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        SeriesRow row = rows.get(0);
        List<PayerContribution> payers = jdbc.query("""
                SELECT member_id, minor_units FROM series_payer
                WHERE series_id = ? ORDER BY member_id
                """, (result, index) -> new PayerContribution(
                new MemberId(result.getString("member_id")),
                result.getLong("minor_units")), id.value());
        List<SeriesParticipant> participants = jdbc.query("""
                SELECT member_id, basis_points, minor_units FROM series_participant
                WHERE series_id = ? ORDER BY member_id
                """, (result, index) -> new SeriesParticipant(
                new MemberId(result.getString("member_id")),
                (Integer) result.getObject("basis_points"),
                result.getObject("minor_units") == null ? null : result.getLong("minor_units")),
                id.value());
        return Optional.of(new RecurringSeries(id,
                row.scopeType().equals("GROUP")
                        ? SeriesScope.group(new GroupId(row.groupId()))
                        : SeriesScope.direct(participants.stream()
                                .map(SeriesParticipant::memberId).toList()),
                new SeriesTemplate(row.description(), row.totalMinorUnits(), row.currency(),
                        SplitMethod.valueOf(row.splitMethod()), payers, participants),
                Cadence.valueOf(row.cadence()), row.dueDay(), ZoneId.of(row.timezone()),
                SeriesStatus.valueOf(row.status()), row.nextPeriodStart(), row.templateVersion(),
                row.createdAt()));
    }

    @Override
    public List<RecurringSeries> findForGroup(GroupId groupId) {
        return findIds("SELECT series_id FROM expense_series WHERE scope_type = 'GROUP' "
                + "AND group_id = ? ORDER BY created_at", groupId.value());
    }

    @Override
    public List<RecurringSeries> findForDirectMember(MemberId memberId) {
        return findIds("""
                SELECT s.series_id FROM expense_series s
                JOIN series_participant p ON p.series_id = s.series_id
                WHERE s.scope_type = 'DIRECT' AND p.member_id = ?
                ORDER BY s.created_at
                """, memberId.value());
    }

    @Override
    public List<PendingOccurrence> occurrencesFor(SeriesId seriesId) {
        return jdbc.query("""
                SELECT occurrence_id, series_id, template_version, period_start, period_end,
                    due_at, status, expense_id
                FROM series_occurrence WHERE series_id = ? ORDER BY period_start
                """, (result, index) -> occurrence(result), seriesId.value());
    }

    @Override
    @Transactional
    public void insertOccurrence(PendingOccurrence occurrence) {
        jdbc.update("""
                INSERT INTO series_occurrence (occurrence_id, series_id, template_version,
                    period_start, period_end, due_at, status, expense_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, occurrence.id().value(), occurrence.seriesId().value(),
                occurrence.templateVersion(), Date.valueOf(occurrence.periodStart()),
                Date.valueOf(occurrence.periodEnd()), Timestamp.from(occurrence.dueAt()),
                occurrence.status().name(),
                occurrence.expenseId() == null ? null : occurrence.expenseId().value());
    }

    @Override
    @Transactional
    public void updateOccurrence(PendingOccurrence occurrence) {
        int updated = jdbc.update("""
                UPDATE series_occurrence SET status = ?, expense_id = ?
                WHERE occurrence_id = ? AND status = 'PENDING'
                """, occurrence.status().name(),
                occurrence.expenseId() == null ? null : occurrence.expenseId().value(),
                occurrence.id().value());
        if (updated == 0) {
            throw new ConcurrentGroupUpdateException();
        }
    }

    @Override
    public Optional<PendingOccurrence> findOccurrence(OccurrenceId id) {
        List<PendingOccurrence> found = jdbc.query("""
                SELECT occurrence_id, series_id, template_version, period_start, period_end,
                    due_at, status, expense_id
                FROM series_occurrence WHERE occurrence_id = ?
                """, (result, index) -> occurrence(result), id.value());
        return found.stream().findFirst();
    }

    @Override
    public Optional<PendingOccurrence> findOccurrence(SeriesId seriesId, LocalDate periodStart) {
        List<PendingOccurrence> found = jdbc.query("""
                SELECT occurrence_id, series_id, template_version, period_start, period_end,
                    due_at, status, expense_id
                FROM series_occurrence WHERE series_id = ? AND period_start = ?
                """, (result, index) -> occurrence(result), seriesId.value(),
                Date.valueOf(periodStart));
        return found.stream().findFirst();
    }

    public List<PendingOccurrence> pendingFor(SeriesId seriesId) {
        return jdbc.query("""
                SELECT occurrence_id, series_id, template_version, period_start, period_end,
                    due_at, status, expense_id
                FROM series_occurrence
                WHERE series_id = ? AND status = 'PENDING'
                ORDER BY period_start
                """, (result, index) -> occurrence(result), seriesId.value());
    }

    private static PendingOccurrence occurrence(java.sql.ResultSet result) throws java.sql.SQLException {
        String expenseId = result.getString("expense_id");
        return new PendingOccurrence(
                new OccurrenceId(result.getString("occurrence_id")),
                new SeriesId(result.getString("series_id")),
                result.getLong("template_version"),
                result.getDate("period_start").toLocalDate(),
                result.getDate("period_end").toLocalDate(),
                result.getTimestamp("due_at").toInstant(),
                OccurrenceStatus.valueOf(result.getString("status")),
                expenseId == null ? null : new ExpenseId(expenseId));
    }

    private List<RecurringSeries> findIds(String query, Object... arguments) {
        return jdbc.queryForList(query, String.class, arguments).stream()
                .map(value -> find(new SeriesId(value)))
                .flatMap(Optional::stream)
                .toList();
    }

    private record SeriesRow(String scopeType, String groupId, String currency,
                             String description, long totalMinorUnits, String splitMethod,
                             String cadence, int dueDay, String timezone, String status,
                             LocalDate nextPeriodStart, long templateVersion,
                             java.time.Instant createdAt) {
    }
}
