package com.dutch.ums.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcMemberProfileConsumerIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcMemberProfileConsumer consumer;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        consumer = new JdbcMemberProfileConsumer(jdbc, "development");
    }

    private static OnboardingReadyEvent event(String eventId, String subject) {
        return new OnboardingReadyEvent(eventId, "dutch-development", subject,
                "act_012345678901234567", "ACTIVE", 2, "FIRST_ACTIVATION", 1,
                "PROFILE_REQUIRED", Instant.parse("2026-10-31T00:00:00Z"), "cor_0123456789012345", null);
    }

    @Test
    void duplicateDeliveryAndDistinctEventsForOneSubjectCreateOneProfileAndMappingEvent() {
        var event = event("evt_012345678901234567", "aps_0123456789012345678901ab");

        var first = consumer.consume(event);
        var duplicate = consumer.consume(event);
        var replayWithNewEventId = consumer.consume(event("evt_212345678901234567",
                "aps_0123456789012345678901ab"));

        assertThat(duplicate.memberId()).isEqualTo(first.memberId());
        assertThat(replayWithNewEventId.memberId()).isEqualTo(first.memberId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_profile", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_event", Integer.class)).isEqualTo(2);
        String payload = jdbc.queryForObject("SELECT payload::text FROM outbox_event", String.class);
        assertThat(payload).contains("dutch-development", "aps_0123456789012345678901ab", first.memberId(),
                "PROFILE_REQUIRED", "ACTIVE");
        assertThat(payload).doesNotContainIgnoringCase("identity", "email", "phone", "token", "credential");
    }

    @Test
    void theEmittedEventCarriesThePinnedEnvelopeAndPayload() {
        var mapping = consumer.consume(event("evt_312345678901234567", "aps_1123456789012345678901ab"));

        var row = jdbc.queryForMap("SELECT * FROM outbox_event WHERE event_id = ?", mapping.eventId());
        assertThat(row.get("event_type")).isEqualTo("dutch.member-profile.mapping-changed.v1");
        assertThat(row.get("producer")).isEqualTo("dutch-ums");
        assertThat(row.get("aggregate_type")).isEqualTo("MEMBER_PROFILE");
        assertThat(row.get("aggregate_id")).isEqualTo(mapping.memberId());
        assertThat(row.get("destination_app_registration_id")).isEqualTo("dutch-development");
        assertThat(row.get("correlation_id")).isEqualTo("cor_0123456789012345");
        assertThat(row.get("causation_id")).isNull();
    }

    @Test
    void reusingAnEventIdWithDifferentPayloadFailsClosed() {
        consumer.consume(event("evt_412345678901234567", "aps_2123456789012345678901ab"));

        assertThatThrownBy(() -> consumer.consume(event("evt_412345678901234567",
                "aps_3123456789012345678901ab")))
                .isInstanceOf(ConflictingOnboardingEventException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_profile", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void theEmittedEventValidatesAgainstThePinnedDutchSchema() throws Exception {
        var mapping = consumer.consume(event("evt_512345678901234567", "aps_4123456789012345678901ab"));
        var row = jdbc.queryForMap("SELECT * FROM outbox_event WHERE event_id = ?", mapping.eventId());
        var mapper = new ObjectMapper();
        var payload = mapper.readTree(row.get("payload").toString());
        var aggregate = mapper.createObjectNode()
                .put("type", (String) row.get("aggregate_type"))
                .put("id", (String) row.get("aggregate_id"))
                .put("version", ((Number) row.get("aggregate_version")).longValue());
        var destination = mapper.createObjectNode()
                .put("appRegistrationId", (String) row.get("destination_app_registration_id"));
        var envelope = mapper.createObjectNode()
                .put("eventId", (String) row.get("event_id"))
                .put("eventType", (String) row.get("event_type"))
                .put("schemaVersion", ((Number) row.get("schema_version")).longValue())
                .put("occurredAt", ((java.sql.Timestamp) row.get("occurred_at")).toInstant().toString())
                .put("producer", (String) row.get("producer"))
                .put("environment", (String) row.get("environment"));
        envelope.set("aggregate", aggregate);
        envelope.put("correlationId", (String) row.get("correlation_id"));
        envelope.putNull("causationId");
        envelope.set("destination", destination);
        envelope.set("payload", payload);

        var schemaPath = Path.of("../../contracts/events/dutch/json-schema/"
                + "dutch.member-profile.mapping-changed.v1.schema.json").toAbsolutePath().normalize();
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaPath.toUri());

        assertThat(schema.validate(envelope)).isEmpty();
    }
}
