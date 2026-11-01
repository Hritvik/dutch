package com.dutch.ums.profile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ProfileErrorHandlerTest {
    private final ProfileErrorHandler handler = new ProfileErrorHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");

    @Test
    void aMissingCorrelationStillProducesAConformingEnvelope() {
        var response = handler.invalidRequest(new IllegalArgumentException("bad row"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        @SuppressWarnings("unchecked")
        var error = (java.util.Map<String, Object>) response.getBody().get("error");
        assertThat(error.get("code")).isEqualTo("internal_error");
        assertThat(error.get("correlationId")).isEqualTo("cor_unknown");
    }

    @Test
    void aSuppliedCorrelationIsPreserved() {
        request.setAttribute(CorrelationIdFilter.ATTRIBUTE, "cor_0123456789012345");

        var response = handler.invalidRequest(new IllegalArgumentException("bad row"), request);

        @SuppressWarnings("unchecked")
        var error = (java.util.Map<String, Object>) response.getBody().get("error");
        assertThat(error.get("correlationId")).isEqualTo("cor_0123456789012345");
    }
}
