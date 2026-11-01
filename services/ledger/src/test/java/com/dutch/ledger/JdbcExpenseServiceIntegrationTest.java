package com.dutch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.ledger.expense.CreateExpenseRequest;
import com.dutch.ledger.expense.ExpenseService;
import com.dutch.ledger.expense.ExpenseView;
import com.dutch.ledger.expense.IdempotencyConflictException;
import com.dutch.ledger.expense.SplitMethod;
import com.dutch.ledger.group.Group;
import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.GroupRepository;
import com.dutch.ledger.group.GroupRole;
import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class JdbcExpenseServiceIntegrationTest {

    private static final MemberId ADMIN = new MemberId("mem_012345678901234567");
    private static final MemberId MEMBER = new MemberId("mem_112345678901234567");
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_expense_service_test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    @MockBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private ExpenseService expenses;

    @Autowired
    private GroupRepository groups;

    @Autowired
    private JdbcTemplate jdbc;

    private GroupId groupId;
    private String adminSubject;

    @BeforeEach
    void prepareGroupAndMapping() {
        groupId = new GroupId("grp_" + UUID.randomUUID().toString().replace("-", ""));
        Group group = Group.create(groupId, "Flat", "INR", ADMIN, NOW);
        group.invite(ADMIN, MEMBER, GroupRole.MEMBER, NOW);
        group.activate(MEMBER, MEMBER, NOW);
        groups.saveNew(group);
        adminSubject = "aps_admin_" + UUID.randomUUID().toString().replace("-", "");
        insertMapping(adminSubject, ADMIN);
    }

    @Test
    void two_concurrent_identical_commands_create_one_expense() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<ExpenseView> task = () -> {
            barrier.await();
            return expenses.create(adminSubject, groupId.value(), "concurrent-key", request(100));
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ExpenseView> first = pool.submit(task);
            Future<ExpenseView> second = pool.submit(task);
            assertThat(first.get().id()).isEqualTo(second.get().id());
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM expense WHERE group_id = ?", Long.class, groupId.value()))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void a_same_key_different_payload_is_rejected() {
        expenses.create(adminSubject, groupId.value(), "reuse-key", request(100));

        assertThatThrownBy(() ->
                expenses.create(adminSubject, groupId.value(), "reuse-key", request(200)))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM expense WHERE group_id = ?", Long.class, groupId.value()))
                .isEqualTo(1);
    }

    @Test
    void a_key_cannot_be_replayed_into_another_group() {
        expenses.create(adminSubject, groupId.value(), "cross-key", request(100));

        GroupId other = new GroupId("grp_" + UUID.randomUUID().toString().replace("-", ""));
        Group group = Group.create(other, "Other", "INR", ADMIN, NOW);
        groups.saveNew(group);

        assertThatThrownBy(() ->
                expenses.create(adminSubject, other.value(), "cross-key", request(100)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    private CreateExpenseRequest request(long total) {
        return new CreateExpenseRequest(
                "Dinner", total, "INR", SplitMethod.EQUAL,
                List.of(new CreateExpenseRequest.PayerRequest(ADMIN.value(), total)),
                List.of(ADMIN.value(), MEMBER.value()),
                null, null, NOW);
    }

    private void insertMapping(String subject, MemberId member) {
        jdbc.update("""
                INSERT INTO app_subject_member_mapping
                    (app_subject, member_id, lifecycle_state, mapping_version, source_event_id, updated_at)
                VALUES (?, ?, 'ACTIVE', 1, ?, now())
                """, subject, member.value(), UUID.randomUUID().toString());
    }
}
