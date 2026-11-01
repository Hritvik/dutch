package com.dutch.ledger.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.ledger.expense.Allocation;
import com.dutch.ledger.expense.ExpenseId;
import com.dutch.ledger.expense.ParticipantShare;
import com.dutch.ledger.expense.PayerContribution;
import com.dutch.ledger.expense.SplitMethod;
import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DirectExpenseTest {

    private static final ExpenseId ID = new ExpenseId("exp_012345678901234567");
    private static final MemberId A = new MemberId("mem_012345678901234567");
    private static final MemberId B = new MemberId("mem_112345678901234567");
    private static final MemberId C = new MemberId("mem_212345678901234567");
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

    @Test
    void an_equal_direct_expense_creates_one_canonical_obligation() {
        DirectExpense expense = DirectExpense.createAtRevision(ID, "Dinner", 100, "INR",
                SplitMethod.EQUAL,
                List.of(new PayerContribution(A, 100)),
                List.of(new ParticipantShare(A, 50), new ParticipantShare(B, 50)),
                NOW, 1);

        assertThat(expense.obligationEntries()).singleElement().satisfies(entry -> {
            assertThat(entry.debtor()).isEqualTo(B);
            assertThat(entry.creditor()).isEqualTo(A);
            assertThat(entry.minorUnits()).isEqualTo(50);
            assertThat(entry.revision()).isEqualTo(1);
        });
        assertThat(expense.netPositions()).containsEntry(A, 50L).containsEntry(B, -50L);
        assertThat(DirectScope.streamId(B, A, "INR")).isEqualTo(DirectScope.streamId(A, B, "INR"));
    }

    @Test
    void multiple_payers_net_against_the_shared_positions() {
        DirectExpense expense = DirectExpense.create(ID, "Taxi", 100, "INR", SplitMethod.EQUAL,
                List.of(new PayerContribution(A, 60), new PayerContribution(B, 40)),
                List.of(new ParticipantShare(A, 50), new ParticipantShare(B, 50)), NOW);

        assertThat(expense.netPositions()).containsEntry(A, 10L).containsEntry(B, -10L);
        assertThat(expense.obligationEntries()).singleElement()
                .satisfies(entry -> assertThat(entry.minorUnits()).isEqualTo(10));
    }

    @Test
    void builds_a_revision_from_an_exact_allocation() {
        Allocation allocation = Allocation.exact(100, "INR", List.of(
                new ParticipantShare(A, 70), new ParticipantShare(B, 30)));
        DirectExpense expense = DirectExpense.allocate(ID, "Dinner", 100, "INR",
                SplitMethod.EXACT, List.of(new PayerContribution(B, 100)), allocation, NOW);

        assertThat(expense.shares()).containsExactly(
                new ParticipantShare(A, 70), new ParticipantShare(B, 30));
        assertThat(expense.netPositions()).containsEntry(B, 70L).containsEntry(A, -70L);
    }

    @Test
    void rejects_imbalanced_payers_or_shares_and_a_single_member() {
        assertThatThrownBy(() -> DirectExpense.create(ID, "Dinner", 100, "INR", SplitMethod.EQUAL,
                List.of(new PayerContribution(A, 90)),
                List.of(new ParticipantShare(A, 50), new ParticipantShare(B, 50)), NOW))
                .hasMessageContaining("payer contributions");
        assertThatThrownBy(() -> DirectExpense.create(ID, "Dinner", 100, "INR", SplitMethod.EQUAL,
                List.of(new PayerContribution(A, 100)),
                List.of(new ParticipantShare(A, 50), new ParticipantShare(B, 40)), NOW))
                .hasMessageContaining("participant shares");
        assertThatThrownBy(() -> DirectExpense.create(ID, "Dinner", 100, "INR", SplitMethod.EQUAL,
                List.of(new PayerContribution(A, 100)),
                List.of(new ParticipantShare(A, 100)), NOW))
                .hasMessageContaining("two members");
        assertThatThrownBy(() -> DirectExpense.createAtRevision(ID, "Dinner", 100, "INR",
                SplitMethod.EQUAL, List.of(new PayerContribution(A, 100)),
                List.of(new ParticipantShare(A, 50), new ParticipantShare(B, 50)), NOW, 0))
                .hasMessageContaining("starts at one");
    }

    @Test
    void allows_a_null_share_for_a_member_who_pays_but_does_not_participate() {
        DirectExpense expense = DirectExpense.create(ID, "Gift", 100, "INR", SplitMethod.EXACT,
                List.of(new PayerContribution(A, 100)),
                List.of(new ParticipantShare(B, 60), new ParticipantShare(C, 40)), NOW);

        assertThat(expense.netPositions()).containsEntry(A, 100L)
                .containsEntry(B, -60L).containsEntry(C, -40L);
    }
}
