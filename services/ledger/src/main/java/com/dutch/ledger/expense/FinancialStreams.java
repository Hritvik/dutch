package com.dutch.ledger.expense;

import com.dutch.ledger.group.GroupId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Locks a financial stream for a command and rebuilds its member projection from
 * the immutable Obligation Entries. Group and direct-pair streams share the same
 * projection shape.
 */
@Component
public class FinancialStreams {

    private final JdbcTemplate jdbc;

    public FinancialStreams(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long lockGroup(GroupId groupId, String currency) {
        return lock("group:" + groupId.value() + ":" + currency, true, groupId.value(), currency);
    }

    public long lockDirect(String streamId, String currency) {
        return lock(streamId, false, null, currency);
    }

    private long lock(String streamId, boolean group, String groupId, String currency) {
        Long version = group
                ? jdbc.queryForObject("""
                        INSERT INTO financial_stream (stream_id, scope_type, group_id, currency)
                        VALUES (?, 'GROUP', ?, ?)
                        ON CONFLICT (stream_id) DO UPDATE SET
                            stream_version = financial_stream.stream_version + 1,
                            updated_at = now()
                        RETURNING stream_version
                        """, Long.class, streamId, groupId, currency)
                : jdbc.queryForObject("""
                        INSERT INTO financial_stream (stream_id, scope_type, currency)
                        VALUES (?, 'DIRECT_PAIR', ?)
                        ON CONFLICT (stream_id) DO UPDATE SET
                            stream_version = financial_stream.stream_version + 1,
                            updated_at = now()
                        RETURNING stream_version
                        """, Long.class, streamId, currency);
        if (version == null) {
            throw new IllegalStateException("financial stream is missing");
        }
        return version;
    }

    public void rebuild(String streamId, String currency) {
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
}
