package com.dutch.gateway.routing;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
                .andExpect(header().exists(GatewayRoutingFilter.CORRELATION_HEADER))
                .andExpect(jsonPath("$.error.code").value("unauthenticated"));
    }

    @Test
    @WithMockUser
    void forwards_an_authenticated_profile_request_to_ums() throws Exception {
        mockMvc.perform(get("/api/v1/me/profile-state")
                        .header(GatewayRoutingFilter.CORRELATION_HEADER, "cor_0123456789012345"))
                .andExpect(status().isOk())
                .andExpect(header().string(GatewayRoutingFilter.CORRELATION_HEADER, "cor_0123456789012345"))
                .andExpect(jsonPath("$.data.state").value("PROFILE_REQUIRED"));

        assertThat(UMS.lastPath.get()).isEqualTo("/api/v1/me/profile-state");
        assertThat(UMS.lastCorrelation.get()).isEqualTo("cor_0123456789012345");
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
    void regenerates_an_unbounded_correlation_id_before_forwarding() throws Exception {
        String oversized = "c".repeat(129);
        mockMvc.perform(get("/api/v1/me").header(GatewayRoutingFilter.CORRELATION_HEADER, oversized))
                .andExpect(status().isOk())
                .andExpect(header().exists(GatewayRoutingFilter.CORRELATION_HEADER));

        assertThat(UMS.lastCorrelation.get()).isNotBlank();
        assertThat(UMS.lastCorrelation.get()).hasSizeLessThanOrEqualTo(128);
        assertThat(UMS.lastCorrelation.get()).isNotEqualTo(oversized);
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
        private final AtomicReference<String> lastCorrelation = new AtomicReference<>();

        private Stub(String body) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                lastPath.set(exchange.getRequestURI().toString());
                lastCorrelation.set(exchange.getRequestHeaders().getFirst(GatewayRoutingFilter.CORRELATION_HEADER));
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
