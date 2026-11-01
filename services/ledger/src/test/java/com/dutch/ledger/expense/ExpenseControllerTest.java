package com.dutch.ledger.expense;

import static com.dutch.ledger.TestGroupConfiguration.ADMIN;
import static com.dutch.ledger.TestGroupConfiguration.ADMIN_SUBJECT;
import static com.dutch.ledger.TestGroupConfiguration.MEMBER;
import static com.dutch.ledger.TestGroupConfiguration.MEMBER_SUBJECT;
import static com.dutch.ledger.TestGroupConfiguration.OUTSIDER_SUBJECT;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dutch.ledger.TestGroupConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
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
class ExpenseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void creates_an_expense_and_returns_its_exact_entries() throws Exception {
        String groupId = createGroup();

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses").with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "expense-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expenseJson(100, "INR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalMinorUnits").value(100))
                .andExpect(jsonPath("$.data.splitMethod").value("EQUAL"))
                .andExpect(jsonPath("$.data.payers[0].memberId").value(ADMIN.value()))
                .andExpect(jsonPath("$.data.shares[0].minorUnits").value(50))
                .andExpect(jsonPath("$.data.shares[1].minorUnits").value(50))
                .andExpect(jsonPath("$.data.entries[0].debtor").value(MEMBER.value()))
                .andExpect(jsonPath("$.data.entries[0].creditor").value(ADMIN.value()))
                .andExpect(jsonPath("$.data.entries[0].minorUnits").value(50));
    }

    @Test
    void replays_the_same_key_without_creating_a_second_expense() throws Exception {
        String groupId = createGroup();
        String body = expenseJson(100, "INR");

        String first = mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "expense-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "expense-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String firstId = JsonPath.read(first, "$.data.id");
        String secondId = JsonPath.read(second, "$.data.id");
        java.util.List<?> entries = JsonPath.read(second, "$.data.entries");
        assertThat(firstId).isEqualTo(secondId);
        assertThat(entries.toString()).contains("50");
    }

    @Test
    void rejects_idempotency_key_reuse_with_a_different_payload() throws Exception {
        String groupId = createGroup();
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses").with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "expense-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expenseJson(100, "INR")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses").with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "expense-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expenseJson(200, "INR")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("idempotency_conflict"));
    }

    @Test
    void a_non_member_cannot_create_an_expense() throws Exception {
        String groupId = createGroup();

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses").with(as(OUTSIDER_SUBJECT))
                        .header("Idempotency-Key", "expense-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expenseJson(100, "INR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void an_inactive_member_cannot_create_an_expense() throws Exception {
        String groupId = createGroup();
        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + MEMBER.value())
                        .with(as(ADMIN_SUBJECT))
                        .header("If-Match", "\"2\""))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses").with(as(MEMBER_SUBJECT))
                        .header("Idempotency-Key", "expense-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expenseJson(100, "INR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden"));
    }

    @Test
    void rejects_a_currency_that_differs_from_the_group() throws Exception {
        String groupId = createGroup();

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses").with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "expense-6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expenseJson(100, "USD")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }

    @Test
    void rejects_a_missing_idempotency_key() throws Exception {
        String groupId = createGroup();

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses").with(as(ADMIN_SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expenseJson(100, "INR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }

    private String createGroup() throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups").with(as(ADMIN_SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Flat 12\",\"currency\":\"INR\"}"))
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

    private static String expenseJson(long total, String currency) {
        return """
                {"description":"Dinner","totalMinorUnits":%d,"currency":"%s","splitMethod":"EQUAL",
                 "payers":[{"memberId":"%s","minorUnits":%d}],
                 "participants":["%s","%s"],
                 "occurredAt":"2026-11-01T00:00:00Z"}
                """.formatted(total, currency, ADMIN.value(), total, ADMIN.value(), MEMBER.value());
    }

    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject));
    }
}
