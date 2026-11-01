package com.dutch.iam.identity;

import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcIdentityLifecycleRepository implements IdentityStore, IdentityOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public JdbcIdentityLifecycleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void save(Identity identity, ProtectedVerifiedIdentifier identifier) {
        jdbc.update("INSERT INTO identity(identity_id, security_state, created_at, updated_at) VALUES (?, ?, ?, ?)",
                identity.identityId(), identity.securityState(), identity.createdAt(), identity.updatedAt());
        jdbc.update("INSERT INTO verified_identifier(identifier_id, identity_id, identifier_type, lookup_digest, ciphertext, key_version, identifier_version, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'VERIFIED', ?)",
                identifier.identifierId(), identifier.identityId(), identifier.type().name(), identifier.lookupDigest(),
                identifier.ciphertext(), identifier.keyVersion(), identifier.identifierVersion(), identifier.createdAt());
    }

    @Override
    public void append(IdentityVerifiedEvent event) {
        String payload = serialize(event);
        jdbc.update("INSERT INTO outbox_event(event_id, event_type, aggregate_type, aggregate_id, aggregate_version, payload, occurred_at) VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB), ?)",
                event.eventId(), "iam.identity.verified.v1", "Identity", event.identityId(),
                event.identityVersion(), payload, event.occurredAt());
    }

    private String serialize(IdentityVerifiedEvent event) {
        try {
            return json.writeValueAsString(java.util.Map.of(
                    "eventId", event.eventId().toString(),
                    "eventType", "iam.identity.verified.v1",
                    "schemaVersion", 1,
                    "occurredAt", event.occurredAt().toString(),
                    "producer", "iam",
                    "aggregate", java.util.Map.of("type", "identity", "id", event.identityId().toString(), "version", event.identityVersion()),
                    "correlationId", event.correlationId().toString(),
                    "causationId", event.causationId().toString(),
                    "payload", java.util.Map.of("identityId", event.identityId().toString(), "securityState", "PROFILE_REQUIRED")));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("PII-free identity event serialization failed.", exception);
        }
    }
}
