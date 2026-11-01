package com.dutch.iam.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityProvisioningServiceTest {

    @Test
    void protects_verified_identifier_and_writes_pii_free_event_in_the_same_unit_of_work() {
        var stored = new ArrayList<ProtectedVerifiedIdentifier>();
        var events = new ArrayList<IdentityVerifiedEvent>();
        var service = new IdentityProvisioningService(
                (identity, identifier) -> stored.add(identifier),
                events::add,
                new IdentifierProtector(
                        "lookup-key-with-at-least-32-bytes".getBytes(StandardCharsets.UTF_8),
                        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8),
                        1),
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC));

        UUID identityId = UUID.fromString("2bf1368d-5d79-49ce-b237-997849fd5cd1");
        service.provisionVerified(identityId, IdentifierType.EMAIL, "member@example.test");

        assertThat(stored).singleElement().satisfies(identifier -> {
            assertThat(new String(identifier.ciphertext(), StandardCharsets.ISO_8859_1)).doesNotContain("member@example.test");
            assertThat(new String(identifier.lookupDigest(), StandardCharsets.ISO_8859_1)).doesNotContain("member@example.test");
        });
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.identityId()).isEqualTo(identityId);
            assertThat(event.identityVersion()).isEqualTo(1);
            assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-10T00:00:00Z"));
            assertThat(event.eventId()).isNotNull();
            assertThat(event.correlationId()).isNotNull();
            assertThat(event.causationId()).isNotNull();
        });
        assertThat(events.toString()).doesNotContain("member@example.test");
    }
}
