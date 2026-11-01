package com.dutch.iam.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
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
class IdentityPersistenceIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("iam_identity_test");
    static JdbcTemplate jdbc;
    static TransactionTemplate transaction;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()).load().migrate();
        var dataSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void identity_identifier_and_outbox_commit_and_roll_back_as_one_transaction() {
        var repository = new JdbcIdentityLifecycleRepository(jdbc);
        var service = new IdentityProvisioningService(repository, repository,
                new IdentifierProtector("lookup-key-with-at-least-32-bytes".getBytes(StandardCharsets.UTF_8),
                        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), 1), Clock.systemUTC());
        UUID identityId = UUID.randomUUID();

        transaction.executeWithoutResult(ignored -> service.provisionVerified(identityId, IdentifierType.EMAIL, "member@example.test"));
        assertThat(count("identity")).isEqualTo(1);
        assertThat(count("verified_identifier")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);

        assertThatThrownBy(() -> transaction.executeWithoutResult(
                ignored -> service.provisionVerified(identityId, IdentifierType.EMAIL, "different@example.test")))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("identity")).isEqualTo(1);
        assertThat(count("verified_identifier")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    private static int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
