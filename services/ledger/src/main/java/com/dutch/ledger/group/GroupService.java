package com.dutch.ledger.group;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GroupService {

    private final GroupRepository groups;
    private final MemberResolver members;

    public GroupService(GroupRepository groups, MemberResolver members) {
        this.groups = groups;
        this.members = members;
    }

    public GroupView create(String appSubject, String name, String currency) {
        MemberId creator = requireMember(appSubject);
        Group group = Group.create(newGroupId(), name, currency, creator, Instant.now());
        groups.saveNew(group);
        return group.view();
    }

    public List<GroupView> list(String appSubject) {
        MemberId member = requireMember(appSubject);
        return groups.findReadableBy(member).stream().map(Group::view).toList();
    }

    public GroupView get(String appSubject, String groupId) {
        MemberId member = requireMember(appSubject);
        return requireReadable(member, groupId).view();
    }

    public GroupView invite(String appSubject, String groupId, String invitee, GroupRole role, Long expectedVersion) {
        MemberId actor = requireMember(appSubject);
        Group group = requireAdmin(actor, groupId);
        requireVersion(group, expectedVersion);
        long expected = group.version();
        group.invite(actor, new MemberId(invitee), role, Instant.now());
        groups.save(group, expected);
        return group.view();
    }

    public GroupView addPlaceholder(String appSubject, String groupId, String placeholder, GroupRole role, Long expectedVersion) {
        MemberId actor = requireMember(appSubject);
        Group group = requireAdmin(actor, groupId);
        requireVersion(group, expectedVersion);
        long expected = group.version();
        group.addPlaceholder(actor, new MemberId(placeholder), role, Instant.now());
        groups.save(group, expected);
        return group.view();
    }

    public GroupView activate(String appSubject, String groupId, String memberId, Long expectedVersion) {
        MemberId actor = requireMember(appSubject);
        MemberId target = new MemberId(memberId);
        Group group = requireGroup(groupId);
        if (!actor.equals(target)) {
            requireAdmin(actor, group);
        }
        requireVersion(group, expectedVersion);
        long expected = group.version();
        group.activate(actor, target, Instant.now());
        groups.save(group, expected);
        return group.view();
    }

    public GroupView deactivate(String appSubject, String groupId, String memberId, Long expectedVersion) {
        MemberId actor = requireMember(appSubject);
        MemberId target = new MemberId(memberId);
        Group group = requireReadable(actor, groupId);
        if (!actor.equals(target)) {
            requireAdmin(actor, group);
        }
        requireVersion(group, expectedVersion);
        long expected = group.version();
        group.deactivate(actor, target, Instant.now());
        groups.save(group, expected);
        return group.view();
    }

    public GroupView changeRole(String appSubject, String groupId, String memberId, GroupRole role, Long expectedVersion) {
        MemberId actor = requireMember(appSubject);
        Group group = requireAdmin(actor, groupId);
        requireVersion(group, expectedVersion);
        long expected = group.version();
        group.changeRole(actor, new MemberId(memberId), role, Instant.now());
        groups.save(group, expected);
        return group.view();
    }

    public GroupView archive(String appSubject, String groupId, Long expectedVersion) {
        MemberId actor = requireMember(appSubject);
        Group group = requireAdmin(actor, groupId);
        requireVersion(group, expectedVersion);
        long expected = group.version();
        group.archive(actor, Instant.now());
        groups.save(group, expected);
        return group.view();
    }

    public GroupView reopen(String appSubject, String groupId, Long expectedVersion) {
        MemberId actor = requireMember(appSubject);
        Group group = requireAdmin(actor, groupId);
        requireVersion(group, expectedVersion);
        long expected = group.version();
        group.reopen(actor, Instant.now());
        groups.save(group, expected);
        return group.view();
    }

    private MemberId requireMember(String appSubject) {
        return members.resolve(appSubject).orElseThrow(GroupAccessDeniedException::new);
    }

    private Group requireGroup(String groupId) {
        return groups.findById(new GroupId(groupId)).orElseThrow(GroupAccessDeniedException::new);
    }

    private Group requireReadable(MemberId actor, String groupId) {
        Group group = requireGroup(groupId);
        if (!group.canRead(actor)) {
            throw new GroupAccessDeniedException();
        }
        return group;
    }

    private Group requireAdmin(MemberId actor, String groupId) {
        Group group = requireReadable(actor, groupId);
        return requireAdmin(actor, group);
    }

    private Group requireAdmin(MemberId actor, Group group) {
        boolean admin = group.membership(actor).filter(Membership::isActiveAdmin).isPresent();
        if (!admin) {
            throw new GroupForbiddenException();
        }
        return group;
    }

    private static void requireVersion(Group group, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != group.version()) {
            throw new ConcurrentGroupUpdateException();
        }
    }

    private static GroupId newGroupId() {
        return new GroupId("grp_" + UUID.randomUUID().toString().replace("-", ""));
    }
}
