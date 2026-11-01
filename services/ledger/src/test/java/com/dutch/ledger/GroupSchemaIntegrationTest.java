package com.dutch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class GroupSchemaIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_group_test");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .load()
                .migrate();
    }

    @Test
    void commits_a_group_with_an_active_admin() throws Exception {
        try (Connection connection = database.createConnection("")) {
            connection.setAutoCommit(false);
            insertGroup(connection, "grp_012345678901234567");
            insertMembership(connection, "grp_012345678901234567", "mem_012345678901234567", "ACTIVE", "ADMIN");
            connection.commit();

            assertThat(count(connection, "SELECT count(*) FROM member_group")).isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM group_membership")).isEqualTo(1);
        }
    }

    @Test
    void rejects_a_group_without_an_active_admin_at_commit() throws Exception {
        try (Connection connection = database.createConnection("")) {
            connection.setAutoCommit(false);
            insertGroup(connection, "grp_112345678901234567");

            assertThatThrownBy(connection::commit)
                    .hasMessageContaining("active Admin");
        }
    }

    @Test
    void rejects_demoting_the_last_active_admin_at_commit() throws Exception {
        String groupId = "grp_212345678901234567";
        String memberId = "mem_212345678901234567";
        try (Connection connection = database.createConnection("")) {
            connection.setAutoCommit(false);
            insertGroup(connection, groupId);
            insertMembership(connection, groupId, memberId, "ACTIVE", "ADMIN");
            connection.commit();
        }
        try (Connection connection = database.createConnection("")) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                    "UPDATE group_membership SET role = 'MEMBER' WHERE group_id = ? AND member_id = ?")) {
                statement.setString(1, groupId);
                statement.setString(2, memberId);
                statement.executeUpdate();
            }

            assertThatThrownBy(connection::commit)
                    .hasMessageContaining("active Admin");
        }
    }

    private static void insertGroup(Connection connection, String groupId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO member_group (group_id, name, currency, state, aggregate_version, created_at, updated_at)
                VALUES (?, 'Flat', 'INR', 'ACTIVE', 0, now(), now())
                """)) {
            statement.setString(1, groupId);
            statement.executeUpdate();
        }
    }

    private static void insertMembership(Connection connection, String groupId, String memberId,
                                         String state, String role) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO group_membership (group_id, member_id, state, role, joined_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """)) {
            statement.setString(1, groupId);
            statement.setString(2, memberId);
            statement.setString(3, state);
            statement.setString(4, role);
            statement.executeUpdate();
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
