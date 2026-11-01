package com.dutch.ledger.direct;

import com.dutch.ledger.expense.ExpenseId;
import java.util.List;
import java.util.Optional;

public interface DirectExpenseRepository {
    void save(DirectExpense expense);

    Optional<DirectExpense> find(ExpenseId id);

    List<String> streamIds(ExpenseId id);
}
