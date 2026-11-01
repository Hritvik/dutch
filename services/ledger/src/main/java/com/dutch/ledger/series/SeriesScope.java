package com.dutch.ledger.series;

import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.MemberId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Where a Series creates its Expenses: one Group, or a fixed set of direct
 * members. A Direct series is not a lasting workspace; each occurrence is a
 * one-off Direct Expense.
 */
public record SeriesScope(ScopeType type, GroupId groupId, List<MemberId> memberIds) {

    public enum ScopeType {
        GROUP,
        DIRECT
    }

    public SeriesScope {
        Objects.requireNonNull(type, "type");
        if (type == ScopeType.GROUP) {
            Objects.requireNonNull(groupId, "groupId");
            if (memberIds != null && !memberIds.isEmpty()) {
                throw new IllegalArgumentException("a Group series does not carry direct members");
            }
            memberIds = List.of();
        } else {
            if (groupId != null) {
                throw new IllegalArgumentException("a Direct series has no group");
            }
            if (memberIds == null || memberIds.size() < 2) {
                throw new IllegalArgumentException("a Direct series needs at least two members");
            }
            if (new HashSet<>(memberIds).size() != memberIds.size()) {
                throw new IllegalArgumentException("Direct series members must be unique");
            }
            memberIds = memberIds.stream().sorted().toList();
        }
    }

    public static SeriesScope group(GroupId groupId) {
        return new SeriesScope(ScopeType.GROUP, groupId, List.of());
    }

    public static SeriesScope direct(List<MemberId> memberIds) {
        return new SeriesScope(ScopeType.DIRECT, null, memberIds);
    }
}
