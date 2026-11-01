package com.dutch.ums.profile;

import java.time.Instant;

public record OnboardingReadyEvent(String eventId, String appRegistrationId, String appSubject,
                                   String activationId, String activationState, long activationVersion,
                                   String onboardingReason, long onboardingVersion,
                                   String requiredProfileState, Instant occurredAt, String correlationId,
                                   String causationId) {
    public OnboardingReadyEvent {
        if (eventId == null || !eventId.matches("^evt_[A-Za-z0-9_-]{16,128}$")
                || appRegistrationId == null || !appRegistrationId.matches("^[a-z][a-z0-9-]{2,63}$")
                || appSubject == null || !appSubject.matches("^aps_[A-Za-z0-9_-]{22,128}$")
                || activationId == null || !activationId.matches("^act_[A-Za-z0-9_-]{16,128}$")
                || !"ACTIVE".equals(activationState)
                || activationVersion < 1 || onboardingVersion < 1
                || !"FIRST_ACTIVATION".equals(onboardingReason) && !"RECONCILIATION".equals(onboardingReason)
                || !"PROFILE_REQUIRED".equals(requiredProfileState)
                || occurredAt == null || correlationId == null
                || (causationId != null && !causationId.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"))) {
            throw new IllegalArgumentException("onboarding_event_invalid");
        }
    }

    @Override public String toString() { return "OnboardingReadyEvent[eventId=" + eventId + ",redacted]"; }
}
