package com.dutch.ledger.group;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcGroupRepository implements GroupRepository {

    private final JdbcTemplate jdbc;

    public JdbcGroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Group> findById(GroupId id) {
        return load(id, false);
    }

    @Override
    public Optional<Group> findByIdForUpdate(GroupId id) {
        return load(id, true);
    }

    private Optional<Group> load(GroupId id, boolean lock) {
        String sql = """
                SELECT group_id, name, currency, state, aggregate_version
                FROM member_group WHERE group_id = ?
                """ + (lock ? " FOR UPDATE" : "");
        List<GroupRow> rows = jdbc.query(sql, (result, index) -> new GroupRow(
                result.getString("group_id"),
                result.getString("name"),
                result.getString("currency"),
                GroupState.valueOf(result.getString("state")),
                result.getLong("aggregate_version")), id.value());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        GroupRow row = rows.get(0);
        List<Membership> memberships = jdbc.query("""
                SELECT member_id, state, role, joined_at, updated_at
                FROM group_membership WHERE group_id = ? ORDER BY member_id
                """, (result, index) -> new Membership(
                new MemberId(result.getString("member_id")),
                MembershipState.valueOf(result.getString("state")),
                GroupRole.valueOf(result.getString("role")),
                result.getTimestamp("joined_at").toInstant(),
                result.getTimestamp("updated_at").toInstant()), id.value());
        return Optional.of(Group.restore(
                new GroupId(row.groupId()), row.name(), row.currency(), row.state(),
                row.version(), memberships));
    }

    @Override
    public List<Group> findReadableBy(MemberId memberId) {
        List<String> ids = jdbc.queryForList("""
                SELECT group_id FROM group_membership
                WHERE member_id = ? AND state IN ('ACTIVE', 'INACTIVE')
                ORDER BY group_id
                """, String.class, memberId.value());
        return ids.stream()
                .map(id -> findById(new GroupId(id)))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    @Transactional
    public void saveNew(Group group) {
        jdbc.update("""
                INSERT INTO member_group
                    (group_id, name, currency, state, aggregate_version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, now(), now())
                """, group.id().value(), group.name(), group.currency(),
                group.state().name(), group.version());
        writeMemberships(group);
    }

    @Override
    @Transactional
    public void save(Group group, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE member_group SET
                    name = ?, currency = ?, state = ?, aggregate_version = ?, updated_at = now()
                WHERE group_id = ? AND aggregate_version = ?
                """, group.name(), group.currency(), group.state().name(), group.version(),
                group.id().value(), expectedVersion);
        if (updated == 0) {
            throw new ConcurrentGroupUpdateException();
        }
        writeMemberships(group);
    }

    private void writeMemberships(Group group) {
        for (Membership membership : group.memberships()) {
            jdbc.update("""
                    INSERT INTO group_membership
                        (group_id, member_id, state, role, joined_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (group_id, member_id) DO UPDATE SET
                        state = EXCLUDED.state,
                        role = EXCLUDED.role,
                        updated_at = EXCLUDED.updated_at
                    """, group.id().value(), membership.memberId().value(),
                    membership.state().name(), membership.role().name(),
                    Timestamp.from(membership.joinedAt()), Timestamp.from(membership.updatedAt()));
        }
    }

    private record GroupRow(String groupId, String name, String currency, GroupState state, long version) {
    }
}
