package com.dutch.ledger.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.SortedMap;
import org.junit.jupiter.api.Test;

class ExpenseTest {

    private static final ExpenseId EXPENSE = new ExpenseId("exp_000000000000000001");
    private static final GroupId GROUP = new GroupId("grp_000000000000000001");
    private static final MemberId A = member(1);
    private static final MemberId B = member(2);
    private static final MemberId C = member(3);
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void a_multi_payer_expense_nets_to_obligation_entries() {
        Expense expense = Expense.create(EXPENSE, GROUP, "Dinner", 100, "INR", SplitMethod.EQUAL,
                List.of(new PayerContribution(A, 70), new PayerContribution(B, 30)),
                List.of(new ParticipantShare(A, 50), new ParticipantShare(B, 50)), NOW);

        assertThat(expense.netPositions()).containsEntry(A, 20L).containsEntry(B, -20L);
        assertThat(expense.obligationEntries()).containsExactly(
                new ObligationEntry(EXPENSE, 1, B, A, 20, "INR", "expense-allocation-v1"));
    }

    @Test
    void one_payer_with_three_participants_creates_two_entries() {
        Expense expense = Expense.create(EXPENSE, GROUP, "Dinner", 100, "INR", SplitMethod.EQUAL,
                List.of(new PayerContribution(A, 100)),
                List.of(new ParticipantShare(A, 40), new ParticipantShare(B, 30),
                        new ParticipantShare(C, 30)), NOW);

        assertThat(expense.obligationEntries()).containsExactly(
                new ObligationEntry(EXPENSE, 1, B, A, 30, "INR", "expense-allocation-v1"),
                new ObligationEntry(EXPENSE, 1, C, A, 30, "INR", "expense-allocation-v1"));
    }

    @Test
    void an_evenly_covered_expense_has_no_entries() {
        Expense expense = Expense.create(EXPENSE, GROUP, "Dinner", 100, "INR", SplitMethod.EQUAL,
                List.of(new PayerContribution(A, 50), new PayerContribution(B, 50)),
                List.of(new ParticipantShare(A, 50), new ParticipantShare(B, 50)), NOW);

        assertThat(expense.obligationEntries()).isEmpty();
        assertThat(expense.netPositions().values()).allMatch(value -> value == 0);
    }

    @Test
    void payer_and_share_totals_must_match_the_expense_total() {
        assertThatThrownBy(() -> Expense.create(EXPENSE, GROUP, "Dinner", 100, "INR",
                SplitMethod.EQUAL,
                List.of(new PayerContribution(A, 90)),
                List.of(new ParticipantShare(A, 100)), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payer contributions");

        assertThatThrownBy(() -> Expense.create(EXPENSE, GROUP, "Dinner", 100, "INR",
                SplitMethod.EQUAL,
                List.of(new PayerContribution(A, 100)),
                List.of(new ParticipantShare(A, 90)), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("participant shares");
    }

    @Test
    void duplicate_payers_or_participants_are_rejected() {
        assertThatThrownBy(() -> Expense.create(EXPENSE, GROUP, "Dinner", 100, "INR",
                SplitMethod.EQUAL,
                List.of(new PayerContribution(A, 50), new PayerContribution(A, 50)),
                List.of(new ParticipantShare(A, 100)), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payers must be unique");
    }

    @Test
    void entries_are_deterministic_and_net_to_zero() {
        Expense expense = Expense.create(EXPENSE, GROUP, "Dinner", 100, "INR", SplitMethod.EQUAL,
                List.of(new PayerContribution(A, 100)),
                List.of(new ParticipantShare(A, 40), new ParticipantShare(B, 30),
                        new ParticipantShare(C, 30)), NOW);

        assertThat(expense.obligationEntries()).isEqualTo(expense.obligationEntries());
        BalanceProjection projection = BalanceProjection.from(expense.obligationEntries(), "INR");
        assertThat(projection.balances()).isEqualTo(expense.netPositions());
        assertThat(projection.balances().values().stream().mapToLong(Long::longValue).sum()).isZero();
    }

    @Test
    void generated_expenses_always_fold_to_zero() {
        Random random = new Random(44);
        for (int run = 0; run < 300; run++) {
            int payerCount = 1 + random.nextInt(4);
            int participantCount = 1 + random.nextInt(5);
            long total = Math.max(payerCount, participantCount) + random.nextInt(100_000);
            List<PayerContribution> payers = new ArrayList<>();
            long[] payerParts = partition(random, total, payerCount);
            for (int index = 0; index < payerCount; index++) {
                payers.add(new PayerContribution(member(index + 1), payerParts[index]));
            }
            List<ParticipantShare> shares = new ArrayList<>();
            long[] shareParts = partition(random, total, participantCount);
            for (int index = 0; index < participantCount; index++) {
                shares.add(new ParticipantShare(member(index + 11), shareParts[index]));
            }
            Expense expense = Expense.create(new ExpenseId("exp_" + String.format("%018d", run + 1)),
                    GROUP, "Generated", total, "INR", SplitMethod.EXACT, payers, shares, NOW);

            SortedMap<MemberId, Long> positions = expense.netPositions();
            assertThat(positions.values().stream().mapToLong(Long::longValue).sum()).isZero();
            BalanceProjection projection = BalanceProjection.from(expense.obligationEntries(), "INR");
            assertThat(projection.balances()).isEqualTo(positions);
        }
    }

    @Test
    void invalid_totals_and_descriptions_are_rejected() {
        assertThatThrownBy(() -> Expense.create(EXPENSE, GROUP, " ", 100, "INR",
                SplitMethod.EQUAL, List.of(new PayerContribution(A, 100)),
                List.of(new ParticipantShare(A, 100)), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Expense.create(EXPENSE, GROUP, "Dinner", 0, "INR",
                SplitMethod.EQUAL, List.of(new PayerContribution(A, 1)),
                List.of(new ParticipantShare(A, 1)), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayerContribution(A, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParticipantShare(A, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static long[] partition(Random random, long total, int count) {
        long[] parts = new long[count];
        for (int index = 0; index < count; index++) {
            parts[index] = 1;
        }
        long remaining = total - count;
        for (int index = 0; index < count - 1; index++) {
            long value = remaining == 0 ? 0 : random.nextLong(remaining + 1);
            parts[index] += value;
            remaining -= value;
        }
        parts[count - 1] += remaining;
        return parts;
    }

    private static MemberId member(int value) {
        return new MemberId("mem_" + String.format("%018d", value));
    }
}
