package com.dutch.gateway.routing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "gateway.routes.ums-base-url=http://127.0.0.1:1")
@AutoConfigureMockMvc
class GatewayUpstreamFailureTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    @WithMockUser
    void returns_the_published_unavailable_envelope_when_ums_is_down() throws Exception {
        mockMvc.perform(get("/api/v1/groups").header(CorrelationId.HEADER, "cor_0123456789012345"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(CorrelationId.HEADER, "cor_0123456789012345"))
                .andExpect(jsonPath("$.error.code").value("service_unavailable"))
                .andExpect(jsonPath("$.error.message").value("Service is temporarily unavailable"))
                .andExpect(jsonPath("$.error.correlationId").value("cor_0123456789012345"));
    }
}
