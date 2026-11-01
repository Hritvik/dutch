package com.dutch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.ledger.group.ConcurrentGroupUpdateException;
import com.dutch.ledger.group.Group;
import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.GroupRepository;
import com.dutch.ledger.group.GroupRole;
import com.dutch.ledger.group.JdbcGroupRepository;
import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class GroupRepositoryIntegrationTest {

    private static final GroupId GROUP = new GroupId("grp_012345678901234567");
    private static final MemberId ADMIN = new MemberId("mem_012345678901234567");
    private static final MemberId MEMBER = new MemberId("mem_112345678901234567");
    private static final MemberId OTHER = new MemberId("mem_212345678901234567");
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_repository_test");

    private static GroupRepository repository;
    private static TransactionTemplate transactions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        repository = new JdbcGroupRepository(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @Test
    void persists_and_reloads_a_group_with_its_memberships() {
        transactions.executeWithoutResult(status ->
                repository.saveNew(Group.create(GROUP, "Flat 12", "INR", ADMIN, NOW)));

        Group loaded = repository.findById(GROUP).orElseThrow();
        assertThat(loaded.name()).isEqualTo("Flat 12");
        assertThat(loaded.currency()).isEqualTo("INR");
        assertThat(loaded.membership(ADMIN)).get()
                .extracting(membership -> membership.role()).isEqualTo(GroupRole.ADMIN);

        loaded.invite(ADMIN, MEMBER, GroupRole.MEMBER, NOW);
        transactions.executeWithoutResult(status -> repository.save(loaded, 0));

        Group reloaded = repository.findById(GROUP).orElseThrow();
        assertThat(reloaded.version()).isEqualTo(1);
        assertThat(reloaded.membership(MEMBER)).get()
                .extracting(membership -> membership.state().name()).isEqualTo("INVITED");
        assertThat(repository.findReadableBy(ADMIN)).extracting(Group::id).contains(GROUP);
        assertThat(repository.findReadableBy(OTHER)).isEmpty();
    }

    @Test
    void locks_the_group_row_for_a_command() throws Exception {
        GroupId lockedGroup = new GroupId("grp_812345678901234567");
        transactions.executeWithoutResult(status ->
                repository.saveNew(Group.create(lockedGroup, "Lock", "INR", ADMIN, NOW)));

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> transactions.executeWithoutResult(status -> {
                repository.findByIdForUpdate(lockedGroup);
                locked.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> waiter = pool.submit(() -> assertThatThrownBy(() ->
                    transactions.executeWithoutResult(status -> {
                        jdbc.execute("SET LOCAL lock_timeout = '200ms'");
                        repository.findByIdForUpdate(lockedGroup);
                    })).hasStackTraceContaining("lock timeout"));

            Thread.sleep(500);
            waiter.get(5, TimeUnit.SECONDS);
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void rejects_a_stale_writer_at_the_same_version() {
        GroupId id = new GroupId("grp_112345678901234567");
        transactions.executeWithoutResult(status ->
                repository.saveNew(Group.create(id, "Flat 13", "INR", ADMIN, NOW)));

        Group first = repository.findById(id).orElseThrow();
        Group second = repository.findById(id).orElseThrow();
        first.invite(ADMIN, MEMBER, GroupRole.MEMBER, NOW);
        second.invite(ADMIN, OTHER, GroupRole.MEMBER, NOW);

        transactions.executeWithoutResult(status -> repository.save(first, 0));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> repository.save(second, 0)))
                .isInstanceOf(ConcurrentGroupUpdateException.class);
    }
}
