package com.dutch.ledger.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpenseCorrectionTest {

    private static final ExpenseId EXPENSE = new ExpenseId("exp_012345678901234567");
    private static final GroupId GROUP = new GroupId("grp_012345678901234567");
    private static final MemberId A = member(1);
    private static final MemberId B = member(2);
    private static final MemberId C = member(3);
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void reversal_entries_cancel_the_original_exactly() {
        Expense original = original();
        Expense replacement = replacement(EXPENSE, 2);
        ExpenseCorrection correction = ExpenseCorrection.of(original, replacement, A, NOW);

        assertThat(correction.reversalEntries()).hasSameSizeAs(original.obligationEntries());
        assertThat(correction.reversalEntries()).allSatisfy(reversal -> {
            assertThat(reversal.revision()).isEqualTo(2);
            assertThat(reversal.minorUnits()).isEqualTo(30);
            assertThat(reversal.currency()).isEqualTo("INR");
        });
        assertThat(correction.reversalEntries()).extracting(ObligationEntry::debtor)
                .containsExactlyInAnyOrder(A, A);
        assertThat(correction.reversalEntries()).extracting(ObligationEntry::creditor)
                .containsExactlyInAnyOrder(B, C);

        List<ObligationEntry> fold = new ArrayList<>(original.obligationEntries());
        fold.addAll(correction.reversalEntries());
        assertThat(BalanceProjection.from(fold, "INR").balances()).isEmpty();
    }

    @Test
    void the_replacement_is_the_next_revision_and_keeps_the_scope() {
        ExpenseImagePair pair = pair();

        assertThat(pair.correction().toRevision()).isEqualTo(2);
        assertThat(pair.correction().replacementEntries())
                .isEqualTo(pair.replacement().obligationEntries());
        assertThat(pair.history().current().revision()).isEqualTo(2);
        assertThat(pair.history().corrections()).hasSize(1);
    }

    @Test
    void a_correction_cannot_change_identity_group_or_currency() {
        Expense original = original();
        assertThatThrownBy(() -> ExpenseCorrection.of(original,
                replacement(new ExpenseId("exp_112345678901234567"), 2), A, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expense id");

        Expense wrongGroup = Expense.createAtRevision(EXPENSE, new GroupId("grp_112345678901234567"),
                "Dinner", 100, "INR", SplitMethod.EQUAL,
                List.of(new PayerContribution(A, 100)),
                List.of(new ParticipantShare(A, 50), new ParticipantShare(B, 50)), NOW, 2);
        assertThatThrownBy(() -> ExpenseCorrection.of(original, wrongGroup, A, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("group");

        Expense wrongCurrency = Expense.createAtRevision(EXPENSE, GROUP, "Dinner", 100, "USD",
                SplitMethod.EQUAL, List.of(new PayerContribution(A, 100)),
                List.of(new ParticipantShare(A, 50), new ParticipantShare(B, 50)), NOW, 2);
        assertThatThrownBy(() -> ExpenseCorrection.of(original, wrongCurrency, A, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void a_revision_must_advance_by_exactly_one() {
        assertThatThrownBy(() -> ExpenseCorrection.of(original(), replacement(EXPENSE, 3), A, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("next");
    }

    @Test
    void a_history_advances_and_keeps_the_correction_evidence() {
        ExpenseHistory history = ExpenseHistory.of(original());
        ExpenseCorrection first = history.correct(replacement(EXPENSE, 2), A, NOW);
        ExpenseCorrection second = history.correct(replacement(EXPENSE, 3), B, NOW);

        assertThat(first.fromRevision()).isEqualTo(1);
        assertThat(second.fromRevision()).isEqualTo(2);
        assertThat(history.current().revision()).isEqualTo(3);
        assertThat(history.corrections()).containsExactly(first, second);
    }

    private record ExpenseImagePair(ExpenseCorrection correction, Expense replacement,
                                    ExpenseHistory history) {
    }

    private ExpenseImagePair pair() {
        ExpenseHistory history = ExpenseHistory.of(original());
        Expense replacement = replacement(EXPENSE, 2);
        ExpenseCorrection correction = history.correct(replacement, A, NOW);
        return new ExpenseImagePair(correction, replacement, history);
    }

    private static Expense original() {
        return Expense.create(EXPENSE, GROUP, "Dinner", 100, "INR", SplitMethod.EQUAL,
                List.of(new PayerContribution(A, 100)),
                List.of(new ParticipantShare(A, 40), new ParticipantShare(B, 30),
                        new ParticipantShare(C, 30)), NOW);
    }

    private static Expense replacement(ExpenseId id, long revision) {
        return Expense.createAtRevision(id, GROUP, "Dinner corrected", 100, "INR", SplitMethod.EQUAL,
                List.of(new PayerContribution(A, 100)),
                List.of(new ParticipantShare(A, 50), new ParticipantShare(B, 50)), NOW, revision);
    }

    private static MemberId member(int value) {
        return new MemberId("mem_" + String.format("%018d", value));
    }
}
