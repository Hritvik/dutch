package com.dutch.ledger.expense;

import com.dutch.ledger.group.GroupRuleException;
import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Domain guard that applies at most one correction per Expense revision. */
public final class ExpenseHistory {

    private Expense current;
    private final List<ExpenseCorrection> corrections = new ArrayList<>();
    private final Set<Long> correctedRevisions = new HashSet<>();

    private ExpenseHistory(Expense expense) {
        this.current = Objects.requireNonNull(expense, "expense");
    }

    public static ExpenseHistory of(Expense expense) {
        return new ExpenseHistory(expense);
    }

    public Expense current() {
        return current;
    }

    public List<ExpenseCorrection> corrections() {
        return List.copyOf(corrections);
    }

    public ExpenseCorrection correct(Expense replacement, MemberId actor, Instant occurredAt) {
        Objects.requireNonNull(replacement, "replacement");
        if (correctedRevisions.contains(current.revision())) {
            throw new GroupRuleException("This expense revision has already been corrected.");
        }
        ExpenseCorrection correction = ExpenseCorrection.of(current, replacement, actor, occurredAt);
        correctedRevisions.add(current.revision());
        corrections.add(correction);
        current = replacement;
        return correction;
    }
}
