package com.dutch.ledger.settlement;

import com.dutch.ledger.group.GroupRuleException;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcSettlementRepository {

    private final JdbcTemplate jdbc;

    public JdbcSettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void save(Settlement settlement) {
        jdbc.update("""
                INSERT INTO settlement (settlement_id, payer_member_id, payee_member_id,
                    total_minor_units, currency, status, occurred_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)
                """, settlement.id().value(), settlement.payer().value(), settlement.payee().value(),
                settlement.totalMinorUnits(), settlement.currency(),
                Timestamp.from(settlement.occurredAt()));
        for (SettlementAllocation allocation : settlement.allocations()) {
            jdbc.update("""
                    INSERT INTO settlement_allocation (settlement_id, scope_id, currency,
                        minor_units, payer_member_id, payee_member_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, settlement.id().value(), allocation.scopeId(), allocation.currency(),
                    allocation.minorUnits(), allocation.payer().value(), allocation.payee().value());
        }
    }

    @Transactional
    public void voidSettlement(SettlementVoid voidAction) {
        try {
            jdbc.update("""
                    INSERT INTO settlement_void (void_id, settlement_id, actor_member_id, occurred_at)
                    VALUES (?, ?, ?, ?)
                    """, "void_" + UUID.randomUUID().toString().replace("-", ""),
                    voidAction.settlementId().value(), voidAction.actor().value(),
                    Timestamp.from(voidAction.occurredAt()));
        } catch (DuplicateKeyException exception) {
            throw new GroupRuleException("This settlement has already been voided.");
        }
        int updated = jdbc.update("""
                UPDATE settlement SET status = 'VOIDED'
                WHERE settlement_id = ? AND status = 'ACTIVE'
                """, voidAction.settlementId().value());
        if (updated == 0) {
            throw new GroupRuleException("This settlement has already been voided.");
        }
    }

    public String status(SettlementId id) {
        return jdbc.queryForObject("SELECT status FROM settlement WHERE settlement_id = ?",
                String.class, id.value());
    }
}
