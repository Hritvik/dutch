package com.dutch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.dutch.ledger.expense.JdbcIdempotencyStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcIdempotencyStoreIntegrationTest {

    private static final String ACTOR = "mem_012345678901234567";

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_idempotency_test");

    private static JdbcIdempotencyStore store;

    @BeforeAll
    static void prepare() {
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        store = new JdbcIdempotencyStore(jdbc);
    }

    @Test
    void reserves_completes_and_replays_an_outcome() {
        assertThat(store.reserve(ACTOR, "create-expense", "key-1", "hash-a")).isTrue();
        assertThat(store.reserve(ACTOR, "create-expense", "key-1", "hash-a")).isFalse();

        assertThat(store.find(ACTOR, "create-expense", "key-1").orElseThrow().requestHash())
                .isEqualTo("hash-a");

        store.complete(ACTOR, "create-expense", "key-1", "{\"id\":\"exp_012345678901234567\"}");

        assertThat(store.find(ACTOR, "create-expense", "key-1").orElseThrow().responseJson())
                .contains("exp_012345678901234567");
    }

    @Test
    void a_different_key_is_independent() {
        assertThat(store.reserve(ACTOR, "create-expense", "key-2", "hash-b")).isTrue();
        assertThat(store.find(ACTOR, "create-expense", "missing")).isEmpty();
    }
}
