package com.dutch.ledger.mapping;

import java.time.Instant;
import java.util.Objects;

/**
 * The app-scoped profile binding UMS observed for one app subject. It carries no
 * display value, contact identifier, or global identity, and it never names a
 * Dutch member: the mapping stays Dutch-owned.
 */
public record MemberAssociationReport(
        String eventId,
        String appSubject,
        long bindingRevision,
        String profileState,
        String lifecycleState,
        String lifecycleReason,
        Long generation,
        String profileRevision,
        Long retiredGeneration,
        boolean contentObligation,
        Instant occurredAt) {

    private static final String APP_SUBJECT = "^aps_[A-Za-z0-9_-]{18,128}$";
    private static final java.util.Set<String> PROFILE_STATES =
            java.util.Set.of("PROFILE_REQUIRED", "PROFILE_READY");
    private static final java.util.Set<String> LIFECYCLE_STATES =
            java.util.Set.of("ACTIVE", "CLOSED", "DELETION_PENDING", "ERASED");
    private static final java.util.Set<String> REASONS = java.util.Set.of(
            "DELETION_REQUESTED", "DELETION_CANCELLED", "CLOSED", "ERASED", "REOPENED");

    public MemberAssociationReport {
        if (eventId == null || !eventId.matches("^evt_[A-Za-z0-9_-]{16,128}$")) {
            throw new IllegalArgumentException("eventId does not match the pinned format");
        }
        if (appSubject == null || !appSubject.matches(APP_SUBJECT)) {
            throw new IllegalArgumentException("appSubject does not match the pinned format");
        }
        if (bindingRevision < 1) {
            throw new IllegalArgumentException("bindingRevision must be positive");
        }
        if (!PROFILE_STATES.contains(profileState)) {
            throw new IllegalArgumentException("profileState is not a permitted value");
        }
        if (!LIFECYCLE_STATES.contains(lifecycleState)) {
            throw new IllegalArgumentException("lifecycleState is not a permitted value");
        }
        if (lifecycleReason != null && !REASONS.contains(lifecycleReason)) {
            throw new IllegalArgumentException("lifecycleReason is not a permitted value");
        }
        if (Objects.equals(lifecycleState, "ERASED")) {
            if (retiredGeneration == null) {
                throw new IllegalArgumentException("an erased report requires retiredGeneration");
            }
            if (generation != null || profileRevision != null) {
                throw new IllegalArgumentException("an erased report must not carry live fields");
            }
        } else {
            if (generation == null || profileRevision == null) {
                throw new IllegalArgumentException("a live report requires generation and profileRevision");
            }
            if (retiredGeneration != null) {
                throw new IllegalArgumentException("a live report must not carry retiredGeneration");
            }
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /** True only when UMS states the profile is ready and unrestricted. */
    public boolean grantsProfileAccess() {
        return "PROFILE_READY".equals(profileState) && "ACTIVE".equals(lifecycleState);
    }

    public boolean isErasure() {
        return "ERASED".equals(lifecycleState);
    }

    /**
     * A report that resumes a generation UMS already retired cannot be a new life, no
     * matter when it arrived. Ordering comes from the binding revision, never a clock.
     */
    public boolean reusesRetiredGeneration(Long previousRetiredGeneration) {
        if (previousRetiredGeneration == null || isErasure()) {
            return false;
        }
        return generation != null && generation <= previousRetiredGeneration;
    }
}
