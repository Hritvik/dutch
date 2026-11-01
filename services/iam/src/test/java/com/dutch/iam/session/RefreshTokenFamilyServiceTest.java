package com.dutch.iam.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenFamilyServiceTest {

    private final RefreshTokenFamilyService service = new RefreshTokenFamilyService(
            Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void rotates_an_unseen_current_token_without_retaining_its_value() {
        var family = service.create(UUID.randomUUID(), "first-refresh-token-value");

        var result = service.rotate(family.familyId(), "first-refresh-token-value", "second-refresh-token-value");

        assertThat(result).isEqualTo(RefreshResult.ROTATED);
        assertThat(service.isActive(family.familyId())).isTrue();
        assertThat(service.hasRawTokenValue(family.familyId(), "first-refresh-token-value")).isFalse();
    }

    @Test
    void reuse_of_a_rotated_token_revokes_the_entire_family() {
        var family = service.create(UUID.randomUUID(), "first-refresh-token-value");
        service.rotate(family.familyId(), "first-refresh-token-value", "second-refresh-token-value");

        var result = service.rotate(family.familyId(), "first-refresh-token-value", "third-refresh-token-value");

        assertThat(result).isEqualTo(RefreshResult.REAUTHENTICATION_REQUIRED);
        assertThat(service.isActive(family.familyId())).isFalse();
    }
}
