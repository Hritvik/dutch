package com.dutch.ledger.group;

import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Replays one membership fact exactly once, in aggregate-version order. */
@Service
public class JdbcGroupMembershipEventConsumer {

    private final JdbcTemplate jdbc;
    private final GroupRepository groups;

    public JdbcGroupMembershipEventConsumer(JdbcTemplate jdbc, GroupRepository groups) {
        this.jdbc = jdbc;
        this.groups = groups;
    }

    /**
     * @return true when the group advanced, false for a duplicate or already-applied version
     * @throws GroupRuleException when the event would skip a version
     */
    @Transactional
    public boolean consume(GroupMembershipEvent event) {
        Group group = groups.findById(event.groupId()).orElseThrow(GroupAccessDeniedException::new);
        long expectedVersion = group.version();
        if (event.aggregateVersion() <= expectedVersion) {
            return false;
        }
        if (event.aggregateVersion() != expectedVersion + 1) {
            throw new GroupRuleException("Membership event version skipped ahead.");
        }
        int inserted = jdbc.update("""
                INSERT INTO group_membership_event
                    (event_id, group_id, aggregate_version, member_id, state, role, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, event.eventId(), event.groupId().value(), event.aggregateVersion(),
                event.memberId().value(), event.state().name(), event.role().name(),
                Timestamp.from(event.occurredAt()));
        if (inserted == 0) {
            return false;
        }
        boolean applied = group.applyMembershipEvent(new MembershipEvent(
                event.eventId(), event.aggregateVersion(), event.memberId(), event.state(),
                event.role(), event.occurredAt()));
        if (!applied) {
            return false;
        }
        groups.save(group, expectedVersion);
        return true;
    }
}
