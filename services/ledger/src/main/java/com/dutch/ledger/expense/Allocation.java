package com.dutch.ledger.expense;

import com.dutch.ledger.group.MemberId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic equal, exact, and percentage allocation in minor units.
 * Percentage input is integer basis points summing to 10,000; equal and
 * percentage remainders use the largest-remainder rule with input ordering as
 * the stable tie-break.
 */
public record Allocation(
        SplitMethod method,
        long totalMinorUnits,
        String currency,
        List<ParticipantShare> shares,
        String algorithmVersion) {

    private static final int TOTAL_BASIS_POINTS = 10_000;

    public Allocation {
        Objects.requireNonNull(method, "method");
        new Money(totalMinorUnits, currency);
        if (totalMinorUnits <= 0) {
            throw new IllegalArgumentException("an expense total must be positive");
        }
        Objects.requireNonNull(shares, "shares");
        if (shares.isEmpty()) {
            throw new IllegalArgumentException("an expense needs at least one participant");
        }
        long sum = 0;
        Set<MemberId> seen = new HashSet<>();
        for (ParticipantShare share : shares) {
            if (!seen.add(share.memberId())) {
                throw new IllegalArgumentException("participants must be unique");
            }
            sum = Math.addExact(sum, share.minorUnits());
        }
        if (sum != totalMinorUnits) {
            throw new IllegalArgumentException("shares must sum exactly to the expense total");
        }
        shares = List.copyOf(shares);
    }

    public static Allocation equal(long totalMinorUnits, String currency, List<MemberId> participants) {
        validateParticipants(participants);
        int count = participants.size();
        if (totalMinorUnits < count) {
            throw new IllegalArgumentException("an equal split needs at least one minor unit per participant");
        }
        long base = totalMinorUnits / count;
        long remainder = totalMinorUnits % count;
        List<ParticipantShare> shares = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            shares.add(new ParticipantShare(
                    participants.get(index), base + (index < remainder ? 1 : 0)));
        }
        return new Allocation(SplitMethod.EQUAL, totalMinorUnits, currency, shares,
                AllocationAlgorithmVersion.EXPENSE_ALLOCATION_V1);
    }

    public static Allocation exact(long totalMinorUnits, String currency, List<ParticipantShare> shares) {
        Objects.requireNonNull(shares, "shares");
        validateParticipants(shares.stream().map(ParticipantShare::memberId).toList());
        return new Allocation(SplitMethod.EXACT, totalMinorUnits, currency, shares,
                AllocationAlgorithmVersion.EXPENSE_ALLOCATION_V1);
    }

    public static Allocation percentage(long totalMinorUnits, String currency,
                                        List<MemberId> participants, List<Integer> basisPoints) {
        validateParticipants(participants);
        Objects.requireNonNull(basisPoints, "basisPoints");
        if (basisPoints.size() != participants.size()) {
            throw new IllegalArgumentException("basis points must match the participant list");
        }
        int points = 0;
        for (Integer value : basisPoints) {
            if (value == null || value <= 0) {
                throw new IllegalArgumentException("basis points must be positive");
            }
            points += value;
        }
        if (points != TOTAL_BASIS_POINTS) {
            throw new IllegalArgumentException("basis points must sum to 10000");
        }
        if (totalMinorUnits < participants.size()) {
            throw new IllegalArgumentException("a percentage split needs at least one minor unit per participant");
        }

        long[] base = new long[participants.size()];
        long[] fractional = new long[participants.size()];
        long allocated = 0;
        for (int index = 0; index < participants.size(); index++) {
            long product = Math.multiplyExact(totalMinorUnits, (long) basisPoints.get(index));
            base[index] = product / TOTAL_BASIS_POINTS;
            fractional[index] = product % TOTAL_BASIS_POINTS;
            allocated = Math.addExact(allocated, base[index]);
        }
        long remainder = totalMinorUnits - allocated;

        List<Integer> order = new ArrayList<>(participants.size());
        for (int index = 0; index < participants.size(); index++) {
            order.add(index);
        }
        order.sort(Comparator
                .comparingLong((Integer index) -> fractional[index]).reversed()
                .thenComparingInt(Integer::intValue));
        for (int index = 0; index < remainder; index++) {
            base[order.get(index)] += 1;
        }

        List<ParticipantShare> shares = new ArrayList<>(participants.size());
        for (int index = 0; index < participants.size(); index++) {
            shares.add(new ParticipantShare(participants.get(index), base[index]));
        }
        return new Allocation(SplitMethod.PERCENTAGE, totalMinorUnits, currency, shares,
                AllocationAlgorithmVersion.EXPENSE_ALLOCATION_V1);
    }

    private static void validateParticipants(List<MemberId> participants) {
        Objects.requireNonNull(participants, "participants");
        if (participants.isEmpty()) {
            throw new IllegalArgumentException("an expense needs at least one participant");
        }
        Set<MemberId> seen = new HashSet<>();
        for (MemberId participant : participants) {
            Objects.requireNonNull(participant, "participant");
            if (!seen.add(participant)) {
                throw new IllegalArgumentException("participants must be unique");
            }
        }
    }
}
