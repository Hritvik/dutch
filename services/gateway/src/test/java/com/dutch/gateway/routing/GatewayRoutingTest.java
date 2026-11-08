package com.dutch.gateway.routing;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class GatewayRoutingTest {

    private static final Stub UMS = start("{\"data\":{\"state\":\"PROFILE_REQUIRED\"}}");
    private static final Stub LEDGER = start("{\"data\":{\"scope\":\"home\"}}");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("gateway.routes.ums-base-url", UMS::baseUrl);
        registry.add("gateway.routes.ledger-base-url", LEDGER::baseUrl);
    }

    @AfterAll
    static void stop() {
        UMS.close();
        LEDGER.close();
    }

    @Test
    void rejects_an_unauthenticated_profile_request_with_the_published_envelope() throws Exception {
        mockMvc.perform(get("/api/v1/me/profile-state"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(CorrelationId.HEADER))
                .andExpect(jsonPath("$.error.code").value("unauthorized"))
                .andExpect(jsonPath("$.error.message").value("Authentication is required"));
    }

    @Test
    void accepts_a_bearer_token_through_the_resource_server() throws Exception {
        when(jwtDecoder.decode("valid-token")).thenReturn(Jwt.withTokenValue("valid-token")
                .header("alg", "none")
                .subject("aps_0123456789012345678901ab")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build());

        mockMvc.perform(get("/api/v1/ledger/home").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void preserves_method_query_and_body() throws Exception {
        mockMvc.perform(post("/api/v1/groups/anything?mode=full")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"a\":1}"))
                .andExpect(status().isOk());

        assertThat(LEDGER.lastMethod.get()).isEqualTo("POST");
        assertThat(LEDGER.lastQuery.get()).isEqualTo("mode=full");
        assertThat(LEDGER.lastBody.get()).isEqualTo("{\"a\":1}");
    }

    @Test
    @WithMockUser
    void routes_the_ledger_surface_to_the_ledger_service() throws Exception {
        mockMvc.perform(get("/api/v1/ledger/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope").value("home"));

        assertThat(LEDGER.lastPath.get()).isEqualTo("/api/v1/ledger/home");
    }

    @Test
    @WithMockUser
    void routes_the_group_surface_to_the_ledger_service() throws Exception {
        mockMvc.perform(get("/api/v1/groups"))
                .andExpect(status().isOk());

        assertThat(LEDGER.lastPath.get()).isEqualTo("/api/v1/groups");
    }

    @Test
    @WithMockUser
    void regenerates_an_unsafe_correlation_id_and_keeps_the_body_valid() throws Exception {
        mockMvc.perform(get("/api/v1/ledger/home").header(CorrelationId.HEADER, "bad\"value"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationId.HEADER,
                        org.hamcrest.Matchers.matchesPattern("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")))
                .andExpect(jsonPath("$.data.scope").exists());
    }

    @Test
    @WithMockUser
    void strips_headers_named_by_connection() throws Exception {
        mockMvc.perform(get("/api/v1/groups")
                        .header("Connection", "X-Secret")
                        .header("X-Secret", "leak"))
                .andExpect(status().isOk());

        assertThat(LEDGER.lastSecret.get()).isNull();
    }

    @Test
    @WithMockUser
    void denies_an_unknown_route_with_the_published_envelope() throws Exception {
        mockMvc.perform(get("/api/v1/unknown"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden"))
                .andExpect(jsonPath("$.error.message").value("Access is denied"));
    }

    private static Stub start(String body) {
        try {
            return new Stub(body);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start the upstream stub", exception);
        }
    }

    private static final class Stub {
        private final HttpServer server;
        private final AtomicReference<String> lastPath = new AtomicReference<>();
        private final AtomicReference<String> lastMethod = new AtomicReference<>();
        private final AtomicReference<String> lastQuery = new AtomicReference<>();
        private final AtomicReference<String> lastBody = new AtomicReference<>();
        private final AtomicReference<String> lastCorrelation = new AtomicReference<>();
        private final AtomicReference<String> lastSecret = new AtomicReference<>();

        private Stub(String body) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                lastPath.set(exchange.getRequestURI().getPath());
                lastMethod.set(exchange.getRequestMethod());
                lastQuery.set(exchange.getRequestURI().getQuery());
                lastBody.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
                lastCorrelation.set(exchange.getRequestHeaders().getFirst(CorrelationId.HEADER));
                lastSecret.set(exchange.getRequestHeaders().getFirst("X-Secret"));
                byte[] payload = body.getBytes(UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
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
}
