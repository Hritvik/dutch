package com.dutch.iam.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;

class DevelopmentVerifiedIdentityTest {

    @Test
    void is_only_available_in_the_development_profile_and_is_deterministic() {
        var profile = DevelopmentVerifiedIdentity.class.getAnnotation(Profile.class);
        assertThat(Arrays.asList(profile.value())).containsExactly("development");

        var flow = new DevelopmentVerifiedIdentity();
        assertThat(flow.identityId("alice")).isEqualTo(flow.identityId("alice"));
        assertThat(flow.identityId("alice")).isNotEqualTo(flow.identityId("bob"));
    }

    @Test
    void repeated_deterministic_fixture_creation_is_idempotent() {
        var provisioning = mock(IdentityProvisioningService.class);
        doThrow(new DataIntegrityViolationException("duplicate fixture"))
                .when(provisioning).provisionVerified(any(), any(), any());
        var controller = new DevelopmentVerifiedIdentityController(new DevelopmentVerifiedIdentity(), provisioning);

        assertThatCode(() -> controller.create("alice")).doesNotThrowAnyException();
    }
}
