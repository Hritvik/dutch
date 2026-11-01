package com.dutch.ledger.group;

import java.util.List;

/** The PII-free Group projection served to the mobile client. */
public record GroupView(
        GroupId id,
        String name,
        String currency,
        GroupState state,
        long version,
        List<MembershipView> memberships) {

    public GroupView {
        memberships = List.copyOf(memberships);
    }

    public static GroupView from(Group group) {
        return new GroupView(
                group.id(),
                group.name(),
                group.currency(),
                group.state(),
                group.version(),
                group.memberships().stream().map(MembershipView::from).toList());
    }

    public record MembershipView(MemberId memberId, GroupRole role, MembershipState state) {
        static MembershipView from(Membership membership) {
            return new MembershipView(membership.memberId(), membership.role(), membership.state());
        }
    }
}
