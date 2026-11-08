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
        assertThatThrownBy(() -> new MemberAssociationReport("evt_unit_00000000000001", SUBJECT,
                1, "PROFILE_READY", "ACTIVE", null, null, "5", null, false, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generation and profileRevision");
    }

    @Test
    void an_erased_report_must_not_carry_live_fields() {
        assertThatThrownBy(() -> new MemberAssociationReport("evt_unit_00000000000002", SUBJECT,
                2, "PROFILE_REQUIRED", "ERASED", "ERASED", 3L, "5", 3L, false, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("live fields");
    }

    @Test
    void a_tombstone_without_a_retired_generation_is_rejected() {
        assertThatThrownBy(() -> new MemberAssociationReport("evt_unit_00000000000003", SUBJECT,
                2, "PROFILE_REQUIRED", "ERASED", "ERASED", null, null, null, false, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retiredGeneration");
    }

    @Test
    void only_a_ready_active_report_grants_profile_access() {
        assertThat(live("PROFILE_READY", "ACTIVE").grantsProfileAccess()).isTrue();
        assertThat(live("PROFILE_REQUIRED", "ACTIVE").grantsProfileAccess()).isFalse();
        assertThat(live("PROFILE_READY", "DELETION_PENDING").grantsProfileAccess()).isFalse();
        assertThat(live("PROFILE_READY", "CLOSED").grantsProfileAccess()).isFalse();
    }

    @Test
    void a_stale_subject_identifier_is_rejected() {
        assertThatThrownBy(() -> new MemberAssociationReport("evt_unit_00000000000004",
                "hid_0123456789012345678901", 1, "PROFILE_READY", "ACTIVE", null,
                1L, "5", null, false, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appSubject");
    }

    private static MemberAssociationReport live(String profileState, String lifecycleState) {
        return new MemberAssociationReport("evt_unit_00000000000009", SUBJECT, 1, profileState,
                lifecycleState, null, 1L, "5", null, false, NOW);
    }
}
