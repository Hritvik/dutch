package com.dutch.ledger.expense;

import com.dutch.ledger.group.GroupId;
import java.util.List;
import java.util.Optional;

public interface ExpenseRepository {
    void save(Expense expense);

    Optional<Expense> findExpense(ExpenseId id);

    List<ObligationEntry> entriesFor(GroupId groupId, String currency);

    BalanceProjection projectionFor(GroupId groupId, String currency);
}
