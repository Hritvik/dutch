package com.dutch.ledger.settlement;

import com.dutch.ledger.expense.AllocationAlgorithmVersion;
import com.dutch.ledger.group.GroupRuleException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcSettlementRepository implements SettlementRepository {

    private final JdbcTemplate jdbc;

    public JdbcSettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
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
        apply(settlement.id().value(), settlement.allocations(), "SETTLEMENT");
    }

    @Override
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
        apply(voidAction.settlementId().value(), voidAction.reversedAllocations(), "SETTLEMENT_VOID");
    }

    @Override
    public String status(SettlementId id) {
        return jdbc.queryForObject("SELECT status FROM settlement WHERE settlement_id = ?",
                String.class, id.value());
    }

    @Override
    public Optional<Settlement> find(SettlementId id) {
        List<SettlementRow> rows = jdbc.query("""
                SELECT payer_member_id, payee_member_id, total_minor_units, currency, occurred_at
                FROM settlement WHERE settlement_id = ?
                """, (result, index) -> new SettlementRow(
                result.getString("payer_member_id"), result.getString("payee_member_id"),
                result.getLong("total_minor_units"), result.getString("currency"),
                result.getTimestamp("occurred_at").toInstant()), id.value());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        SettlementRow row = rows.get(0);
        List<SettlementAllocation> allocations = jdbc.query("""
                SELECT scope_id, currency, minor_units, payer_member_id, payee_member_id
                FROM settlement_allocation WHERE settlement_id = ? ORDER BY scope_id
                """, (result, index) -> new SettlementAllocation(
                result.getString("scope_id"), result.getString("currency"),
                result.getLong("minor_units"),
                new com.dutch.ledger.group.MemberId(result.getString("payer_member_id")),
                new com.dutch.ledger.group.MemberId(result.getString("payee_member_id"))), id.value());
        return Optional.of(new Settlement(id,
                new com.dutch.ledger.group.MemberId(row.payerId()),
                new com.dutch.ledger.group.MemberId(row.payeeId()),
                row.totalMinorUnits(), row.currency(), allocations, row.occurredAt()));
    }

    /**
     * Writes one immutable entry per allocation: the payer's debit is reduced and the
     * payee's credit is reduced, so the entry runs from payee to payer. A void passes
     * its already-swapped allocations and therefore restores the original positions.
     */
    private void apply(String settlementId, List<SettlementAllocation> allocations, String kind) {
        Set<String> streams = new TreeSet<>();
        for (SettlementAllocation allocation : allocations) {
            String streamId = allocation.scopeId();
            String groupId = groupIdOf(streamId);
            Long streamVersion = groupId == null
                    ? jdbc.queryForObject("""
                            INSERT INTO financial_stream (stream_id, scope_type, currency)
                            VALUES (?, 'DIRECT_PAIR', ?)
                            ON CONFLICT (stream_id) DO UPDATE SET
                                stream_version = financial_stream.stream_version + 1,
                                updated_at = now()
                            RETURNING stream_version
                            """, Long.class, streamId, allocation.currency())
                    : jdbc.queryForObject("""
                            INSERT INTO financial_stream (stream_id, scope_type, group_id, currency)
                            VALUES (?, 'GROUP', ?, ?)
                            ON CONFLICT (stream_id) DO UPDATE SET
                                stream_version = financial_stream.stream_version + 1,
                                updated_at = now()
                            RETURNING stream_version
                            """, Long.class, streamId, groupId, allocation.currency());
            if (streamVersion == null) {
                throw new IllegalStateException("financial stream is missing");
            }
            jdbc.update("""
                    INSERT INTO obligation_entry (expense_id, revision, group_id, stream_id,
                        debtor_member_id, creditor_member_id, minor_units, currency,
                        algorithm_version, entry_kind, settlement_id)
                    VALUES (NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, groupId, streamId, allocation.payee().value(), allocation.payer().value(),
                    allocation.minorUnits(), allocation.currency(),
                    AllocationAlgorithmVersion.SETTLEMENT_ALLOCATION_V1, kind, settlementId);
            streams.add(streamId);
        }
        for (String streamId : streams) {
            rebuildProjection(streamId, allocations.stream()
                    .filter(allocation -> allocation.scopeId().equals(streamId))
                    .findFirst().orElseThrow().currency());
        }
    }

    private void rebuildProjection(String streamId, String currency) {
        Long streamVersion = jdbc.queryForObject(
                "SELECT stream_version FROM financial_stream WHERE stream_id = ?",
                Long.class, streamId);
        if (streamVersion == null) {
            throw new IllegalStateException("financial stream is missing");
        }
        jdbc.update("DELETE FROM member_balance_projection WHERE stream_id = ?", streamId);
        jdbc.update("""
                INSERT INTO member_balance_projection
                    (stream_id, member_id, currency, balance_minor_units, stream_version)
                SELECT ?, member_id, ?, SUM(amount), ?
                FROM (
                    SELECT creditor_member_id AS member_id, minor_units AS amount
                    FROM obligation_entry WHERE stream_id = ?
                    UNION ALL
                    SELECT debtor_member_id AS member_id, -minor_units AS amount
                    FROM obligation_entry WHERE stream_id = ?
                ) net
                GROUP BY member_id
                """, streamId, currency, streamVersion, streamId, streamId);
    }

    private static String groupIdOf(String streamId) {
        if (!streamId.startsWith("group:")) {
            return null;
        }
        String[] parts = streamId.split(":", 3);
        if (parts.length != 3 || !parts[2].matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("a group scope must be group:<groupId>:<currency>");
        }
        if (!parts[1].matches("^grp_[A-Za-z0-9_-]{16,128}$")) {
            throw new IllegalArgumentException("a group scope must carry a valid group id");
        }
        return parts[1];
    }

    private record SettlementRow(String payerId, String payeeId, long totalMinorUnits,
                                  String currency, java.time.Instant occurredAt) {
    }
}
