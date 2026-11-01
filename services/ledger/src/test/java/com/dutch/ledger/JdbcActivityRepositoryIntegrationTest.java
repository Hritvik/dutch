package com.dutch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.ledger.activity.ActivityAction;
import com.dutch.ledger.activity.ActivityEntry;
import com.dutch.ledger.activity.JdbcActivityRepository;
import com.dutch.ledger.group.GroupRuleException;
import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcActivityRepositoryIntegrationTest {

    private static final String SCOPE = "group:grp_012345678901234567:INR";
    private static final MemberId ACTOR = new MemberId("mem_012345678901234567");
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_activity_test");

    private static JdbcTemplate jdbc;
    private static JdbcActivityRepository activity;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void prepare() {
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        activity = new JdbcActivityRepository(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @Test
    void appends_and_reads_activity_without_losing_details() {
        ActivityEntry created = new ActivityEntry("act_012345678901234567", SCOPE, ACTOR,
                ActivityAction.EXPENSE_CREATED, "exp_012345678901234567", 1, NOW,
                Map.of("description", "Dinner", "totalMinorUnits", "100", "currency", "INR"));
        ActivityEntry deleted = new ActivityEntry("act_112345678901234567", SCOPE, null,
                ActivityAction.EXPENSE_DELETED, "exp_012345678901234567", 2, NOW.plusSeconds(1),
                Map.of("status", "DELETED"));

        transactions.executeWithoutResult(status -> {
            activity.append(created);
            activity.append(deleted);
        });

        assertThat(activity.forScope(SCOPE)).containsExactly(created, deleted);
        assertThat(activity.forScope(SCOPE).get(1).actor()).isNull();
    }

    @Test
    void rejects_duplicates_and_mutations() {
        ActivityEntry entry = new ActivityEntry("act_212345678901234567", SCOPE, ACTOR,
                ActivityAction.EXPENSE_CREATED, "exp_012345678901234567", 1, NOW,
                Map.of("description", "Dinner"));
        transactions.executeWithoutResult(status -> activity.append(entry));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> activity.append(entry)))
                .isInstanceOf(GroupRuleException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                jdbc.update("UPDATE activity_entry SET target_revision = 9 WHERE activity_id = ?",
                        entry.activityId())))
                .hasStackTraceContaining("immutable");
    }
}
