package com.dutch.ledger.mapping;

import java.time.Instant;
import java.util.UUID;

/**
 * @deprecated legacy UUID-keyed projection; new code uses the app-subject mapping
 * table and {@link MemberMappingEvent}.
 */
@Deprecated(forRemoval = true)
/** Local Ledger projection event, containing only stable IDs and lifecycle state. */
public record MemberMappingChangedEvent(
        UUID eventId, UUID identityId, UUID memberId, long mappingVersion,
        MappingLifecycleState lifecycleState, Instant occurredAt) {
}
