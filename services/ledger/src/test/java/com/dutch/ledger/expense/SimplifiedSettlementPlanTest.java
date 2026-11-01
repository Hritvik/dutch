package com.dutch.ledger.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.dutch.ledger.group.MemberId;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class SimplifiedSettlementPlanTest {

    private static final MemberId A = member(1);
    private static final MemberId B = member(2);
    private static final MemberId C = member(3);
    private static final MemberId D = member(4);

    @Test
    void settles_largest_debtors_and_creditors_without_rewriting_positions() {
        SimplifiedSettlementPlan plan = SimplifiedSettlementPlan.from("INR",
                Map.of(A, 20L, B, -5L, C, -15L));

        assertThat(plan.transfers()).containsExactly(
                new SimplifiedSettlementPlan.Transfer(C, A, 15),
                new SimplifiedSettlementPlan.Transfer(B, A, 5));
        assertThat(plan.algorithmVersion()).isEqualTo("simplified-plan-v1");
    }

    @Test
    void an_already_settled_scope_has_no_transfers() {
        assertThat(SimplifiedSettlementPlan.from("INR", Map.of()).transfers()).isEmpty();
        assertThat(SimplifiedSettlementPlan.from("INR", Map.of(A, 0L, B, 0L)).transfers()).isEmpty();
    }

    @Test
    void a_plan_has_at_most_member_count_minus_one_transfers() {
        SimplifiedSettlementPlan plan = SimplifiedSettlementPlan.from("INR", Map.of(
                A, 30L, B, -10L, C, -10L, D, -10L));

        assertThat(plan.transfers()).hasSizeLessThanOrEqualTo(3);
        assertThat(plan.transfers()).hasSize(3);
    }

    @Test
    void ties_break_by_member_id() {
        SimplifiedSettlementPlan plan = SimplifiedSettlementPlan.from("INR",
                Map.of(A, 10L, B, 10L, C, -10L, D, -10L));

        assertThat(plan.transfers()).containsExactly(
                new SimplifiedSettlementPlan.Transfer(C, A, 10),
                new SimplifiedSettlementPlan.Transfer(D, B, 10));
    }

    @Test
    void transfers_fold_back_to_the_original_balances() {
        Map<MemberId, Long> balances = new TreeMap<>(Map.of(A, 25L, B, -5L, C, -20L));
        SimplifiedSettlementPlan plan = SimplifiedSettlementPlan.from("INR", balances);

        Map<MemberId, Long> folded = new TreeMap<>();
        for (SimplifiedSettlementPlan.Transfer transfer : plan.transfers()) {
            folded.merge(transfer.from(), -transfer.minorUnits(), Long::sum);
            folded.merge(transfer.to(), transfer.minorUnits(), Long::sum);
        }
        assertThat(folded).isEqualTo(balances);
    }

    private static MemberId member(int value) {
        return new MemberId("mem_" + String.format("%018d", value));
    }
}
