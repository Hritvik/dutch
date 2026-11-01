package com.dutch.ledger.expense;

import com.dutch.ledger.group.GroupId;
import java.util.List;

public interface ExpenseRepository {
    void save(Expense expense);

    List<ObligationEntry> entriesFor(GroupId groupId, String currency);

    BalanceProjection projectionFor(GroupId groupId, String currency);
}
