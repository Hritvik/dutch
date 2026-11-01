package com.dutch.ledger.settlement;

import java.util.Optional;

public interface SettlementRepository {
    void save(Settlement settlement);

    void voidSettlement(SettlementVoid voidAction);

    Optional<Settlement> find(SettlementId id);

    String status(SettlementId id);
}
