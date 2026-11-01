package com.dutch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.ledger.expense.JdbcExpenseRepository;
import com.dutch.ledger.group.Group;
import com.dutch.ledger.group.GroupId;
import com.dutch.ledger.group.GroupRole;
import com.dutch.ledger.group.GroupRuleException;
import com.dutch.ledger.group.JdbcGroupRepository;
import com.dutch.ledger.group.MemberId;
import com.dutch.ledger.settlement.JdbcSettlementRepository;
import com.dutch.ledger.settlement.Settlement;
import com.dutch.ledger.settlement.SettlementAllocation;
import com.dutch.ledger.settlement.SettlementHistory;
import com.dutch.ledger.settlement.SettlementId;
import com.dutch.ledger.settlement.SettlementVoid;
import java.time.Instant;
import java.util.List;
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
class JdbcSettlementRepositoryIntegrationTest {

    private static final SettlementId SETTLEMENT = new SettlementId("stl_012345678901234567");
    private static final MemberId A = new MemberId("mem_012345678901234567");
    private static final MemberId B = new MemberId("mem_112345678901234567");
    private static final GroupId GROUP = new GroupId("grp_012345678901234567");
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_settlement_test");

    private static JdbcTemplate jdbc;
    private static JdbcSettlementRepository settlements;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void prepare() {
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        settlements = new JdbcSettlementRepository(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        JdbcGroupRepository groups = new JdbcGroupRepository(jdbc);
        transactions.executeWithoutResult(status -> {
            Group group = Group.create(GROUP, "Flat", "INR", A, NOW);
            group.invite(A, B, GroupRole.MEMBER, NOW);
            group.activate(B, B, NOW);
            groups.saveNew(group);
        });
    }

    @Test
    void saves_a_multi_scope_settlement_and_voids_it_once() {
        Settlement settlement = settlement(100, List.of(
                new SettlementAllocation("group:grp_012345678901234567:INR", "INR", 60, A, B),
                new SettlementAllocation("direct:mem_000000000000000001:mem_000000000000000002:INR",
                        "INR", 40, A, B)));

        transactions.executeWithoutResult(status -> settlements.save(settlement));
        assertThat(settlements.status(SETTLEMENT)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM settlement_allocation WHERE settlement_id = ?",
                Long.class, SETTLEMENT.value())).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM obligation_entry WHERE settlement_id = ? AND entry_kind = 'SETTLEMENT'",
                Long.class, SETTLEMENT.value())).isEqualTo(2);
        // A stream that also carries settlement entries still reads only its Expenses.
        assertThat(new JdbcExpenseRepository(jdbc).entriesFor(GROUP, "INR")).isEmpty();
        assertThat(new JdbcExpenseRepository(jdbc).projectionFor(GROUP, "INR").balances())
                .containsEntry(A, 60L)
                .containsEntry(B, -60L);
        assertThat(jdbc.queryForObject("""
                SELECT balance_minor_units FROM member_balance_projection
                WHERE stream_id = 'direct:mem_000000000000000001:mem_000000000000000002:INR'
                  AND member_id = ?
                """, Long.class, A.value())).isEqualTo(40L);

        SettlementVoid voided = SettlementHistory.of(settlement).voidIt(A, NOW);
        transactions.executeWithoutResult(status -> settlements.voidSettlement(voided));
        assertThat(settlements.status(SETTLEMENT)).isEqualTo("VOIDED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM settlement_void WHERE settlement_id = ?",
                Long.class, SETTLEMENT.value())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM obligation_entry WHERE settlement_id = ? AND entry_kind = 'SETTLEMENT_VOID'",
                Long.class, SETTLEMENT.value())).isEqualTo(2);
        assertThat(new JdbcExpenseRepository(jdbc).projectionFor(GROUP, "INR").balances()).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(balance_minor_units), 0) FROM member_balance_projection
                WHERE stream_id = 'direct:mem_000000000000000001:mem_000000000000000002:INR'
                """, Long.class)).isZero();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                settlements.voidSettlement(SettlementVoid.of(settlement, A, NOW))))
                .isInstanceOf(GroupRuleException.class);
    }

    @Test
    void the_parent_total_is_immutable_except_for_a_one_way_void() {
        SettlementId immutable = new SettlementId("stl_812345678901234567");
        GroupId otherGroup = new GroupId("grp_812345678901234567");
        transactions.executeWithoutResult(status -> {
            Group group = Group.create(otherGroup, "Other flat", "INR", A, NOW);
            group.invite(A, B, GroupRole.MEMBER, NOW);
            group.activate(B, B, NOW);
            new JdbcGroupRepository(jdbc).saveNew(group);
            settlements.save(new Settlement(immutable, A, B, 100, "INR",
                    List.of(new SettlementAllocation("group:" + otherGroup.value() + ":INR",
                            "INR", 100, A, B)), NOW));
        });

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> jdbc.update(
                "UPDATE settlement SET total_minor_units = 200 WHERE settlement_id = ?",
                immutable.value())))
                .hasStackTraceContaining("one-way void");
    }

    @Test
    void rejects_allocations_that_do_not_sum_to_the_parent_total() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO settlement (settlement_id, payer_member_id, payee_member_id,
                        total_minor_units, currency, status, occurred_at)
                    VALUES ('stl_912345678901234567', ?, ?, 100, 'INR', 'ACTIVE', now())
                    """, A.value(), B.value());
            jdbc.update("""
                    INSERT INTO settlement_allocation (settlement_id, scope_id, currency,
                        minor_units, payer_member_id, payee_member_id)
                    VALUES ('stl_912345678901234567', 'group:grp_012345678901234567:INR', 'INR', 60, ?, ?)
                    """, A.value(), B.value());
        })).hasStackTraceContaining("allocations must equal 100");
    }

    private static Settlement settlement(long total, List<SettlementAllocation> allocations) {
        return new Settlement(SETTLEMENT, A, B, total, "INR", allocations, NOW);
    }
}
