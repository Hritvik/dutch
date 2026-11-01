package com.dutch.ledger.mapping;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Ledger-owned, fail-closed authorization projection fed from UMS events. */
public final class IdentityMemberMappingProjection {

    private Map<UUID, MemberMappingChangedEvent> mappingsByIdentity = Map.of();
    private Set<UUID> appliedEventIds = Set.of();

    public synchronized void consume(MemberMappingChangedEvent event) {
        if (appliedEventIds.contains(event.eventId())) {
            return;
        }
        MemberMappingChangedEvent current = mappingsByIdentity.get(event.identityId());
        if (current == null || event.mappingVersion() > current.mappingVersion()) {
            Map<UUID, MemberMappingChangedEvent> nextMappings = new HashMap<>(mappingsByIdentity);
            nextMappings.put(event.identityId(), event);
            mappingsByIdentity = Map.copyOf(nextMappings);
        }
        Set<UUID> nextEventIds = new HashSet<>(appliedEventIds);
        nextEventIds.add(event.eventId());
        appliedEventIds = Set.copyOf(nextEventIds);
    }

    public synchronized Optional<UUID> mappedMember(UUID identityId) {
        MemberMappingChangedEvent mapping = mappingsByIdentity.get(identityId);
        return mapping != null && mapping.lifecycleState() == MappingLifecycleState.ACTIVE
                ? Optional.of(mapping.memberId())
                : Optional.empty();
    }

    public synchronized boolean canAccessLedger(UUID identityId) {
        return mappedMember(identityId).isPresent();
    }

    public synchronized int appliedEventCount() {
        return appliedEventIds.size();
    }
}
