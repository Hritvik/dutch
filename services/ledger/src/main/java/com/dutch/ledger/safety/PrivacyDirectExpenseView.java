package com.dutch.ledger.safety;

import com.dutch.ledger.direct.DirectExpense;
import com.dutch.ledger.expense.ParticipantShare;
import com.dutch.ledger.expense.PayerContribution;
import java.time.Instant;
import java.util.List;

/** The privacy-safe Direct Expense projection: no entries or internal stream ids. */
public record PrivacyDirectExpenseView(
        String id,
        String description,
        long totalMinorUnits,
        String currency,
        String splitMethod,
        List<PayerContribution> payers,
        List<ParticipantShare> shares,
        Instant occurredAt,
        long revision) {

    public static PrivacyDirectExpenseView from(DirectExpense expense) {
        return new PrivacyDirectExpenseView(expense.id().value(), expense.description(),
                expense.totalMinorUnits(), expense.currency(), expense.splitMethod().name(),
                expense.payers(), expense.shares(), expense.occurredAt(), expense.revision());
    }
}
