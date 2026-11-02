package com.dutch.ums.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.matchesPattern;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Required -> provisioning -> ready, the 409/200 split, lifecycle negatives, correlation and
 * privacy checks over the real security chain, correlation filter, controller and PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ProfileJourneyIntegrationTest {
    private static final String SUBJECT = "aps_0123456789012345678901ab";
    private static final String CORRELATION = "cor_0123456789012345";
    private static final String BEARER = "Bearer fixture-token";

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    /** Test-only decoder: the real resource-server filter still authenticates the bearer token. */
    @TestConfiguration
    static class TestAuthority {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none").subject(SUBJECT).build();
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

    private void completeProfile(String name) {
        jdbc.update("UPDATE member_profile SET display_name = ?, profile_state = 'PROFILE_READY'"
                + " WHERE app_subject = ?", name, SUBJECT);
    }

    private MvcResult me(String correlation) throws Exception {
        return mvc.perform(get("/api/v1/me")
                        .header("Authorization", BEARER)
                        .header("X-Correlation-Id", correlation))
                .andReturn();
    }

    @Test
    void beforeConsumptionTheJourneyReportsProfileRequired() throws Exception {
        mvc.perform(get("/api/v1/me/profile-state").header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PROFILE_REQUIRED"))
                .andExpect(jsonPath("$.data").value(aMapWithSize(1)));

        var result = me(CORRELATION);
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        var body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"profile_required\"")
                .contains("Complete your profile to continue.")
                .contains(CORRELATION);
        assertNoPersonalData(body);
    }

    @Test
    void aCommittedProfileAdvancesToReadyWithOnlyDutchOwnedFields() throws Exception {
        consumer.consume(onboarding(SUBJECT));
        assertThat(state()).isEqualTo("PROFILE_REQUIRED");

        jdbc.update("UPDATE member_profile SET profile_state = 'PROFILE_PROVISIONING' WHERE app_subject = ?", SUBJECT);
        assertThat(state()).isEqualTo("PROFILE_PROVISIONING");

        completeProfile("Ada");
        assertThat(state()).isEqualTo("PROFILE_READY");

        var result = me(CORRELATION);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        mvc.perform(get("/api/v1/me").header("Authorization", BEARER))
                .andExpect(header().string("X-Correlation-Id", org.hamcrest.Matchers.notNullValue()))
                .andExpect(jsonPath("$.data").value(aMapWithSize(4)))
                .andExpect(jsonPath("$.data.memberId").value(matchesPattern("^mem_[A-Za-z0-9_-]{16,128}$")))
                .andExpect(jsonPath("$.data.displayName").value("Ada"))
                .andExpect(jsonPath("$.data.profileState").value("PROFILE_READY"))
                .andExpect(jsonPath("$.data.lifecycleState").value("ACTIVE"));
        assertNoPersonalData(result.getResponse().getContentAsString());
    }

    @Test
    void suspendedAndDepartedMembersAreNeverReportedReady() throws Exception {
        consumer.consume(onboarding(SUBJECT));
        completeProfile("Ada");
        assertThat(state()).isEqualTo("PROFILE_READY");

        for (String lifecycle : new String[] {"SUSPENDED", "DEPARTED"}) {
            jdbc.update("UPDATE member_profile SET lifecycle_state = ? WHERE app_subject = ?", lifecycle, SUBJECT);
            assertThat(state()).isEqualTo("PROFILE_REQUIRED");
            var result = me(CORRELATION);
            assertThat(result.getResponse().getStatus()).as(lifecycle).isEqualTo(409);
            assertThat(result.getResponse().getContentAsString()).contains("profile_required");
        }
    }

    @Test
    void aMissingBearerTokenIsRejectedWithThePinnedEnvelope() throws Exception {
        mvc.perform(get("/api/v1/me").header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthorized"))
                .andExpect(jsonPath("$.error.message").value("Authentication is required"))
                .andExpect(jsonPath("$.error.correlationId").value(CORRELATION));
    }

    @Test
    void anInvalidCorrelationIsReplacedAndEchoedOnRejections() throws Exception {
        var result = me("bad value with spaces");

        var echoed = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(echoed).isNotEqualTo("bad value with spaces").matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains(echoed);
    }

    private String state() throws Exception {
        var result = mvc.perform(get("/api/v1/me/profile-state").header("Authorization", BEARER))
                .andExpect(status().isOk()).andReturn();
        var body = result.getResponse().getContentAsString();
        assertNoPersonalData(body);
        return com.jayway.jsonpath.JsonPath.read(body, "$.data.state");
    }

    private static void assertNoPersonalData(String body) {
        assertThat(body.toLowerCase())
                .doesNotContain("aps_", "identity", "token", "credential", "password", "email",
                        "phone", "secret");
    }
}
