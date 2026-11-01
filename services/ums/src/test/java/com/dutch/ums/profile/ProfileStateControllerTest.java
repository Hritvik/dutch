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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProfileStateController.class)
class ProfileStateControllerTest {
    private static final String SUBJECT = "aps_0123456789012345678901ab";
    @Autowired MockMvc mvc;
    @MockBean MemberProfileRepository profiles;
    @MockBean JwtDecoder jwtDecoder;

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownSubjectReturnsProfileRequiredWithCorrelation() throws Exception {
        when(profiles.findByAppSubject(ProfileStateController.APP, SUBJECT)).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/me").header("X-Correlation-Id", "7af1f54e-c812-49b9-9837-5c0a1b25c0eb")
                        .with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-Correlation-Id", "7af1f54e-c812-49b9-9837-5c0a1b25c0eb"))
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
                .andExpect(jsonPath("$.data.identityId").doesNotExist())
                .andExpect(jsonPath("$.data.appSubject").doesNotExist());
    }

    @Test
    void aSuspendedProfileIsNotReportedAsReady() throws Exception {
        var profile = new MemberProfile("mem_112345678901234567", "dutch-development", SUBJECT, null,
                ProfileState.PROFILE_REQUIRED, MemberLifecycleState.SUSPENDED, 1,
                Instant.parse("2026-10-31T00:00:00Z"));
        when(profiles.findByAppSubject(ProfileStateController.APP, SUBJECT)).thenReturn(Optional.of(profile));

        mvc.perform(get("/api/v1/me").with(jwt().jwt(j -> j.subject(SUBJECT))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("profile_required"));
    }
}
