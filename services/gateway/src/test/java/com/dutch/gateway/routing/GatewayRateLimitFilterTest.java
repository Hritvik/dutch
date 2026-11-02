package com.dutch.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GatewayRateLimitFilterTest {

    private final GatewayRateLimitFilter filter =
            new GatewayRateLimitFilter(2, 60, new ObjectMapper());

    @Test
    void sheds_requests_after_the_per_identity_budget() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = (request, response) -> calls.incrementAndGet();
        MockHttpServletRequest first = request();
        MockHttpServletRequest second = request();
        MockHttpServletRequest third = request();
        MockHttpServletResponse limited = new MockHttpServletResponse();

        filter.doFilter(first, new MockHttpServletResponse(), chain);
        filter.doFilter(second, new MockHttpServletResponse(), chain);
        filter.doFilter(third, limited, chain);

        assertThat(calls).hasValue(2);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
        assertThat(limited.getContentAsString()).contains("\"code\":\"rate_limited\"");
    }

    @Test
    void keeps_separate_buckets_per_client_address_and_ignores_non_api_paths() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = (request, response) -> calls.incrementAndGet();
        MockHttpServletRequest otherClient = request();
        otherClient.setRemoteAddr("10.0.0.9");

        filter.doFilter(request(), new MockHttpServletResponse(), chain);
        filter.doFilter(request(), new MockHttpServletResponse(), chain);
        filter.doFilter(otherClient, new MockHttpServletResponse(), chain);

        assertThat(calls).hasValue(3);
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health")))
                .isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/ledger/home")))
                .isFalse();
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/v1/ledger/home");
    }
}
