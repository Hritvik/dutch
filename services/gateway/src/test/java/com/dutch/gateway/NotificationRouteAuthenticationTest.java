package com.dutch.gateway;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "gateway.routes.notification-base-url=http://127.0.0.1:1",
        "gateway.routes.ledger-base-url=http://127.0.0.1:1"
})
@AutoConfigureMockMvc
class NotificationRouteAuthenticationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void an_authenticated_notification_request_reaches_routing_instead_of_denial() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/devices").with(jwt().jwt(jwt -> jwt.subject("member"))))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void an_authenticated_privacy_export_reaches_routing_instead_of_denial() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/export").with(jwt().jwt(jwt -> jwt.subject("member"))))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void an_authenticated_safety_report_reaches_routing_instead_of_denial() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/reports")
                        .with(jwt().jwt(jwt -> jwt.subject("member")))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"SPAM\",\"details\":\"text\"}"))
                .andExpect(status().isServiceUnavailable());
    }
}
