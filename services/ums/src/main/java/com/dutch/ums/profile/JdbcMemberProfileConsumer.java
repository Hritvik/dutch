package com.dutch.ums.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Idempotent UMS consumer for the IAM onboarding-ready fact. */
@Service
public class JdbcMemberProfileConsumer {
    static final String INPUT_TYPE = "iam.app-subject.onboarding-ready.v1";
    static final String OUTPUT_TYPE = "dutch.member-profile.mapping-changed.v1";
    private static final String CHECKPOINT = "ums-onboarding";

    private final JdbcTemplate jdbc;
    private final MemberProfileRepository profiles;
    private final ObjectMapper objectMapper;
    private final SecureRandom random;
    private final String environment;

    @Autowired
    public JdbcMemberProfileConsumer(JdbcTemplate jdbc,
            @Value("${dutch.environment:development}") String environment) {
        this(jdbc, new JdbcMemberProfileRepository(jdbc), new ObjectMapper(), new SecureRandom(), environment);
    }

    JdbcMemberProfileConsumer(JdbcTemplate jdbc, MemberProfileRepository profiles, ObjectMapper objectMapper,
                              SecureRandom random, String environment) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.profiles = Objects.requireNonNull(profiles);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.random = Objects.requireNonNull(random);
        this.environment = Objects.requireNonNull(environment);
    }

    @Transactional
    public MemberMappingChanged consume(OnboardingReadyEvent event) {
        Objects.requireNonNull(event, "event");
        var fingerprint = fingerprint(event);
        int accepted = jdbc.update("""
                INSERT INTO inbox_event(producer, event_id, event_type, payload_fingerprint, processed_at)
                VALUES ('iam', ?, ?, ?, ?) ON CONFLICT (producer, event_id) DO NOTHING
                """, event.eventId(), INPUT_TYPE, fingerprint, Timestamp.from(event.occurredAt()));
        if (accepted == 0) {
            var stored = jdbc.queryForObject("""
                    SELECT payload_fingerprint FROM inbox_event WHERE producer='iam' AND event_id=?
                    """, String.class, event.eventId());
            if (!fingerprint.equals(stored)) {
                quarantine(event, "PAYLOAD_CONFLICT");
                throw new ConflictingOnboardingEventException();
            }
            return existing(event);
        }

        var existing = profiles.findByAppSubject(event.appRegistrationId(), event.appSubject());
        if (existing.isPresent()) {
            return mapping(existing.get());
        }

        var memberId = newMemberId();
        int inserted = jdbc.update("""
                INSERT INTO member_profile(member_id, app_registration_id, app_subject, display_name,
                    profile_state, lifecycle_state, mapping_version, created_at, updated_at)
                VALUES (?, ?, ?, NULL, 'PROFILE_REQUIRED', 'ACTIVE', 1, ?, ?)
                ON CONFLICT (app_registration_id, app_subject) DO NOTHING
                """, memberId, event.appRegistrationId(), event.appSubject(),
                Timestamp.from(event.occurredAt()), Timestamp.from(event.occurredAt()));
        var profile = profiles.findByAppSubject(event.appRegistrationId(), event.appSubject())
                .orElseThrow(() -> new IllegalStateException("onboarding profile unavailable"));
        if (inserted == 0) {
            return mapping(profile);
        }

        var eventId = newEventId();
        jdbc.update("""
                INSERT INTO outbox_event(event_id, event_type, schema_version, producer, environment,
                    aggregate_type, aggregate_id, aggregate_version, correlation_id, causation_id,
                    destination_app_registration_id, payload, occurred_at)
                VALUES (?, ?, 1, 'dutch-ums', ?, 'MEMBER_PROFILE', ?, 1, ?, ?, ?, CAST(? AS jsonb), ?)
                """, eventId, OUTPUT_TYPE, environment, profile.memberId(), event.correlationId(),
                event.causationId(), event.appRegistrationId(), payload(profile),
                Timestamp.from(event.occurredAt()));
        jdbc.update("""
                INSERT INTO inbox_checkpoint(consumer, last_event_id, updated_at) VALUES (?, ?, ?)
                ON CONFLICT (consumer) DO UPDATE SET last_event_id = EXCLUDED.last_event_id,
                    updated_at = EXCLUDED.updated_at
                """, CHECKPOINT, event.eventId(), Timestamp.from(event.occurredAt()));
        return mapping(profile);
    }

    private void quarantine(OnboardingReadyEvent event, String reasonCode) {
        var dataSource = jdbc.getDataSource();
        if (dataSource == null) return;
        // A dedicated autocommit connection records the poison event even though the consume transaction rolls back.
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO inbox_quarantine(producer, event_id, event_type, reason_code, detected_at)
                     VALUES ('iam', ?, ?, ?, ?) ON CONFLICT (producer, event_id) DO NOTHING
                     """)) {
            if (!connection.getAutoCommit()) connection.setAutoCommit(true);
            statement.setString(1, event.eventId());
            statement.setString(2, INPUT_TYPE);
            statement.setString(3, reasonCode);
            statement.setTimestamp(4, Timestamp.from(event.occurredAt()));
            statement.executeUpdate();
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("onboarding quarantine unavailable", exception);
        }
    }

    private MemberMappingChanged existing(OnboardingReadyEvent event) {
        return profiles.findByAppSubject(event.appRegistrationId(), event.appSubject())
                .map(this::mapping)
                .orElseThrow(() -> new IllegalStateException("consumed event has no member mapping"));
    }

    private MemberMappingChanged mapping(MemberProfile profile) {
        var eventId = jdbc.query("""
                SELECT event_id FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ? ORDER BY occurred_at DESC LIMIT 1
                """, (rs, row) -> rs.getString("event_id"), profile.memberId(), OUTPUT_TYPE)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("mapping event missing"));
        var event = jdbc.query("""
                SELECT correlation_id, causation_id, occurred_at FROM outbox_event WHERE event_id = ?
                """, (rs, row) -> new Object[] {rs.getString("correlation_id"), rs.getString("causation_id"),
                        rs.getTimestamp("occurred_at").toInstant()}, eventId).getFirst();
        return new MemberMappingChanged(eventId, profile.appRegistrationId(), profile.appSubject(),
                profile.memberId(), profile.mappingVersion(), profile.profileState(), profile.lifecycleState(),
                (java.time.Instant) event[2], (String) event[0], (String) event[1]);
    }

    private String payload(MemberProfile profile) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("appRegistrationId", profile.appRegistrationId());
        payload.put("appSubject", profile.appSubject());
        payload.put("memberId", profile.memberId());
        payload.put("mappingVersion", profile.mappingVersion());
        payload.put("profileState", profile.profileState().name());
        payload.put("lifecycleState", profile.lifecycleState().name());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("mapping payload unavailable", exception);
        }
    }

    private String fingerprint(OnboardingReadyEvent event) {
        var canonical = new LinkedHashMap<String, Object>();
        canonical.put("appRegistrationId", event.appRegistrationId());
        canonical.put("appSubject", event.appSubject());
        canonical.put("activationId", event.activationId());
        canonical.put("activationState", event.activationState());
        canonical.put("activationVersion", event.activationVersion());
        canonical.put("onboardingReason", event.onboardingReason());
        canonical.put("onboardingVersion", event.onboardingVersion());
        canonical.put("requiredProfileState", event.requiredProfileState());
        try {
            var json = objectMapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (Exception exception) {
            throw new IllegalStateException("onboarding fingerprint unavailable", exception);
        }
    }

    private String newMemberId() {
        var bytes = new byte[18];
        random.nextBytes(bytes);
        return "mem_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String newEventId() {
        var bytes = new byte[18];
        random.nextBytes(bytes);
        return "evt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
