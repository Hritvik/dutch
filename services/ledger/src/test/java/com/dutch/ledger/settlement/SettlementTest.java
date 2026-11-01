package com.dutch.ledger.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.ledger.group.GroupRuleException;
import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettlementTest {

    private static final SettlementId SETTLEMENT = new SettlementId("stl_012345678901234567");
    private static final MemberId A = member(1);
    private static final MemberId B = member(2);
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void a_multi_scope_settlement_is_parent_owned_and_immutable() {
        Settlement settlement = settlement(100, List.of(
                allocation("group:grp_012345678901234567:INR", 60),
                allocation("direct:mem_000000000000000001:mem_000000000000000002:INR", 40)));

        assertThat(settlement.allocations()).hasSize(2);
        assertThat(settlement.totalMinorUnits()).isEqualTo(100);
        assertThatThrownBy(() -> settlement.allocations().add(allocation("group:other:INR", 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void allocations_must_sum_to_the_total_and_match_the_parent() {
        assertThatThrownBy(() -> settlement(100, List.of(allocation("group:one:INR", 60))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum exactly");

        assertThatThrownBy(() -> settlement(100, List.of(
                new SettlementAllocation("group:one:INR", "INR", 100, B, A))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent payer");

        assertThatThrownBy(() -> settlement(100, List.of(
                allocation("group:one:INR", 50), allocation("group:one:INR", 50))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");

        assertThatThrownBy(() -> settlement(100, List.of(
                new SettlementAllocation("group:one:USD", "USD", 100, A, B))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void a_void_reverses_every_allocation_exactly_once() {
        Settlement settlement = settlement(100, List.of(
                allocation("group:grp_012345678901234567:INR", 60),
                allocation("direct:mem_000000000000000001:mem_000000000000000002:INR", 40)));
        SettlementHistory history = SettlementHistory.of(settlement);

        SettlementVoid voided = history.voidIt(A, NOW);

        assertThat(voided.reversedAllocations()).hasSameSizeAs(settlement.allocations());
        for (SettlementAllocation original : settlement.allocations()) {
            SettlementAllocation reversal = voided.reversedAllocations().stream()
                    .filter(candidate -> candidate.scopeId().equals(original.scopeId()))
                    .findFirst().orElseThrow();
            assertThat(reversal.payer()).isEqualTo(original.payee());
            assertThat(reversal.payee()).isEqualTo(original.payer());
            assertThat(reversal.minorUnits()).isEqualTo(original.minorUnits());
            assertThat(reversal.minorUnits() - original.minorUnits()).isZero();
        }

        assertThatThrownBy(() -> history.voidIt(A, NOW))
                .isInstanceOf(GroupRuleException.class)
                .hasMessageContaining("already been voided");
    }

    @Test
    void a_settle_all_parent_allocates_each_selected_scope() {
        List<SettlementAllocation> allocations = new ArrayList<>();
        allocations.add(allocation("group:grp_012345678901234567:INR", 25));
        allocations.add(allocation("direct:mem_000000000000000001:mem_000000000000000002:INR", 15));

        Settlement settlement = settlement(40, allocations);

        assertThat(settlement.allocations()).extracting(SettlementAllocation::scopeId)
                .containsExactly(
                        "group:grp_012345678901234567:INR",
                        "direct:mem_000000000000000001:mem_000000000000000002:INR");
    }

    private static Settlement settlement(long total, List<SettlementAllocation> allocations) {
        return new Settlement(SETTLEMENT, A, B, total, "INR", allocations, NOW);
    }

    private static SettlementAllocation allocation(String scopeId, long amount) {
        return new SettlementAllocation(scopeId, "INR", amount, A, B);
    }

    private static MemberId member(int value) {
        return new MemberId("mem_" + String.format("%018d", value));
    }
}
