package com.dutch.ledger.group;

import com.dutch.ledger.sync.SyncJournal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class GroupService {

    private final GroupRepository groups;
    private final MemberResolver members;
    private final SyncJournal journal;
    private final ObjectMapper objectMapper;

    public GroupService(GroupRepository groups, MemberResolver members, SyncJournal journal,
                        ObjectMapper objectMapper) {
        this.groups = groups;
        this.members = members;
        this.journal = journal;
        this.objectMapper = objectMapper;
    }

    public GroupView create(String appSubject, String name, String currency) {
        MemberId creator = requireMember(appSubject);
        Group group = Group.create(newGroupId(), name, currency, creator, Instant.now());
        groups.saveNew(group);
        append(group);
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
        return persist(group, expected);
    }

    public GroupView addPlaceholder(String appSubject, String groupId, String placeholder, GroupRole role, Long expectedVersion) {
        MemberId actor = requireMember(appSubject);
        Group group = requireAdmin(actor, groupId);
        requireVersion(group, expectedVersion);
        long expected = group.version();
        group.addPlaceholder(actor, new MemberId(placeholder), role, Instant.now());
        return persist(group, expected);
    }

    public GroupView activate(String appSubject, String groupId, String memberId, Long expectedVersion) {
        MemberId actor = requireMember(appSubject);
        MemberId target = new MemberId(memberId);
        Group group = actor.equals(target) ? requireGroup(groupId) : requireReadable(actor, groupId);
        Membership actorMembership = group.membership(actor).orElseThrow(GroupAccessDeniedException::new);
        if (!actor.equals(target)) {
            requireAdmin(actor, group);
        } else if (actorMembership.state() == MembershipState.PLACEHOLDER) {
            throw new GroupForbiddenException();
        }
        requireVersion(group, expectedVersion);
        long expected = group.version();
        group.activate(actor, target, Instant.now());
        return persist(group, expected);
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
        return persist(group, expected);
    }

    public GroupView changeRole(String appSubject, String groupId, String memberId, GroupRole role, Long expectedVersion) {
        MemberId actor = requireMember(appSubject);
        Group group = requireAdmin(actor, groupId);
        requireVersion(group, expectedVersion);
        long expected = group.version();
        group.changeRole(actor, new MemberId(memberId), role, Instant.now());
        return persist(group, expected);
    }

    public GroupView archive(String appSubject, String groupId, Long expectedVersion) {
        MemberId actor = requireMember(appSubject);
        Group group = requireAdmin(actor, groupId);
        requireVersion(group, expectedVersion);
        long expected = group.version();
        group.archive(actor, Instant.now());
        return persist(group, expected);
    }

    public GroupView reopen(String appSubject, String groupId, Long expectedVersion) {
        MemberId actor = requireMember(appSubject);
        Group group = requireAdmin(actor, groupId);
        requireVersion(group, expectedVersion);
        long expected = group.version();
        group.reopen(actor, Instant.now());
        return persist(group, expected);
    }

    protected GroupView persist(Group group, long expected) {
        Group previous = groups.findById(group.id()).orElse(group);
        groups.save(group, expected);
        append(group);
        appendAccessChanges(previous, group);
        return group.view();
    }

    /** An Inactive Membership keeps read-only history, announced as an access change. */
    private void appendAccessChanges(Group previous, Group group) {
        for (Membership membership : group.memberships()) {
            boolean wasActive = previous.membership(membership.memberId())
                    .map(Membership::isActive).orElse(false);
            if (wasActive && membership.state() == MembershipState.INACTIVE) {
                journal.append(List.of(membership.memberId()), "ACCESS", group.id().value(),
                        "access_changed", group.version(),
                        Map.of("groupId", group.id().value(), "access", "read"),
                        null, Instant.now());
            }
        }
    }

    private void append(Group group) {
        List<MemberId> recipients = group.memberships().stream()
                .filter(Membership::hasAppAccess)
                .map(Membership::memberId)
                .toList();
        if (recipients.isEmpty()) {
            return;
        }
        journal.append(recipients, "GROUP", group.id().value(), "upsert", group.version(),
                objectMapper.convertValue(group.view(), Map.class), null, Instant.now());
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
