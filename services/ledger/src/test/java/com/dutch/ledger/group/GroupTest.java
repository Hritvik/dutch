package com.dutch.ledger.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GroupTest {

    private static final GroupId GROUP = new GroupId("grp_012345678901234567");
    private static final MemberId ADMIN = new MemberId("mem_012345678901234567");
    private static final MemberId MEMBER = new MemberId("mem_112345678901234567");
    private static final MemberId OTHER = new MemberId("mem_212345678901234567");
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    private Group group() {
        return Group.create(GROUP, "Flat 12", "INR", ADMIN, NOW);
    }

    @Test
    void creation_makes_the_creator_an_active_admin() {
        Group group = group();

        assertThat(group.state()).isEqualTo(GroupState.ACTIVE);
        assertThat(group.currency()).isEqualTo("INR");
        assertThat(group.membership(ADMIN)).get()
                .extracting(Membership::state, Membership::role)
                .containsExactly(MembershipState.ACTIVE, GroupRole.ADMIN);
        assertThat(group.canManageMembers(ADMIN)).isTrue();
        assertThat(group.canCreateExpense(ADMIN)).isTrue();
    }

    @Test
    void an_invited_member_has_no_app_access_until_activation() {
        Group group = group();
        group.invite(ADMIN, MEMBER, GroupRole.MEMBER, NOW);

        assertThat(group.canRead(MEMBER)).isFalse();
        assertThat(group.canCollaborate(MEMBER)).isFalse();

        group.activate(MEMBER, MEMBER, NOW);
        assertThat(group.canRead(MEMBER)).isTrue();
        assertThat(group.canCollaborate(MEMBER)).isTrue();
        assertThat(group.canManageMembers(MEMBER)).isFalse();
    }

    @Test
    void an_inactive_member_retains_read_only_history_and_settleable_balances() {
        Group group = group();
        group.invite(ADMIN, MEMBER, GroupRole.MEMBER, NOW);
        group.activate(MEMBER, MEMBER, NOW);
        group.deactivate(ADMIN, MEMBER, NOW);

        assertThat(group.membership(MEMBER)).get()
                .extracting(Membership::state).isEqualTo(MembershipState.INACTIVE);
        assertThat(group.canRead(MEMBER)).isTrue();
        assertThat(group.canCollaborate(MEMBER)).isFalse();
        assertThat(group.canCreateExpense(MEMBER)).isFalse();
        assertThat(group.canRecordSettlement(MEMBER)).isFalse();
        assertThat(group.canCorrect(MEMBER)).isFalse();
        assertThat(group.canManageMembers(MEMBER)).isFalse();
    }

    @Test
    void removal_retains_the_membership_row_as_inactive() {
        Group group = group();
        group.invite(ADMIN, MEMBER, GroupRole.MEMBER, NOW);
        group.activate(MEMBER, MEMBER, NOW);
        group.remove(ADMIN, MEMBER, NOW);

        assertThat(group.memberships()).hasSize(2);
        assertThat(group.membership(MEMBER)).get()
                .extracting(Membership::state).isEqualTo(MembershipState.INACTIVE);
    }

    @Test
    void the_last_active_admin_cannot_be_demoted() {
        Group group = group();

        assertThatThrownBy(() -> group.changeRole(ADMIN, ADMIN, GroupRole.MEMBER, NOW))
                .isInstanceOf(LastAdminException.class);
    }

    @Test
    void the_last_active_admin_cannot_be_deactivated() {
        Group group = group();

        assertThatThrownBy(() -> group.deactivate(ADMIN, ADMIN, NOW))
                .isInstanceOf(LastAdminException.class);
    }

    @Test
    void a_second_admin_allows_the_first_to_step_down() {
        Group group = group();
        group.invite(ADMIN, MEMBER, GroupRole.MEMBER, NOW);
        group.activate(MEMBER, MEMBER, NOW);
        group.changeRole(ADMIN, MEMBER, GroupRole.ADMIN, NOW);
        group.changeRole(MEMBER, ADMIN, GroupRole.MEMBER, NOW);

        assertThat(group.membership(ADMIN)).get()
                .extracting(Membership::role).isEqualTo(GroupRole.MEMBER);
        assertThat(group.membership(MEMBER)).get()
                .extracting(Membership::role).isEqualTo(GroupRole.ADMIN);
    }

    @Test
    void an_archived_group_keeps_read_settlement_and_correction_but_stops_new_work() {
        Group group = group();
        group.invite(ADMIN, MEMBER, GroupRole.MEMBER, NOW);
        group.activate(MEMBER, MEMBER, NOW);
        group.archive(ADMIN, NOW);

        assertThat(group.canRead(MEMBER)).isTrue();
        assertThat(group.canRecordSettlement(MEMBER)).isTrue();
        assertThat(group.canCorrect(MEMBER)).isTrue();
        assertThat(group.canCollaborate(MEMBER)).isFalse();
        assertThat(group.canCreateExpense(MEMBER)).isFalse();
        assertThat(group.canManageMembers(MEMBER)).isFalse();
        assertThat(group.canManageMembers(ADMIN)).isFalse();
        assertThatThrownBy(() -> group.invite(ADMIN, OTHER, GroupRole.MEMBER, NOW))
                .isInstanceOf(GroupRuleException.class);

        group.reopen(ADMIN, NOW);
        assertThat(group.canManageMembers(ADMIN)).isTrue();
        assertThat(group.canCreateExpense(ADMIN)).isTrue();
    }

    @Test
    void a_non_member_cannot_read_or_collaborate() {
        Group group = group();

        assertThat(group.canRead(OTHER)).isFalse();
        assertThat(group.canCollaborate(OTHER)).isFalse();
        assertThat(group.canManageMembers(OTHER)).isFalse();
    }

    @Test
    void duplicate_membership_events_are_no_ops() {
        Group group = group();
        MembershipEvent event = new MembershipEvent(
                "evt_1", 1, MEMBER, MembershipState.ACTIVE, GroupRole.MEMBER, NOW);

        assertThat(group.applyMembershipEvent(event)).isTrue();
        assertThat(group.version()).isEqualTo(1);
        assertThat(group.applyMembershipEvent(event)).isFalse();
        assertThat(group.version()).isEqualTo(1);
        assertThat(group.membership(MEMBER)).get()
                .extracting(Membership::state).isEqualTo(MembershipState.ACTIVE);
    }

    @Test
    void a_stale_membership_event_does_not_move_the_group_backwards() {
        Group group = group();
        group.applyMembershipEvent(new MembershipEvent(
                "evt_2", 2, MEMBER, MembershipState.INACTIVE, GroupRole.MEMBER, NOW));

        boolean applied = group.applyMembershipEvent(new MembershipEvent(
                "evt_1", 1, MEMBER, MembershipState.ACTIVE, GroupRole.MEMBER, NOW));

        assertThat(applied).isFalse();
        assertThat(group.membership(MEMBER)).get()
                .extracting(Membership::state).isEqualTo(MembershipState.INACTIVE);
        assertThat(group.version()).isEqualTo(2);
    }

    @Test
    void a_replayed_event_cannot_remove_the_last_active_admin() {
        Group group = group();

        assertThatThrownBy(() -> group.applyMembershipEvent(new MembershipEvent(
                "evt_bad", 1, ADMIN, MembershipState.INACTIVE, GroupRole.ADMIN, NOW)))
                .isInstanceOf(LastAdminException.class);

        assertThat(group.membership(ADMIN)).get()
                .extracting(Membership::state).isEqualTo(MembershipState.ACTIVE);
        assertThat(group.version()).isZero();
    }

    @Test
    void the_view_carries_no_contact_identifiers() throws Exception {
        Group group = group();
        group.invite(ADMIN, MEMBER, GroupRole.MEMBER, NOW);

        String json = new ObjectMapper().writeValueAsString(group.view());

        assertThat(json).doesNotContain("email", "phone", "identifier", "password");
        assertThat(json).contains("memberId", "role", "state", "currency");
    }

    @Test
    void invalid_group_fields_are_rejected() {
        assertThatThrownBy(() -> Group.create(GROUP, " ", "INR", ADMIN, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Group.create(GROUP, "Flat", "rupee", ADMIN, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GroupId("grp_short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemberId("user_012345678901234567"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
