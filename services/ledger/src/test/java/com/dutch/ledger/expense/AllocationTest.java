package com.dutch.ledger.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.ledger.group.MemberId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AllocationTest {

    private static final MemberId A = member(1);
    private static final MemberId B = member(2);
    private static final MemberId C = member(3);

    @Test
    void equal_split_assigns_the_remainder_in_stored_order() {
        Allocation allocation = Allocation.equal(100, "INR", List.of(A, B, C));

        assertThat(allocation.shares()).containsExactly(
                new ParticipantShare(A, 34),
                new ParticipantShare(B, 33),
                new ParticipantShare(C, 33));
        assertThat(allocation.method()).isEqualTo(SplitMethod.EQUAL);
        assertThat(allocation.algorithmVersion()).isEqualTo("expense-allocation-v1");
    }

    @Test
    void equal_split_ties_follow_the_input_order() {
        assertThat(Allocation.equal(100, "INR", List.of(C, A, B)).shares())
                .containsExactly(new ParticipantShare(C, 34),
                        new ParticipantShare(A, 33),
                        new ParticipantShare(B, 33));
    }

    @Test
    void exact_split_requires_an_exact_sum() {
        Allocation allocation = Allocation.exact(100, "INR", List.of(
                new ParticipantShare(A, 60), new ParticipantShare(B, 40)));
        assertThat(allocation.shares()).hasSize(2);

        assertThatThrownBy(() -> Allocation.exact(100, "INR", List.of(
                new ParticipantShare(A, 60), new ParticipantShare(B, 30))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum exactly");
    }

    @Test
    void percentage_uses_largest_remainder() {
        Allocation allocation = Allocation.percentage(100, "INR", List.of(A, B, C),
                List.of(3333, 3333, 3334));

        assertThat(allocation.shares()).containsExactly(
                new ParticipantShare(A, 33),
                new ParticipantShare(B, 33),
                new ParticipantShare(C, 34));
    }

    @Test
    void percentage_ties_follow_the_input_order() {
        Allocation allocation = Allocation.percentage(101, "INR", List.of(A, B),
                List.of(5000, 5000));

        assertThat(allocation.shares()).containsExactly(
                new ParticipantShare(A, 51),
                new ParticipantShare(B, 50));
    }

    @Test
    void percentage_basis_points_must_sum_to_ten_thousand() {
        assertThatThrownBy(() -> Allocation.percentage(100, "INR", List.of(A, B),
                List.of(5000, 4999)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10000");
    }

    @Test
    void equal_property_holds_for_generated_totals() {
        Random random = new Random(42);
        for (int run = 0; run < 500; run++) {
            int count = 1 + random.nextInt(10);
            long total = count + random.nextInt(1_000_000);
            List<MemberId> participants = participants(count);
            Allocation allocation = Allocation.equal(total, "INR", participants);

            assertThat(sum(allocation)).isEqualTo(total);
            assertThat(allocation.shares()).allMatch(share -> share.minorUnits() > 0);
            assertThat(allocation.shares()).extracting(ParticipantShare::memberId)
                    .containsExactlyElementsOf(participants);
        }
    }

    @Test
    void percentage_property_holds_for_generated_partitions() {
        Random random = new Random(43);
        for (int run = 0; run < 500; run++) {
            int count = 1 + random.nextInt(8);
            long total = 10_000_000L + random.nextInt(1_000_000);
            List<Integer> points = partition(random, count);
            Allocation allocation = Allocation.percentage(total, "INR", participants(count), points);

            assertThat(sum(allocation)).isEqualTo(total);
            assertThat(allocation.shares()).allMatch(share -> share.minorUnits() > 0);
        }
    }

    @Test
    void equal_and_percentage_are_deterministic() {
        List<MemberId> participants = participants(5);
        assertThat(Allocation.equal(1001, "INR", participants).shares())
                .isEqualTo(Allocation.equal(1001, "INR", participants).shares());
        assertThat(Allocation.percentage(1001, "INR", participants, List.of(2000, 2000, 2000, 2000, 2000)).shares())
                .isEqualTo(Allocation.percentage(1001, "INR", participants, List.of(2000, 2000, 2000, 2000, 2000)).shares());
    }

    private static List<MemberId> participants(int count) {
        List<MemberId> members = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            members.add(member(index));
        }
        return members;
    }

    private static List<Integer> partition(Random random, int count) {
        List<Integer> points = new ArrayList<>();
        int remaining = 10_000;
        for (int index = 0; index < count - 1; index++) {
            int max = remaining - (count - index - 1);
            int value = 1 + random.nextInt(max);
            points.add(value);
            remaining -= value;
        }
        points.add(remaining);
        return points;
    }

    private static long sum(Allocation allocation) {
        return allocation.shares().stream().mapToLong(ParticipantShare::minorUnits).sum();
    }

    private static MemberId member(int value) {
        return new MemberId("mem_" + String.format("%018d", value));
    }
}
