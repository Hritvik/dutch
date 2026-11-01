package com.dutch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.ledger.expense.ExpenseId;
import com.dutch.ledger.expense.PayerContribution;
import com.dutch.ledger.expense.SplitMethod;
import com.dutch.ledger.group.Group;
import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.GroupRole;
import com.dutch.ledger.group.JdbcGroupRepository;
import com.dutch.ledger.group.MemberId;
import com.dutch.ledger.series.Cadence;
import com.dutch.ledger.series.JdbcSeriesRepository;
import com.dutch.ledger.series.OccurrenceId;
import com.dutch.ledger.series.OccurrenceStatus;
import com.dutch.ledger.series.RecurringSeries;
import com.dutch.ledger.series.SeriesId;
import com.dutch.ledger.series.SeriesParticipant;
import com.dutch.ledger.series.SeriesScope;
import com.dutch.ledger.series.SeriesTemplate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcSeriesRepositoryIntegrationTest {

    private static final GroupId GROUP = new GroupId("grp_012345678901234567");
    private static final SeriesId SERIES = new SeriesId("ser_012345678901234567");
    private static final SeriesId ADVANCE = new SeriesId("ser_112345678901234567");
    private static final SeriesId OCCURRENCE_SERIES = new SeriesId("ser_212345678901234567");
    private static final OccurrenceId OCCURRENCE = new OccurrenceId("occ_012345678901234567");
    private static final MemberId A = new MemberId("mem_012345678901234567");
    private static final MemberId B = new MemberId("mem_112345678901234567");
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_series_test");

    private static JdbcTemplate jdbc;
    private static JdbcSeriesRepository series;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void prepare() {
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        series = new JdbcSeriesRepository(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        JdbcGroupRepository groups = new JdbcGroupRepository(jdbc);
        transactions.executeWithoutResult(status -> {
            Group group = Group.create(GROUP, "Flat", "INR", A, NOW);
            group.invite(A, B, GroupRole.MEMBER, NOW);
            group.activate(B, B, NOW);
            groups.saveNew(group);
        });
    }

    @Test
    void saves_and_reads_a_series_with_its_scope_and_template() {
        RecurringSeries saved = monthly();
        transactions.executeWithoutResult(status -> series.saveNew(saved));

        assertThat(series.find(SERIES)).contains(saved);
        assertThat(series.findForGroup(GROUP)).extracting(RecurringSeries::id)
                .contains(SERIES);
    }

    @Test
    void allows_one_occurrence_per_due_period_and_updates_its_status_once() {
        RecurringSeries saved = RecurringSeries.create(OCCURRENCE_SERIES, SeriesScope.group(GROUP),
                new SeriesTemplate("Rent", 100, "INR", SplitMethod.EQUAL,
                        List.of(new PayerContribution(A, 100)),
                        List.of(SeriesParticipant.of(A), SeriesParticipant.of(B))),
                Cadence.MONTHLY, 5, KOLKATA, LocalDate.of(2026, 11, 1), NOW);
        transactions.executeWithoutResult(status -> series.saveNew(saved));
        var occurrence = saved.occurrenceFor(OCCURRENCE, LocalDate.of(2026, 11, 1));
        transactions.executeWithoutResult(status -> series.insertOccurrence(occurrence));

        assertThat(series.findOccurrence(OCCURRENCE)).contains(occurrence);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                series.insertOccurrence(new com.dutch.ledger.series.PendingOccurrence(
                        new OccurrenceId("occ_112345678901234567"), OCCURRENCE_SERIES, 1,
                        LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
                        occurrence.dueAt(), OccurrenceStatus.PENDING, null))))
                .isInstanceOf(DuplicateKeyException.class);

        var submitted = occurrence.submitted(new ExpenseId("exp_012345678901234567"));
        transactions.executeWithoutResult(status -> series.updateOccurrence(submitted));
        assertThat(series.findOccurrence(OCCURRENCE).orElseThrow().status())
                .isEqualTo(OccurrenceStatus.SUBMITTED);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                series.updateOccurrence(occurrence.skipped())))
                .isInstanceOf(com.dutch.ledger.group.ConcurrentGroupUpdateException.class);
    }

    @Test
    void advances_the_series_only_at_the_expected_version() {
        RecurringSeries saved = RecurringSeries.create(ADVANCE, SeriesScope.group(GROUP),
                new SeriesTemplate("Rent", 100, "INR", SplitMethod.EQUAL,
                        List.of(new PayerContribution(A, 100)),
                        List.of(SeriesParticipant.of(A), SeriesParticipant.of(B))),
                Cadence.MONTHLY, 5, KOLKATA, LocalDate.of(2026, 11, 1), NOW);
        transactions.executeWithoutResult(status -> series.saveNew(saved));

        RecurringSeries paused = saved.paused();
        transactions.executeWithoutResult(status -> series.update(paused, 1));
        assertThat(series.find(ADVANCE).orElseThrow().status())
                .isEqualTo(com.dutch.ledger.series.SeriesStatus.PAUSED);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                series.update(paused.archived(), 1)))
                .isInstanceOf(com.dutch.ledger.group.ConcurrentGroupUpdateException.class);
    }

    private static RecurringSeries monthly() {
        return RecurringSeries.create(
                new SeriesId("ser_012345678901234567"), SeriesScope.group(GROUP),
                new SeriesTemplate("Rent", 100, "INR", SplitMethod.EQUAL,
                        List.of(new PayerContribution(A, 100)),
                        List.of(SeriesParticipant.of(A), SeriesParticipant.of(B))),
                Cadence.MONTHLY, 5, KOLKATA, LocalDate.of(2026, 11, 1), NOW);
    }
}
