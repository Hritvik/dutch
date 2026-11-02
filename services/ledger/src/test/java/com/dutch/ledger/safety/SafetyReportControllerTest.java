package com.dutch.ledger.safety;

import static com.dutch.ledger.TestGroupConfiguration.ADMIN_SUBJECT;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dutch.ledger.TestGroupConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
@AutoConfigureMockMvc
@Import(TestGroupConfiguration.class)
class SafetyReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void records_a_safety_report_without_echoing_its_details() throws Exception {
        mockMvc.perform(post("/api/v1/reports").with(as(ADMIN_SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"HARASSMENT","scopeType":"GROUP",
                                 "scopeId":"grp_012345678901234567","details":"Unsafe messages in the group."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportId").value(org.hamcrest.Matchers.startsWith("rep_")))
                .andExpect(jsonPath("$.data.status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.details").doesNotExist());
    }

    @Test
    void rejects_an_unknown_category_and_an_unauthenticated_report() throws Exception {
        mockMvc.perform(post("/api/v1/reports").with(as(ADMIN_SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"BORING\",\"details\":\"text\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));

        mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"SPAM\",\"details\":\"text\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthorized"));
    }

    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject));
    }
}
