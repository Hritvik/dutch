package com.dutch.ledger.direct;

import com.dutch.ledger.group.MemberId;

/** A Direct Expense is visible only to Members in its payer/share snapshot. */
final class DirectInvolvement {

    private DirectInvolvement() {
    }

    static boolean involves(DirectExpense expense, MemberId member) {
        return expense.payers().stream().anyMatch(payer -> payer.memberId().equals(member))
                || expense.shares().stream().anyMatch(share -> share.memberId().equals(member));
    }
}
