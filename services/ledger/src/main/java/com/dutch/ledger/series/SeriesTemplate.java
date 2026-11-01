package com.dutch.ledger.series;

import com.dutch.ledger.expense.Allocation;
import com.dutch.ledger.expense.Money;
import com.dutch.ledger.expense.ParticipantShare;
import com.dutch.ledger.expense.PayerContribution;
import com.dutch.ledger.expense.SplitMethod;
import com.dutch.ledger.group.MemberId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The immutable occurrence template: description, total, payers, and split inputs. */
public record SeriesTemplate(
        String description,
        long totalMinorUnits,
        String currency,
        SplitMethod splitMethod,
        List<PayerContribution> payers,
        List<SeriesParticipant> participants) {

    private static final int MAX_DESCRIPTION = 200;

    public SeriesTemplate {
        if (description == null) {
            throw new IllegalArgumentException("description is required");
        }
        String trimmed = description.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("description must be between 1 and 200 characters");
        }
        new Money(totalMinorUnits, currency);
        Objects.requireNonNull(splitMethod, "splitMethod");
        Objects.requireNonNull(payers, "payers");
        Objects.requireNonNull(participants, "participants");

        if (payers.isEmpty()) {
            throw new IllegalArgumentException("a series needs at least one payer");
        }
        Set<MemberId> payerIds = new HashSet<>();
        long paid = 0;
        for (PayerContribution payer : payers) {
            Objects.requireNonNull(payer, "payer");
            if (!payerIds.add(payer.memberId())) {
                throw new IllegalArgumentException("a payer may appear only once");
            }
            if (payer.minorUnits() <= 0) {
                throw new IllegalArgumentException("a payer contribution must be positive");
            }
            paid = Math.addExact(paid, payer.minorUnits());
        }
        if (paid != totalMinorUnits) {
            throw new IllegalArgumentException("payer contributions must equal the total");
        }

        if (participants.isEmpty()) {
            throw new IllegalArgumentException("a series needs at least one participant");
        }
        Set<MemberId> participantIds = new HashSet<>();
        long basisTotal = 0;
        long exactTotal = 0;
        for (SeriesParticipant participant : participants) {
            Objects.requireNonNull(participant, "participant");
            if (!participantIds.add(participant.memberId())) {
                throw new IllegalArgumentException("a participant may appear only once");
            }
            switch (splitMethod) {
                case EQUAL -> {
                    if (participant.basisPoints() != null || participant.minorUnits() != null) {
                        throw new IllegalArgumentException("an EQUAL participant carries no amounts");
                    }
                }
                case PERCENTAGE -> {
                    if (participant.basisPoints() == null) {
                        throw new IllegalArgumentException("a PERCENTAGE participant needs basis points");
                    }
                    basisTotal = Math.addExact(basisTotal, participant.basisPoints());
                }
                case EXACT -> {
                    if (participant.minorUnits() == null) {
                        throw new IllegalArgumentException("an EXACT participant needs an amount");
                    }
                    exactTotal = Math.addExact(exactTotal, participant.minorUnits());
                }
            }
        }
        if (splitMethod == SplitMethod.PERCENTAGE && basisTotal != 10_000) {
            throw new IllegalArgumentException("percentage basis points must sum to 10000");
        }
        if (splitMethod == SplitMethod.EXACT && exactTotal != totalMinorUnits) {
            throw new IllegalArgumentException("exact participant amounts must equal the total");
        }

        description = trimmed;
        payers = List.copyOf(payers);
        participants = List.copyOf(participants);
    }

    public List<MemberId> participantIds() {
        return participants.stream().map(SeriesParticipant::memberId).toList();
    }

    /** Derives this template's exact shares for the fixed total. */
    public List<ParticipantShare> shares() {
        return switch (splitMethod) {
            case EQUAL -> Allocation.equal(totalMinorUnits, currency, participantIds()).shares();
            case PERCENTAGE -> Allocation.percentage(totalMinorUnits, currency, participantIds(),
                    participants.stream().map(SeriesParticipant::basisPoints).toList()).shares();
            case EXACT -> participants.stream()
                    .map(participant -> new ParticipantShare(
                            participant.memberId(), participant.minorUnits()))
                    .toList();
        };
    }
}
