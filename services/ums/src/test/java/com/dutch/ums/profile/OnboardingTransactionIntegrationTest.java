package com.dutch.ums.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "dutch.environment=invalid",
        "spring.autoconfigure.exclude="
})
class OnboardingTransactionIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "https://iam.invalid");
    }

    @Autowired JdbcMemberProfileConsumer consumer;
    @Autowired JdbcTemplate jdbc;
    @MockBean JwtDecoder jwtDecoder;

    @Test
    void anOutboxFailureRollsBackTheInboxAndProfileThroughTheConsumerTransaction() {
        var event = new OnboardingReadyEvent("evt_012345678901234567", "dutch-development",
                "aps_0123456789012345678901ab", "act_012345678901234567", "ACTIVE", 2,
                "FIRST_ACTIVATION", 1, "PROFILE_REQUIRED", Instant.parse("2026-10-31T00:00:00Z"),
                "cor_0123456789012345", null);

        assertThatThrownBy(() -> consumer.consume(event)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_event", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_profile", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_checkpoint", Integer.class)).isZero();
    }
}
