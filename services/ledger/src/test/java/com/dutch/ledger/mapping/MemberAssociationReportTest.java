package com.dutch.ledger.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class MemberAssociationReportTest {

    private static final String SUBJECT = "aps_unit_012345678901234567";
    private static final Instant NOW = Instant.parse("2026-11-05T09:00:00Z");

    @Test
    void a_live_report_requires_generation_and_profile_revision() {
        assertThatThrownBy(() -> live("PROFILE_READY", "ACTIVE", null, "5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generation and profileRevision");
    }

    @Test
    void an_erased_report_must_not_carry_live_fields() {
        assertThatThrownBy(() -> new MemberAssociationReport("evt_unit_00000000000002", SUBJECT,
                SUBJECT, "dutch-development", "test", 2, "PROFILE_REQUIRED", "ERASED",
                "ERASED", 3L, "5", 3L, false, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("live fields");
    }

    @Test
    void a_tombstone_without_a_retired_generation_is_rejected() {
        assertThatThrownBy(() -> new MemberAssociationReport("evt_unit_00000000000003", SUBJECT,
                SUBJECT, "dutch-development", "test", 2, "PROFILE_REQUIRED", "ERASED",
                "ERASED", null, null, null, false, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retiredGeneration");
    }

    @Test
    void a_report_whose_aggregate_disagrees_with_its_payload_is_rejected() {
        assertThatThrownBy(() -> new MemberAssociationReport("evt_unit_00000000000005",
                "aps_other_012345678901234567", SUBJECT, "dutch-development", "test",
                1, "PROFILE_READY", "ACTIVE", null, 1L, "5", null, false, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate.id");
    }

    @Test
    void a_report_for_another_app_or_environment_does_not_belong_here() {
        assertThat(liveFor("dutch-production", "test").belongsTo("dutch-development", "test"))
                .isFalse();
        assertThat(liveFor("dutch-development", "staging").belongsTo("dutch-development", "test"))
                .isFalse();
        assertThat(liveFor("dutch-development", "test").belongsTo("dutch-development", "test"))
                .isTrue();
    }

    @Test
    void a_non_canonical_profile_revision_is_rejected() {
        assertThatThrownBy(() -> live("PROFILE_READY", "ACTIVE", null, "05"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profileRevision");
        assertThatThrownBy(() -> live("PROFILE_READY", "ACTIVE", null, "99999999999999999999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profileRevision");
    }

    @Test
    void a_stale_subject_identifier_is_rejected() {
        assertThatThrownBy(() -> new MemberAssociationReport("evt_unit_00000000000004",
                "hid_0123456789012345678901", "hid_0123456789012345678901",
                "dutch-development", "test", 1, "PROFILE_READY", "ACTIVE", null,
                1L, "5", null, false, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pinned format");
    }

    private static MemberAssociationReport live(String profileState, String lifecycleState,
                                                Long generation, String profileRevision) {
        return new MemberAssociationReport("evt_unit_00000000000009", SUBJECT, SUBJECT,
                "dutch-development", "test", 1, profileState, lifecycleState, null,
                generation, profileRevision, null, false, NOW);
    }

    private static MemberAssociationReport liveFor(String registration, String environment) {
        return new MemberAssociationReport("evt_unit_00000000000010", SUBJECT, SUBJECT,
                registration, environment, 1, "PROFILE_READY", "ACTIVE", null,
                1L, "5", null, false, NOW);
    }
}
