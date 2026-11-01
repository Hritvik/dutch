package com.dutch.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.dutch.gateway.routing.CorrelationId;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class LedgerRouteAuthenticationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void rejects_an_unauthenticated_ledger_request_with_the_published_error_envelope() throws Exception {
        mockMvc.perform(get("/api/v1/ledger/home"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(CorrelationId.HEADER))
                .andExpect(jsonPath("$.error.code").value("unauthorized"))
                .andExpect(jsonPath("$.error.message").value("Authentication is required"))
                .andExpect(jsonPath("$.error.correlationId").exists());
    }

    @Test
    void preserves_a_safe_client_correlation_id_on_rejection() throws Exception {
        mockMvc.perform(get("/api/v1/ledger/home").header(CorrelationId.HEADER, "cor_4ed612d7f73e426ca9f13eb756b5f11a"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(CorrelationId.HEADER, "cor_4ed612d7f73e426ca9f13eb756b5f11a"))
                .andExpect(jsonPath("$.error.correlationId").value("cor_4ed612d7f73e426ca9f13eb756b5f11a"));
    }
}
