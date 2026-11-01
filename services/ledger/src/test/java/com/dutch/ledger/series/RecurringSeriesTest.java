package com.dutch.ledger.series;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.ledger.expense.ExpenseId;
import com.dutch.ledger.expense.PayerContribution;
import com.dutch.ledger.expense.SplitMethod;
import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecurringSeriesTest {

    private static final SeriesId SERIES = new SeriesId("ser_012345678901234567");
    private static final OccurrenceId OCCURRENCE = new OccurrenceId("occ_012345678901234567");
    private static final ExpenseId EXPENSE = new ExpenseId("exp_012345678901234567");
    private static final MemberId A = new MemberId("mem_012345678901234567");
    private static final MemberId B = new MemberId("mem_112345678901234567");
    private static final MemberId C = new MemberId("mem_212345678901234567");
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    private static SeriesTemplate template(SplitMethod method,
                                           List<SeriesParticipant> participants) {
        return new SeriesTemplate("Rent", 100, "INR", method,
                List.of(new PayerContribution(A, 100)), participants);
    }

    private static RecurringSeries monthly(SeriesStatus status, LocalDate next) {
        return new RecurringSeries(SERIES, SeriesScope.group(
                new com.dutch.ledger.group.GroupId("grp_012345678901234567")),
                template(SplitMethod.EQUAL, List.of(SeriesParticipant.of(A), SeriesParticipant.of(B))),
                Cadence.MONTHLY, 31, KOLKATA, status, next, 1, NOW);
    }

    @Test
    void a_pending_occurrence_holds_no_debt_until_explicit_submission() {
        RecurringSeries series = monthly(SeriesStatus.ACTIVE, LocalDate.of(2026, 11, 1));
        PendingOccurrence occurrence = series.occurrenceFor(OCCURRENCE, LocalDate.of(2026, 11, 1));

        assertThat(occurrence.status()).isEqualTo(OccurrenceStatus.PENDING);
        assertThat(occurrence.expenseId()).isNull();
        assertThat(occurrence.periodStart()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(occurrence.periodEnd()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(occurrence.dueAt()).isEqualTo(
                ZonedDateTime.of(2026, 11, 30, 0, 0, 0, 0, KOLKATA).toInstant());
        assertThat(occurrence.templateVersion()).isEqualTo(1);

        PendingOccurrence submitted = occurrence.submitted(EXPENSE);
        assertThat(submitted.status()).isEqualTo(OccurrenceStatus.SUBMITTED);
        assertThat(submitted.expenseId()).isEqualTo(EXPENSE);
        assertThatThrownBy(() -> submitted.submitted(EXPENSE))
                .hasMessageContaining("no longer pending");
    }

    @Test
    void a_monthly_due_day_clamps_to_the_month_length() {
        RecurringSeries series = monthly(SeriesStatus.ACTIVE, LocalDate.of(2027, 2, 1));
        PendingOccurrence february = series.occurrenceFor(OCCURRENCE, LocalDate.of(2027, 2, 1));

        assertThat(february.dueAt()).isEqualTo(
                ZonedDateTime.of(2027, 2, 28, 0, 0, 0, 0, KOLKATA).toInstant());
    }

    @Test
    void a_weekly_series_uses_iso_week_boundaries_and_weekday_due_dates() {
        RecurringSeries series = new RecurringSeries(SERIES, SeriesScope.direct(List.of(A, B)),
                template(SplitMethod.EQUAL, List.of(SeriesParticipant.of(A), SeriesParticipant.of(B))),
                Cadence.WEEKLY, 5, KOLKATA, SeriesStatus.ACTIVE, LocalDate.of(2026, 11, 2),
                1, NOW);
        PendingOccurrence occurrence = series.occurrenceFor(OCCURRENCE, LocalDate.of(2026, 11, 2));

        assertThat(occurrence.periodEnd()).isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(occurrence.dueAt()).isEqualTo(
                ZonedDateTime.of(2026, 11, 6, 0, 0, 0, 0, KOLKATA).toInstant());
    }

    @Test
    void paused_and_archived_series_generate_nothing_and_periods_only_move_forward() {
        assertThatThrownBy(() -> monthly(SeriesStatus.PAUSED, LocalDate.of(2026, 11, 1))
                .occurrenceFor(OCCURRENCE, LocalDate.of(2026, 11, 1)))
                .hasMessageContaining("paused");
        assertThatThrownBy(() -> monthly(SeriesStatus.ARCHIVED, LocalDate.of(2026, 11, 1))
                .occurrenceFor(OCCURRENCE, LocalDate.of(2026, 11, 1)))
                .hasMessageContaining("archived");

        RecurringSeries series = monthly(SeriesStatus.ACTIVE, LocalDate.of(2026, 11, 1));
        assertThatThrownBy(() -> series.occurrenceFor(OCCURRENCE, LocalDate.of(2026, 10, 1)))
                .hasMessageContaining("already been generated");
        assertThat(series.withNextPeriodStart(LocalDate.of(2026, 12, 1)).nextPeriodStart())
                .isEqualTo(LocalDate.of(2026, 12, 1));
        assertThatThrownBy(() -> series.withNextPeriodStart(LocalDate.of(2026, 11, 2)))
                .hasMessageContaining("align");
    }

    @Test
    void skips_and_misses_never_carry_an_expense_and_only_apply_while_pending() {
        PendingOccurrence occurrence = monthly(SeriesStatus.ACTIVE, LocalDate.of(2026, 11, 1))
                .occurrenceFor(OCCURRENCE, LocalDate.of(2026, 11, 1));

        PendingOccurrence skipped = occurrence.skipped();
        assertThat(skipped.status()).isEqualTo(OccurrenceStatus.SKIPPED);
        assertThat(skipped.expenseId()).isNull();
        assertThatThrownBy(() -> skipped.missed()).hasMessageContaining("no longer pending");

        PendingOccurrence missed = occurrence.missed();
        assertThat(missed.status()).isEqualTo(OccurrenceStatus.MISSED);
        assertThat(missed.expenseId()).isNull();
    }

    @Test
    void percentage_and_exact_templates_derive_exact_shares() {
        SeriesTemplate percentage = template(SplitMethod.PERCENTAGE,
                List.of(SeriesParticipant.percentage(A, 6000),
                        SeriesParticipant.percentage(B, 4000)));
        assertThat(percentage.shares()).extracting("minorUnits").containsExactly(60L, 40L);

        SeriesTemplate exact = new SeriesTemplate("Rent", 100, "INR", SplitMethod.EXACT,
                List.of(new PayerContribution(A, 100)),
                List.of(SeriesParticipant.exact(B, 70), SeriesParticipant.exact(C, 30)));
        assertThat(exact.shares()).extracting("memberId").containsExactly(B, C);
        assertThat(exact.shares()).extracting("minorUnits").containsExactly(70L, 30L);

        assertThatThrownBy(() -> template(SplitMethod.PERCENTAGE,
                List.of(SeriesParticipant.percentage(A, 6000))))
                .hasMessageContaining("sum to 10000");
        assertThatThrownBy(() -> new SeriesTemplate("Rent", 100, "INR", SplitMethod.EXACT,
                List.of(new PayerContribution(A, 100)),
                List.of(SeriesParticipant.exact(B, 60))))
                .hasMessageContaining("equal the total");
    }

    @Test
    void a_direct_series_requires_two_members_and_has_no_group() {
        SeriesScope scope = SeriesScope.direct(List.of(B, A));
        assertThat(scope.memberIds()).containsExactly(A, B);
        assertThatThrownBy(() -> SeriesScope.direct(List.of(A)))
                .hasMessageContaining("at least two members");
        assertThatThrownBy(() -> SeriesScope.direct(List.of(A, A)))
                .hasMessageContaining("unique");
    }

    @Test
    void lifecycle_transitions_are_guarded() {
        RecurringSeries active = monthly(SeriesStatus.ACTIVE, LocalDate.of(2026, 11, 1));
        RecurringSeries paused = active.paused();
        assertThat(paused.status()).isEqualTo(SeriesStatus.PAUSED);
        assertThat(paused.version()).isEqualTo(2);
        assertThat(paused.resumed().status()).isEqualTo(SeriesStatus.ACTIVE);
        assertThat(active.archived().status()).isEqualTo(SeriesStatus.ARCHIVED);
        assertThatThrownBy(() -> active.resumed()).hasMessageContaining("paused");
        assertThatThrownBy(() -> active.archived().paused()).hasMessageContaining("archived");
    }
}
