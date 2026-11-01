package com.dutch.ums.profile;

import java.time.Instant;

public record MemberMappingChanged(String eventId, String appRegistrationId, String appSubject, String memberId,
                                   long mappingVersion, ProfileState profileState,
                                   MemberLifecycleState lifecycleState, Instant occurredAt,
                                   String correlationId, String causationId) {
    @Override public String toString() { return "MemberMappingChanged[eventId=" + eventId + ",redacted]"; }
}
