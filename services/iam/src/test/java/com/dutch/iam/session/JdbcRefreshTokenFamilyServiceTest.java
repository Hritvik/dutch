package com.dutch.iam.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class JdbcRefreshTokenFamilyServiceTest {
    private JdbcRefreshTokenFamilyService service;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:refresh;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE refresh_token_family (family_id UUID PRIMARY KEY, identity_id UUID NOT NULL, session_id UUID NOT NULL, current_token_digest BINARY(32) NOT NULL, active BOOLEAN NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, revoked_at TIMESTAMP WITH TIME ZONE)");
        jdbc.execute("CREATE TABLE refresh_token_digest (token_digest BINARY(32) PRIMARY KEY, family_id UUID NOT NULL, consumed_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        service = new JdbcRefreshTokenFamilyService(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void replay_of_a_consumed_refresh_token_durably_revokes_the_family() {
        UUID family = service.create(UUID.randomUUID(), UUID.randomUUID(), "first-refresh-token-value");
        assertThat(service.rotate(family, "first-refresh-token-value", "second-refresh-token-value"))
                .isEqualTo(RefreshResult.ROTATED);

        assertThat(service.rotate(family, "first-refresh-token-value", "third-refresh-token-value"))
                .isEqualTo(RefreshResult.REAUTHENTICATION_REQUIRED);
        assertThat(service.isActive(family)).isFalse();
    }
}
