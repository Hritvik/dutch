package com.dutch.ledger.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityMemberMappingProjectionTest {

    private final IdentityMemberMappingProjection projection = new IdentityMemberMappingProjection();

    @Test
    void accepts_an_active_mapping_once_and_authorizes_the_mapped_identity() {
        UUID identityId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        var event = new MemberMappingChangedEvent(
                UUID.randomUUID(), identityId, memberId, 1, MappingLifecycleState.ACTIVE,
                Instant.parse("2026-08-10T00:00:00Z"));

        projection.consume(event);
        projection.consume(event);

        assertThat(projection.mappedMember(identityId)).contains(memberId);
        assertThat(projection.canAccessLedger(identityId)).isTrue();
        assertThat(projection.appliedEventCount()).isEqualTo(1);
    }

    @Test
    void fails_closed_until_an_active_mapping_arrives() {
        assertThat(projection.canAccessLedger(UUID.randomUUID())).isFalse();
    }
}
