package com.dutch.ledger.safety;

import static com.dutch.ledger.TestGroupConfiguration.ADMIN;
import static com.dutch.ledger.TestGroupConfiguration.ADMIN_SUBJECT;
import static com.dutch.ledger.TestGroupConfiguration.MEMBER;
import static com.dutch.ledger.TestGroupConfiguration.MEMBER_SUBJECT;
import static com.dutch.ledger.TestGroupConfiguration.OUTSIDER_SUBJECT;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dutch.ledger.TestGroupConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
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
class PrivacyExportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void exports_only_the_callers_authorized_projection() throws Exception {
        String groupId = createSharedGroup();
        String directId = createDirectExpense();

        mockMvc.perform(get("/api/v1/privacy/export").with(as(ADMIN_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(ADMIN.value()))
                .andExpect(jsonPath("$.data.groups[*].id").value(org.hamcrest.Matchers.hasItem(groupId)))
                .andExpect(jsonPath("$.data.directExpenses[*].id")
                        .value(org.hamcrest.Matchers.hasItem(directId)))
                .andExpect(jsonPath("$.data.entries").doesNotExist());

        mockMvc.perform(get("/api/v1/privacy/export").with(as(OUTSIDER_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups.length()").value(0))
                .andExpect(jsonPath("$.data.directExpenses.length()").value(0));
    }

    private String createSharedGroup() throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups").with(as(ADMIN_SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Export flat\",\"currency\":\"INR\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String groupId = JsonPath.read(body, "$.data.id");
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members").with(as(ADMIN_SUBJECT))
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"" + MEMBER.value() + "\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members/" + MEMBER.value() + "/activate")
                        .with(as(MEMBER_SUBJECT))
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk());
        return groupId;
    }

    private String createDirectExpense() throws Exception {
        String body = mockMvc.perform(post("/api/v1/direct-expenses").with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "privacy-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Dinner","totalMinorUnits":100,"currency":"INR",
                                 "splitMethod":"EQUAL",
                                 "payers":[{"memberId":"%s","minorUnits":100}],
                                 "participants":["%s","%s"],
                                 "occurredAt":"2026-11-02T00:00:00Z"}
                                """.formatted(ADMIN.value(), ADMIN.value(), MEMBER.value())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject));
    }
}
