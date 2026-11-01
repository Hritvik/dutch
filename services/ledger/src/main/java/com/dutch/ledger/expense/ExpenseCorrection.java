package com.dutch.ledger.expense;

import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An immutable correction from one Expense revision to the next. It carries an
 * exact reversal of every original entry plus the replacement revision's entries.
 */
public record ExpenseCorrection(
        ExpenseId expenseId,
        GroupId groupId,
        long fromRevision,
        long toRevision,
        List<ObligationEntry> reversalEntries,
        Expense replacement,
        MemberId actor,
        Instant occurredAt) {

    public ExpenseCorrection {
        Objects.requireNonNull(expenseId, "expenseId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (toRevision != fromRevision + 1) {
            throw new IllegalArgumentException("a correction must advance exactly one revision");
        }
        Objects.requireNonNull(replacement, "replacement");
        if (replacement.revision() != toRevision) {
            throw new IllegalArgumentException("the replacement revision does not match toRevision");
        }
        reversalEntries = List.copyOf(reversalEntries);
    }

    public static ExpenseCorrection of(Expense original, Expense replacement, MemberId actor,
                                       Instant occurredAt) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(replacement, "replacement");
        if (!original.id().equals(replacement.id())) {
            throw new IllegalArgumentException("a correction must keep the same expense id");
        }
        if (!original.groupId().equals(replacement.groupId())) {
            throw new IllegalArgumentException("a correction must keep the same group");
        }
        if (!original.currency().equals(replacement.currency())) {
            throw new IllegalArgumentException("a correction must keep the same currency");
        }
        if (replacement.revision() != original.revision() + 1) {
            throw new IllegalArgumentException("the replacement revision is not the next one");
        }
        List<ObligationEntry> reversals = new ArrayList<>();
        for (ObligationEntry entry : original.obligationEntries()) {
            reversals.add(new ObligationEntry(
                    entry.expenseId(), replacement.revision(), entry.creditor(), entry.debtor(),
                    entry.minorUnits(), entry.currency(), entry.algorithmVersion()));
        }
        return new ExpenseCorrection(original.id(), original.groupId(), original.revision(),
                replacement.revision(), reversals, replacement, actor, occurredAt);
    }

    public List<ObligationEntry> replacementEntries() {
        return replacement.obligationEntries();
    }

    public List<ObligationEntry> allEntries() {
        List<ObligationEntry> entries = new ArrayList<>(reversalEntries);
        entries.addAll(replacementEntries());
        return List.copyOf(entries);
    }
}
