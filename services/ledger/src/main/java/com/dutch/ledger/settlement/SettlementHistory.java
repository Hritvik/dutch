package com.dutch.ledger.settlement;

import com.dutch.ledger.group.GroupRuleException;
import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import java.util.Objects;

/** Domain guard that allows at most one void for a Settlement. */
public final class SettlementHistory {

    private final Settlement settlement;
    private SettlementVoid voidAction;

    private SettlementHistory(Settlement settlement) {
        this.settlement = Objects.requireNonNull(settlement, "settlement");
    }

    public static SettlementHistory of(Settlement settlement) {
        return new SettlementHistory(settlement);
    }

    public Settlement settlement() {
        return settlement;
    }

    public SettlementVoid voidIt(MemberId actor, Instant occurredAt) {
        if (voidAction != null) {
            throw new GroupRuleException("This settlement has already been voided.");
        }
        voidAction = SettlementVoid.of(settlement, actor, occurredAt);
        return voidAction;
    }
}
