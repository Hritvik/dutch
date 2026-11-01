package com.dutch.ledger.mapping;

import java.time.Instant;
import java.util.UUID;

/** Local Ledger projection event, containing only stable IDs and lifecycle state. */
public record MemberMappingChangedEvent(
        UUID eventId, UUID identityId, UUID memberId, long mappingVersion,
        MappingLifecycleState lifecycleState, Instant occurredAt) {
}
