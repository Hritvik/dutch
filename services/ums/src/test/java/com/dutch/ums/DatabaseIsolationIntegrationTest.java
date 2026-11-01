package com.dutch.ums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseIsolationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void theUmsRoleCannotConnectToTheIamDatabase() throws Exception {
        try (var connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(),
                database.getPassword()); var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE ums_app LOGIN PASSWORD 'ums-secret'");
            statement.execute("CREATE DATABASE dutch_iam");
            statement.execute("REVOKE CONNECT ON DATABASE dutch_iam FROM PUBLIC");
        }
        var iamUrl = database.getJdbcUrl().replaceAll("/[^/]+$", "/dutch_iam");

        assertThatThrownBy(() -> DriverManager.getConnection(iamUrl, "ums_app", "ums-secret"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
        assertThat(iamUrl).isNotEqualTo(database.getJdbcUrl());
    }
}
