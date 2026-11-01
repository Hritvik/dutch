package com.dutch.ledger.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dutch.ledger.group.GroupRuleException;
import com.dutch.ledger.group.MemberId;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActivityLogTest {

    private static final String SCOPE = "group:grp_012345678901234567:INR";
    private static final String TARGET = "exp_012345678901234567";
    private static final MemberId ACTOR = new MemberId("mem_012345678901234567");
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void appends_creation_and_correction_evidence_in_order() {
        ActivityLog log = new ActivityLog();
        log.append(entry("act_000000000000000001", ActivityAction.EXPENSE_CREATED, 1,
                Map.of("description", "Dinner", "totalMinorUnits", "100", "currency", "INR")));
        log.append(entry("act_000000000000000002", ActivityAction.EXPENSE_CORRECTED, 2,
                Map.of("description", "Dinner", "totalMinorUnits", "120", "currency", "INR",
                        "fromRevision", "1", "toRevision", "2")));

        assertThat(log.entries()).extracting(ActivityEntry::action)
                .containsExactly(ActivityAction.EXPENSE_CREATED, ActivityAction.EXPENSE_CORRECTED);
        assertThat(log.entries().get(1).details())
                .containsEntry("fromRevision", "1")
                .containsEntry("toRevision", "2")
                .containsEntry("totalMinorUnits", "120");
    }

    @Test
    void retains_deleted_expense_events() {
        ActivityLog log = new ActivityLog();
        log.append(entry("act_100000000000000001", ActivityAction.EXPENSE_CREATED, 1,
                Map.of("description", "Dinner", "totalMinorUnits", "100", "currency", "INR")));
        log.append(entry("act_100000000000000002", ActivityAction.EXPENSE_DELETED, 2,
                Map.of("status", "DELETED")));

        assertThat(log.entries()).extracting(ActivityEntry::action)
                .containsExactly(ActivityAction.EXPENSE_CREATED, ActivityAction.EXPENSE_DELETED);
    }

    @Test
    void rejects_duplicate_ids() {
        ActivityLog log = new ActivityLog();
        ActivityEntry entry = entry("act_200000000000000001", ActivityAction.EXPENSE_CREATED, 1,
                Map.of("description", "Dinner"));
        log.append(entry);

        assertThatThrownBy(() -> log.append(entry))
                .isInstanceOf(GroupRuleException.class)
                .hasMessageContaining("already been recorded");
    }

    @Test
    void rejects_forbidden_details_and_contact_values() {
        assertThatThrownBy(() -> entry("act_300000000000000001", ActivityAction.MEMBERSHIP_CHANGED, 1,
                Map.of("email", "person@example.test")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not permitted");

        assertThatThrownBy(() -> entry("act_300000000000000002", ActivityAction.MEMBERSHIP_CHANGED, 1,
                Map.of("description", "person@example.test")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contact identifiers");

        assertThatThrownBy(() -> entry("act_300000000000000003", ActivityAction.MEMBERSHIP_CHANGED, 1,
                Map.of("description", "+919876543210")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contact identifiers");
    }

    @Test
    void a_former_member_has_no_actor_identifier() {
        ActivityEntry entry = new ActivityEntry("act_400000000000000001", SCOPE, null,
                ActivityAction.MEMBERSHIP_CHANGED, "mem_112345678901234567", 1, NOW,
                Map.of("role", "MEMBER"));
        assertThat(entry.actor()).isNull();
    }

    private static ActivityEntry entry(String id, ActivityAction action, long revision,
                                       Map<String, String> details) {
        return new ActivityEntry(id, SCOPE, ACTOR, action, TARGET, revision, NOW, details);
    }
}
