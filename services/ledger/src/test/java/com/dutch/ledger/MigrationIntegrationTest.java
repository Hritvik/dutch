package com.dutch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class MigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_test");

    @Test
    void migratesAndCleansAnIndependentLedgerDatabase() throws Exception {
        var flyway = Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .cleanDisabled(false)
                .load();

        assertThat(flyway.migrate().migrationsExecuted).isPositive();
        try (var connection = database.createConnection("")) {
            assertThat(connection.getMetaData().getTables(null, null, "outbox_event", null).next()).isTrue();
            assertThat(connection.getMetaData().getTables(null, null, "inbox_event", null).next()).isTrue();
        }

        flyway.clean();
        try (var connection = database.createConnection("")) {
            assertThat(connection.getMetaData().getTables(null, null, "outbox_event", null).next()).isFalse();
        }
    }
}
