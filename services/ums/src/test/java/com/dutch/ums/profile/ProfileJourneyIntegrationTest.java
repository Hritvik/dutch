package com.dutch.ums.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Required -> provisioning -> ready, the 409/200 split, correlation and privacy negatives. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ProfileJourneyIntegrationTest {
    private static final String SUBJECT = "aps_0123456789012345678901ab";
    private static final String CORRELATION = "cor_0123456789012345";

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    @TestConfiguration
    static class TestAuthority {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none")
                    .subject(SUBJECT).build();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcMemberProfileConsumer consumer;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @BeforeEach
    void reset() {
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    private static OnboardingReadyEvent onboarding(String subject) {
        return new OnboardingReadyEvent("evt_012345678901234567", "dutch-development", subject,
                "act_012345678901234567", "ACTIVE", 2, "FIRST_ACTIVATION", 1,
                "PROFILE_REQUIRED", Instant.parse("2026-10-31T00:00:00Z"), CORRELATION, null);
    }

    @Test
    void beforeConsumptionTheJourneyReportsProfileRequired() throws Exception {
        mvc.perform(get("/api/v1/me/profile-state").header("X-Correlation-Id", CORRELATION)
                        .with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PROFILE_REQUIRED"));

        mvc.perform(get("/api/v1/me").header("X-Correlation-Id", CORRELATION)
                        .with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("profile_required"))
                .andExpect(jsonPath("$.error.message").value("Complete your profile to continue."))
                .andExpect(jsonPath("$.error.correlationId").value(CORRELATION));
    }

    @Test
    void aCommittedProfileFlipsTheSplitToReadyWithOnlyDutchOwnedFields() throws Exception {
        consumer.consume(onboarding(SUBJECT));
        jdbc.update("UPDATE member_profile SET profile_state = 'PROFILE_PROVISIONING' WHERE app_subject = ?", SUBJECT);
        mvc.perform(get("/api/v1/me/profile-state").with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PROFILE_PROVISIONING"));

        // The Dutch-owned product completes the profile; UMS only supplies the readiness fact.
        jdbc.update("UPDATE member_profile SET display_name = 'Ada', profile_state = 'PROFILE_READY'"
                + " WHERE app_subject = ?", SUBJECT);
        mvc.perform(get("/api/v1/me/profile-state").with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PROFILE_READY"));
        var response = mvc.perform(get("/api/v1/me").header("X-Correlation-Id", CORRELATION)
                        .with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", CORRELATION))
                .andExpect(jsonPath("$.data.memberId").exists())
                .andExpect(jsonPath("$.data.displayName").value("Ada"))
                .andExpect(jsonPath("$.data.profileState").value("PROFILE_READY"))
                .andExpect(jsonPath("$.data.lifecycleState").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(SUBJECT, "aps_", "identity", "token", "credential",
                "password", "email");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_profile", Integer.class)).isEqualTo(1);
    }

    @Test
    void suspendedOrDepartedProfilesAreNeverReportedReady() throws Exception {
        consumer.consume(onboarding(SUBJECT));
        jdbc.update("UPDATE member_profile SET lifecycle_state = 'SUSPENDED'");

        mvc.perform(get("/api/v1/me/profile-state").with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PROFILE_REQUIRED"));
        mvc.perform(get("/api/v1/me").with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("profile_required"));
    }

    @Test
    void anInvalidCorrelationIsReplacedAndEchoedOnRejections() throws Exception {
        mvc.perform(get("/api/v1/me").header("X-Correlation-Id", "bad value with spaces")
                        .with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-Correlation-Id",
                        org.hamcrest.Matchers.matchesPattern("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")));
    }
}
