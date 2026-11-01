package com.dutch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.ledger.group.Group;
import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.GroupMembershipEvent;
import com.dutch.ledger.group.GroupRepository;
import com.dutch.ledger.group.GroupRole;
import com.dutch.ledger.group.GroupRuleException;
import com.dutch.ledger.group.JdbcGroupMembershipEventConsumer;
import com.dutch.ledger.group.JdbcGroupRepository;
import com.dutch.ledger.group.MemberId;
import com.dutch.ledger.group.MembershipState;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcGroupMembershipEventConsumerIntegrationTest {

    private static final MemberId ADMIN = new MemberId("mem_012345678901234567");
    private static final MemberId MEMBER = new MemberId("mem_112345678901234567");
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_membership_event_test");

    private static GroupRepository repository;
    private static JdbcGroupMembershipEventConsumer consumer;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void prepare() {
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        repository = new JdbcGroupRepository(jdbc);
        consumer = new JdbcGroupMembershipEventConsumer(jdbc, repository);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @Test
    void applies_events_once_and_in_order() {
        GroupId id = new GroupId("grp_312345678901234567");
        transactions.executeWithoutResult(status ->
                repository.saveNew(Group.create(id, "Flat", "INR", ADMIN, NOW)));

        assertThat(apply(event("evt_1", id, 1, MEMBER, MembershipState.INVITED, GroupRole.MEMBER))).isTrue();
        assertThat(repository.findById(id).orElseThrow().version()).isEqualTo(1);

        assertThat(apply(event("evt_1", id, 1, MEMBER, MembershipState.INVITED, GroupRole.MEMBER))).isFalse();
        assertThat(repository.findById(id).orElseThrow().version()).isEqualTo(1);

        assertThat(apply(event("evt_2", id, 2, MEMBER, MembershipState.ACTIVE, GroupRole.MEMBER))).isTrue();
        Group group = repository.findById(id).orElseThrow();
        assertThat(group.version()).isEqualTo(2);
        assertThat(group.membership(MEMBER)).get()
                .extracting(membership -> membership.state()).isEqualTo(MembershipState.ACTIVE);
    }

    @Test
    void ignores_an_already_applied_version_with_another_event_id() {
        GroupId id = new GroupId("grp_412345678901234567");
        transactions.executeWithoutResult(status ->
                repository.saveNew(Group.create(id, "Flat", "INR", ADMIN, NOW)));
        apply(event("evt_10", id, 1, MEMBER, MembershipState.INVITED, GroupRole.MEMBER));

        assertThat(apply(event("evt_11", id, 1, MEMBER, MembershipState.ACTIVE, GroupRole.MEMBER))).isFalse();
        assertThat(repository.findById(id).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void rejects_a_version_gap_without_advancing_the_group() {
        GroupId id = new GroupId("grp_512345678901234567");
        transactions.executeWithoutResult(status ->
                repository.saveNew(Group.create(id, "Flat", "INR", ADMIN, NOW)));

        assertThatThrownBy(() -> apply(
                event("evt_20", id, 2, MEMBER, MembershipState.INVITED, GroupRole.MEMBER)))
                .isInstanceOf(GroupRuleException.class);

        assertThat(repository.findById(id).orElseThrow().version()).isZero();
    }

    @Test
    void a_replay_cannot_remove_the_last_admin() {
        GroupId id = new GroupId("grp_612345678901234567");
        transactions.executeWithoutResult(status ->
                repository.saveNew(Group.create(id, "Flat", "INR", ADMIN, NOW)));

        assertThatThrownBy(() -> apply(
                event("evt_30", id, 1, ADMIN, MembershipState.INACTIVE, GroupRole.ADMIN)))
                .isInstanceOf(GroupRuleException.class);

        assertThat(repository.findById(id).orElseThrow().version()).isZero();
    }

    private static boolean apply(GroupMembershipEvent event) {
        Boolean applied = transactions.execute(status -> consumer.consume(event));
        return Boolean.TRUE.equals(applied);
    }

    private static GroupMembershipEvent event(String eventId, GroupId groupId, long version,
                                              MemberId memberId, MembershipState state, GroupRole role) {
        return new GroupMembershipEvent(eventId, groupId, version, memberId, state, role, NOW);
    }
}
