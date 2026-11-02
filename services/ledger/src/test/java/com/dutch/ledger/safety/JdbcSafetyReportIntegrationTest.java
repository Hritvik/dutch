package com.dutch.ledger.safety;

import static org.assertj.core.api.Assertions.assertThat;

import com.dutch.ledger.group.MemberId;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcSafetyReportIntegrationTest {

    private static final MemberId ADMIN = new MemberId("mem_012345678901234567");

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_safety_test");

    private static SafetyReportService reports;

    @BeforeAll
    static void prepare() {
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        reports = new SafetyReportService(jdbc,
                subject -> Optional.of(ADMIN));
    }

    @Test
    void a_repeated_key_returns_the_same_report_and_a_new_key_creates_another() {
        var request = new SafetyReportService.CreateReportRequest(
                "SPAM", "GROUP", "grp_012345678901234567", "Repeated report");
        var first = reports.report("aps_admin_0123456789012345678901", request, "report-key-1");
        var replay = reports.report("aps_admin_0123456789012345678901", request, "report-key-1");
        var different = reports.report("aps_admin_0123456789012345678901", request, "report-key-2");

        assertThat(replay.get("reportId")).isEqualTo(first.get("reportId"));
        assertThat(different.get("reportId")).isNotEqualTo(first.get("reportId"));
    }
}
