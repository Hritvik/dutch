package com.dutch.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GatewayRoutingFilterTest {

    private final GatewayRoutingFilter filter = new GatewayRoutingFilter(
            new GatewayRouteProperties("http://127.0.0.1:1", "http://127.0.0.1:1"),
            new ObjectMapper());

    @Test
    void only_filters_the_owned_surfaces() {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/other"))).isTrue();
        // /api/v1/me* is translated by MeController, not forwarded.
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/me"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/me/profile-state"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/ledger/home"))).isFalse();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET",
                "/api/v1/notifications/devices"))).isFalse();
    }

    @Test
    void rejects_a_traversal_path_before_calling_upstream() throws Exception {
        assertRejected("/api/v1/groups/../secret");
    }

    @Test
    void rejects_an_encoded_traversal_path() throws Exception {
        assertRejected("/api/v1/groups/%2e%2e/secret");
    }

    private void assertRejected(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("\"code\":\"invalid_request\"");
        assertThat(response.getHeader(CorrelationId.HEADER)).isNotNull();
    }
}
