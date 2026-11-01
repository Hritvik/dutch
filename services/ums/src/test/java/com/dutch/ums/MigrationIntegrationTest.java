package com.dutch.ums;

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
            .withDatabaseName("ums_test");

    @Test
    void migratesAndCleansAnIndependentUmsDatabase() throws Exception {
        var flyway = Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
        try (var connection = database.createConnection("")) {
            assertThat(connection.getMetaData().getTables(null, null, "outbox_event", null).next()).isTrue();
            assertThat(connection.getMetaData().getTables(null, null, "inbox_event", null).next()).isTrue();
            assertThat(connection.getMetaData().getTables(null, null, "member_profile", null).next()).isTrue();
        }

        flyway.clean();
        try (var connection = database.createConnection("")) {
            assertThat(connection.getMetaData().getTables(null, null, "outbox_event", null).next()).isFalse();
        }
    }

    @Test
    void upgradesAV2DatabaseByDiscardingTheLegacyPrototypeTables() {
        var flyway = Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        assertThat(Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .target("2").load().migrate().migrationsExecuted).isEqualTo(2);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        jdbc.update("INSERT INTO member_profile(member_id, identity_id, lifecycle_state, mapping_version,"
                + " created_at, updated_at) VALUES (gen_random_uuid(), gen_random_uuid(), 'ACTIVE', 1, now(), now())");

        assertThat(Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_profile", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'member_profile' AND column_name = 'identity_id'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'member_profile' AND column_name = 'app_subject'
                """, Integer.class)).isEqualTo(1);
    }
}
