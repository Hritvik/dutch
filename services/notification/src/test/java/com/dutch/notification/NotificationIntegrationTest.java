package com.dutch.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dutch.notification.delivery.PushSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(NotificationIntegrationTest.PushConfiguration.class)
class NotificationIntegrationTest {

    private static final String ADMIN_SUBJECT = "aps_admin_0123456789012345678901";
    private static final String MEMBER_SUBJECT = "aps_member_012345678901234567890";
    private static final String ADMIN = "mem_012345678901234567";
    private static final String MEMBER = "mem_112345678901234567";
    private static final String DEVICE = "dev_012345678901234567";

    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("notification_it");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("dutch.notification.service-token", () -> "test-service-token");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RecordingPushSender push;

    @MockBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM notification_delivery");
        jdbc.update("DELETE FROM notification_device");
        jdbc.update("DELETE FROM notification_preference");
        jdbc.update("DELETE FROM inbox_event");
        jdbc.update("DELETE FROM app_subject_member_mapping");
        jdbc.update("""
                INSERT INTO app_subject_member_mapping
                    (app_subject, member_id, lifecycle_state, mapping_version, source_event_id, updated_at)
                VALUES (?, ?, 'ACTIVE', 1, ?, now()), (?, ?, 'ACTIVE', 1, ?, now())
                """, ADMIN_SUBJECT, ADMIN, UUID.randomUUID(), MEMBER_SUBJECT, MEMBER,
                UUID.randomUUID());
        push.sent.clear();
    }

    @Test
    void registers_devices_and_preferences_for_the_authenticated_member() throws Exception {
        registerDevice(ADMIN_SUBJECT, DEVICE);
        registerDevice(ADMIN_SUBJECT, "dev_112345678901234567");

        mockMvc.perform(get("/api/v1/notifications/devices").with(as(ADMIN_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(put("/api/v1/notifications/preferences").with(as(ADMIN_SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityPush\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activityPush").value(false))
                .andExpect(jsonPath("$.data.settlementPush").value(true));

        mockMvc.perform(delete("/api/v1/notifications/devices/" + DEVICE).with(as(ADMIN_SUBJECT)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                "SELECT active FROM notification_device WHERE device_id = ?", Boolean.class, DEVICE))
                .isFalse();
    }

    @Test
    void consumes_an_event_once_and_records_push_per_active_device() throws Exception {
        registerDevice(ADMIN_SUBJECT, DEVICE);
        String eventId = UUID.randomUUID().toString();

        sendEvent(eventId, "ledger.expense.committed.v1");
        sendEvent(eventId, "ledger.expense.committed.v1");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM inbox_event WHERE event_id = ?", Long.class,
                UUID.fromString(eventId))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_delivery WHERE event_id = ?", Long.class,
                UUID.fromString(eventId))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM notification_delivery WHERE event_id = ?", String.class,
                UUID.fromString(eventId))).isEqualTo("SENT");
        assertThat(push.sent).hasSize(1);
    }

    @Test
    void a_disabled_preference_suppresses_delivery_and_no_device_skips_it() throws Exception {
        registerDevice(ADMIN_SUBJECT, DEVICE);
        mockMvc.perform(put("/api/v1/notifications/preferences").with(as(ADMIN_SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityPush\":false}"))
                .andExpect(status().isOk());

        String suppressed = UUID.randomUUID().toString();
        sendEvent(suppressed, "ledger.expense.committed.v1");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM notification_delivery WHERE event_id = ?", String.class,
                UUID.fromString(suppressed))).isEqualTo("SUPPRESSED");
        assertThat(push.sent).isEmpty();

        String skipped = UUID.randomUUID().toString();
        sendEventFor(skipped, "ledger.settlement.committed.v1", List.of(MEMBER));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM notification_delivery WHERE event_id = ?", String.class,
                UUID.fromString(skipped))).isEqualTo("SKIPPED");
    }

    @Test
    void privacy_deletion_removes_devices_preferences_and_deliveries() throws Exception {
        registerDevice(ADMIN_SUBJECT, DEVICE);
        sendEvent(UUID.randomUUID().toString(), "ledger.expense.committed.v1");

        mockMvc.perform(delete("/api/v1/notifications/privacy").with(as(ADMIN_SUBJECT)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_device", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_preference", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_delivery", Long.class))
                .isZero();
    }

    @Test
    void an_anonymization_event_removes_notification_data_and_is_deduped() throws Exception {
        registerDevice(ADMIN_SUBJECT, DEVICE);
        String eventId = UUID.randomUUID().toString();
        String envelope = new ObjectMapper().writeValueAsString(Map.of(
                "eventId", eventId,
                "eventType", "ums.member.anonymized.v1",
                "schemaVersion", 1,
                "producer", "ums",
                "aggregate", Map.of("type", "member", "id", ADMIN, "version", 1),
                "occurredAt", "2026-11-02T00:00:00Z",
                "payload", Map.of("memberId", ADMIN)));

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/internal/events")
                            .header("X-Service-Token", "test-service-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(envelope))
                    .andExpect(status().isNoContent());
        }

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_device WHERE member_id = ?", Long.class, ADMIN))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM inbox_event WHERE event_id = ?", Long.class,
                UUID.fromString(eventId))).isEqualTo(1);
    }

    @Test
    void an_internal_event_requires_the_service_token() throws Exception {
        mockMvc.perform(post("/internal/events")
                        .header("X-Service-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(UUID.randomUUID().toString(),
                                "ledger.expense.committed.v1", List.of(ADMIN))))
                .andExpect(status().isUnauthorized());
    }

    private void registerDevice(String subject, String deviceId) throws Exception {
        mockMvc.perform(post("/api/v1/notifications/devices").with(as(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"%s","platform":"ANDROID","pushToken":"push-token-123456"}
                                """.formatted(deviceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value(deviceId));
    }

    private void sendEvent(String eventId, String eventType) throws Exception {
        sendEventFor(eventId, eventType, List.of(ADMIN));
    }

    private void sendEventFor(String eventId, String eventType, List<String> recipients)
            throws Exception {
        mockMvc.perform(post("/internal/events")
                        .header("X-Service-Token", "test-service-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(eventId, eventType, recipients)))
                .andExpect(status().isNoContent());
    }

    private String eventJson(String eventId, String eventType, List<String> recipients)
            throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of(
                "eventId", eventId,
                "eventType", eventType,
                "schemaVersion", 1,
                "producer", "ledger",
                "aggregate", Map.of("type", "expense", "id", "exp_012345678901234567",
                        "version", 1),
                "occurredAt", "2026-11-02T00:00:00Z",
                "payload", Map.of(
                        "recipientMemberIds", recipients,
                        "amountMinorUnits", 100,
                        "currency", "INR")));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject));
    }

    @TestConfiguration
    static class PushConfiguration {
        @Bean
        @Primary
        RecordingPushSender recordingPushSender() {
            return new RecordingPushSender();
        }
    }

    static class RecordingPushSender implements PushSender {
        private final List<String> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(String pushToken, String title, String body) {
            sent.add(title);
        }
    }
}
