package com.dutch.iam;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseIsolationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("iam_test");

    @Test
    void iamRuntimeRoleCannotConnectToTheUmsDatabase() throws Exception {
        try (var connection = database.createConnection(""); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE ums_test");
            statement.execute("REVOKE CONNECT ON DATABASE ums_test FROM PUBLIC");
            statement.execute("CREATE ROLE iam_runtime NOLOGIN");
            statement.execute("CREATE ROLE ums_runtime NOLOGIN");
            statement.execute("GRANT CONNECT ON DATABASE ums_test TO ums_runtime");

            try (var result = statement.executeQuery(
                    "SELECT has_database_privilege('iam_runtime', 'ums_test', 'CONNECT')")) {
                result.next();
                assertThat(result.getBoolean(1)).isFalse();
            }
        }
    }
}
