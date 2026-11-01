package com.dutch.ums.profile;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProfileStateController.class)
@Import(UmsSecurityConfiguration.class)
class ProfileStateControllerTest {
    private static final String SUBJECT = "aps_0123456789012345678901ab";
    @Autowired MockMvc mvc;
    @MockBean MemberProfileRepository profiles;
    @MockBean JwtDecoder jwtDecoder;

    @Test
    void unauthenticatedRequestsAreRejectedWithThePinnedEnvelope() throws Exception {
        mvc.perform(get("/api/v1/me").header("X-Correlation-Id", "cor_0123456789012345"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-Id", "cor_0123456789012345"))
                .andExpect(jsonPath("$.error.code").value("unauthorized"))
                .andExpect(jsonPath("$.error.message").value("Authentication is required"))
                .andExpect(jsonPath("$.error.correlationId").value("cor_0123456789012345"));
    }

    @Test
    void anInvalidCorrelationIsRegeneratedInThePinnedShape() throws Exception {
        mvc.perform(get("/api/v1/me/profile-state").header("X-Correlation-Id", "-invalid-correlation")
                        .with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", org.hamcrest.Matchers.matchesPattern("^cor_[A-Za-z0-9]{16,124}$")));
    }

    @Test
    void anUnknownSubjectReturnsProfileRequiredWithCorrelation() throws Exception {
        when(profiles.findByAppSubject(ProfileStateController.APP, SUBJECT)).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/me").header("X-Correlation-Id", "cor_0123456789012345")
                        .with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-Correlation-Id", "cor_0123456789012345"))
                .andExpect(jsonPath("$.error.code").value("profile_required"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void aReadyProfileReturnsOnlyTheDutchOwnedFields() throws Exception {
        var profile = new MemberProfile("mem_012345678901234567", "dutch-development", SUBJECT, "Ada",
                ProfileState.PROFILE_READY, MemberLifecycleState.ACTIVE, 1, Instant.parse("2026-10-31T00:00:00Z"));
        when(profiles.findByAppSubject(ProfileStateController.APP, SUBJECT)).thenReturn(Optional.of(profile));

        mvc.perform(get("/api/v1/me").with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(profile.memberId()))
                .andExpect(jsonPath("$.data.displayName").value("Ada"))
                .andExpect(jsonPath("$.data.profileState").value("PROFILE_READY"))
                .andExpect(jsonPath("$.data.lifecycleState").value("ACTIVE"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.aMapWithSize(4)))
                .andExpect(jsonPath("$.data.identityId").doesNotExist())
                .andExpect(jsonPath("$.data.appSubject").doesNotExist());
    }

    @Test
    void aCorruptProfileRowIsAnInternalErrorNotAClientError() throws Exception {
        when(profiles.findByAppSubject(ProfileStateController.APP, SUBJECT))
                .thenThrow(new IllegalArgumentException("bad row"));

        mvc.perform(get("/api/v1/me").header("X-Correlation-Id", "cor_0123456789012345")
                        .with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal_error"))
                .andExpect(jsonPath("$.error.message").value("Request could not be completed"))
                .andExpect(jsonPath("$.error.correlationId").value("cor_0123456789012345"));
    }

    @Test
    void aContractConformingCorrelationIsEchoedEvenWithoutTheCorPrefix() throws Exception {
        when(profiles.findByAppSubject(ProfileStateController.APP, SUBJECT)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/me").header("X-Correlation-Id", "trace.abc-123:4")
                        .with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(header().string("X-Correlation-Id", "trace.abc-123:4"));
    }

    @Test
    void anOverlongOrControlCorrelationIsRegenerated() throws Exception {
        when(profiles.findByAppSubject(ProfileStateController.APP, SUBJECT)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/me").header("X-Correlation-Id", "a".repeat(129))
                        .with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(header().string("X-Correlation-Id",
                        org.hamcrest.Matchers.matchesPattern("^cor_[A-Za-z0-9]{16,124}$")));
    }

    @Test
    void profileStateReportsRequiredForAnUnknownSubject() throws Exception {
        when(profiles.findByAppSubject(ProfileStateController.APP, SUBJECT)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/me/profile-state").with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PROFILE_REQUIRED"))
                .andExpect(jsonPath("$.data.appSubject").doesNotExist())
                .andExpect(jsonPath("$.data.memberId").doesNotExist());
    }

    @Test
    void profileStateReportsProvisioningAndReady() throws Exception {
        var provisioning = new MemberProfile("mem_212345678901234567", "dutch-development", SUBJECT, null,
                ProfileState.PROFILE_PROVISIONING, MemberLifecycleState.ACTIVE, 1,
                Instant.parse("2026-10-31T00:00:00Z"));
        when(profiles.findByAppSubject(ProfileStateController.APP, SUBJECT)).thenReturn(Optional.of(provisioning));
        mvc.perform(get("/api/v1/me/profile-state").with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PROFILE_PROVISIONING"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.aMapWithSize(1)));

        var ready = new MemberProfile("mem_312345678901234567", "dutch-development", SUBJECT, "Ada",
                ProfileState.PROFILE_READY, MemberLifecycleState.ACTIVE, 1,
                Instant.parse("2026-10-31T00:00:00Z"));
        when(profiles.findByAppSubject(ProfileStateController.APP, SUBJECT)).thenReturn(Optional.of(ready));
        mvc.perform(get("/api/v1/me/profile-state").header("X-Correlation-Id", "cor_0123456789012345")
                        .with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "cor_0123456789012345"))
                .andExpect(jsonPath("$.data.state").value("PROFILE_READY"));
    }

    @Test
    void profileStateRejectsUnauthenticatedRequests() throws Exception {
        mvc.perform(get("/api/v1/me/profile-state")).andExpect(status().isUnauthorized());
    }

    @Test
    void aReadyButSuspendedProfileIsNotReportedAsReady() throws Exception {
        var profile = new MemberProfile("mem_112345678901234567", "dutch-development", SUBJECT, "Ada",
                ProfileState.PROFILE_READY, MemberLifecycleState.SUSPENDED, 1,
                Instant.parse("2026-10-31T00:00:00Z"));
        when(profiles.findByAppSubject(ProfileStateController.APP, SUBJECT)).thenReturn(Optional.of(profile));

        mvc.perform(get("/api/v1/me").header("X-Correlation-Id", "cor_0123456789012345")
                        .with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("profile_required"))
                .andExpect(jsonPath("$.error.message").value("Complete your profile to continue."))
                .andExpect(jsonPath("$.error.correlationId").value("cor_0123456789012345"));
        mvc.perform(get("/api/v1/me/profile-state").with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PROFILE_REQUIRED"));
    }

    @Test
    void aReadyProfileWithANullDisplayNameIsNotReady() throws Exception {
        var profile = new MemberProfile("mem_512345678901234567", "dutch-development", SUBJECT, null,
                ProfileState.PROFILE_READY, MemberLifecycleState.ACTIVE, 1,
                Instant.parse("2026-10-31T00:00:00Z"));
        when(profiles.findByAppSubject(ProfileStateController.APP, SUBJECT)).thenReturn(Optional.of(profile));

        mvc.perform(get("/api/v1/me").with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/me/profile-state").with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PROFILE_REQUIRED"));
    }

    @Test
    void aReadyProfileWithoutADisplayNameIsNotReady() throws Exception {
        var profile = new MemberProfile("mem_412345678901234567", "dutch-development", SUBJECT, " ",
                ProfileState.PROFILE_READY, MemberLifecycleState.ACTIVE, 1,
                Instant.parse("2026-10-31T00:00:00Z"));
        when(profiles.findByAppSubject(ProfileStateController.APP, SUBJECT)).thenReturn(Optional.of(profile));

        mvc.perform(get("/api/v1/me").with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/me/profile-state").with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PROFILE_REQUIRED"));
    }
}
