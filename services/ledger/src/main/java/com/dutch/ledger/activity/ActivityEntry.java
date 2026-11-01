package com.dutch.ledger.activity;

import com.dutch.ledger.group.MemberId;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One append-only Activity fact. Details are a closed whitelist and must never
 * contain contact identifiers; a null actor renders as Former member.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivityEntry(
        String activityId,
        String scopeId,
        MemberId actor,
        ActivityAction action,
        String targetId,
        long targetRevision,
        Instant occurredAt,
        Map<String, String> details) {

    private static final Pattern ID = Pattern.compile("^act_[A-Za-z0-9_-]{16,128}$");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final Pattern PHONE = Pattern.compile("\\+[1-9]\\d{6,14}");
    private static final Set<String> ALLOWED_DETAILS = Set.of(
            "description", "totalMinorUnits", "currency", "fromRevision", "toRevision",
            "status", "role", "periodStart", "periodEnd", "cadence", "scopeType");

    /** Returns null when free text would leak a contact identifier. */
    public static String contactFree(String value) {
        if (value == null) {
            return null;
        }
        return EMAIL.matcher(value).find() || PHONE.matcher(value).find() ? null : value;
    }

    public ActivityEntry {
        if (activityId == null || !ID.matcher(activityId).matches()) {
            throw new IllegalArgumentException("activityId does not match the pinned format");
        }
        if (scopeId == null || scopeId.isBlank()) {
            throw new IllegalArgumentException("scopeId is required");
        }
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (targetRevision < 0) {
            throw new IllegalArgumentException("targetRevision cannot be negative");
        }
        if (details == null) {
            throw new IllegalArgumentException("details are required");
        }
        for (Map.Entry<String, String> detail : details.entrySet()) {
            if (!ALLOWED_DETAILS.contains(detail.getKey())) {
                throw new IllegalArgumentException("activity detail is not permitted: " + detail.getKey());
            }
            if (detail.getValue() != null
                    && (EMAIL.matcher(detail.getValue()).find()
                    || PHONE.matcher(detail.getValue()).find())) {
                throw new IllegalArgumentException("activity details must not contain contact identifiers");
            }
        }
        details = Map.copyOf(details);
    }
}
