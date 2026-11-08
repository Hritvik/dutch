package com.dutch.ledger.mapping;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * The app-scoped profile binding UMS observed for one app subject. It carries no
 * display value, contact identifier, or global identity, and it never names a
 * Dutch member: the mapping stays Dutch-owned.
 *
 * <p>The envelope identity is kept because a report minted for another app
 * registration or environment must never be applied to this deployment, and a
 * report whose aggregate disagrees with its payload is malformed.
 */
public record MemberAssociationReport(
        String eventId,
        String aggregateId,
        String appSubject,
        String appRegistrationId,
        String environment,
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
    private static final String PROFILE_REVISION = "^(0|[1-9][0-9]{0,18})$";
    private static final Set<String> PROFILE_STATES =
            Set.of("PROFILE_REQUIRED", "PROFILE_READY");
    private static final Set<String> LIFECYCLE_STATES =
            Set.of("ACTIVE", "CLOSED", "DELETION_PENDING", "ERASED");
    private static final Set<String> REASONS = Set.of(
            "DELETION_REQUESTED", "DELETION_CANCELLED", "CLOSED", "ERASED", "REOPENED");
    private static final Set<String> ENVIRONMENTS =
            Set.of("development", "test", "staging", "production");

    public MemberAssociationReport {
        if (eventId == null || !eventId.matches("^evt_[A-Za-z0-9_-]{16,128}$")) {
            throw new IllegalArgumentException("eventId does not match the pinned format");
        }
        if (aggregateId == null || !aggregateId.matches(APP_SUBJECT)) {
            throw new IllegalArgumentException("aggregateId does not match the pinned format");
        }
        if (appSubject == null || !appSubject.matches(APP_SUBJECT)) {
            throw new IllegalArgumentException("appSubject does not match the pinned format");
        }
        if (!aggregateId.equals(appSubject)) {
            throw new IllegalArgumentException("aggregate.id must equal payload.appSubject");
        }
        if (appRegistrationId == null || !appRegistrationId.matches("^[a-z][a-z0-9-]{2,63}$")) {
            throw new IllegalArgumentException("appRegistrationId does not match the pinned format");
        }
        if (!ENVIRONMENTS.contains(environment)) {
            throw new IllegalArgumentException("environment is not a permitted value");
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
            if (!profileRevision.matches(PROFILE_REVISION) || exceedsLong(profileRevision)) {
                throw new IllegalArgumentException("profileRevision is not a canonical version");
            }
            if (retiredGeneration != null) {
                throw new IllegalArgumentException("a live report must not carry retiredGeneration");
            }
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static boolean exceedsLong(String value) {
        try {
            return Long.parseLong(value) < 0;
        } catch (NumberFormatException overflow) {
            return true;
        }
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

    /** True when this report belongs to the deployment it is being applied to. */
    public boolean belongsTo(String expectedAppRegistrationId, String expectedEnvironment) {
        return appRegistrationId.equals(expectedAppRegistrationId)
                && environment.equals(expectedEnvironment);
    }
}
