package com.dutch.gateway.routing;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Proves the Dutch `/api/v1/me` contract against canonical UMS shapes. */
@SpringBootTest
@AutoConfigureMockMvc
class MeRouteTest {

    private static final Stub UMS = start(exchange -> {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/status")) {
            return "{\"data\":{\"state\":{\"readiness\":\"PROFILE_READY\",\"lifecycle\":\"ACTIVE\"},"
                    + "\"unmetFields\":[],\"nextActions\":[]}}";
        }
        return "{\"data\":{\"fields\":{\"displayName\":\"Ada\"},\"profileRevision\":\"5\"}}";
    });

    private static final Stub LEDGER = start(exchange -> {
        String query = exchange.getRequestURI().getQuery();
        if (query != null && query.contains("aps_unmapped")) {
            return "{\"code\":\"not_found\"}";
        }
        return "{\"memberId\":\"mem_route_0123456789\"}";
    });

    static {
        // The stub must answer 404 for an unmapped subject, not 200 with an error body.
        LEDGER.statusFor = exchange -> exchange.getRequestURI().getQuery() != null
                && exchange.getRequestURI().getQuery().contains("aps_unmapped") ? 404 : 200;
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("gateway.routes.ums-base-url", UMS::baseUrl);
        registry.add("gateway.routes.ledger-base-url", LEDGER::baseUrl);
        registry.add("dutch.gateway.ums-service-token", () -> "test-service-token");
    }

    @AfterAll
    static void stop() {
        UMS.close();
        LEDGER.close();
    }

    @Test
    void renders_the_dutch_profile_from_canonical_ums_and_the_ledger_mapping() throws Exception {
        authenticate("aps_member_012345678901234567");

        mockMvc.perform(get("/api/v1/me/profile-state").header("Authorization", "Bearer valid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PROFILE_READY"));

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer valid"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationId.HEADER))
                .andExpect(jsonPath("$.data.memberId").value("mem_route_0123456789"))
                .andExpect(jsonPath("$.data.displayName").value("Ada"))
                .andExpect(jsonPath("$.data.profileState").value("PROFILE_READY"));

        org.assertj.core.api.Assertions.assertThat(LEDGER.lastServiceToken.get())
                .isEqualTo("test-service-token");
    }

    @Test
    void a_ready_profile_without_a_mapping_is_forbidden() throws Exception {
        authenticate("aps_unmapped_012345678901234");

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer valid"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden"))
                .andExpect(jsonPath("$.error.message").value("Access is denied"));
    }

    private void authenticate(String subject) {
        when(jwtDecoder.decode("valid")).thenReturn(Jwt.withTokenValue("valid")
                .header("alg", "none")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build());
    }

    private interface BodySource {
        String body(com.sun.net.httpserver.HttpExchange exchange);
    }

    private interface StatusSource {
        int status(com.sun.net.httpserver.HttpExchange exchange);
    }

    private static final class Stub {
        private final HttpServer server;
        private final AtomicReference<String> lastServiceToken = new AtomicReference<>();
        private StatusSource statusFor = exchange -> 200;

        private Stub(BodySource bodies) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                lastServiceToken.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
                byte[] payload = bodies.body(exchange).getBytes(UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(statusFor.status(exchange), payload.length);
                exchange.getResponseBody().write(payload);
                exchange.close();
            });
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void close() {
            server.stop(0);
        }
    }

    private static Stub start(BodySource bodies) {
        try {
            return new Stub(bodies);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
