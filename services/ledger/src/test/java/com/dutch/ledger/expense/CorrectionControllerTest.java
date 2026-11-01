package com.dutch.ledger.expense;

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
class CorrectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void corrects_an_expense_and_records_privacy_safe_activity() throws Exception {
        String groupId = createGroup();
        String expenseId = createExpense(groupId, 100);

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses/" + expenseId + "/corrections")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "correction-1")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacementJson(120)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(expenseId))
                .andExpect(jsonPath("$.data.revision").value(2))
                .andExpect(jsonPath("$.data.totalMinorUnits").value(120))
                .andExpect(jsonPath("$.data.entries[0].minorUnits").value(60));

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/activity").with(as(ADMIN_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("EXPENSE_CORRECTED"))
                .andExpect(jsonPath("$.data[0].details.fromRevision").value("1"))
                .andExpect(jsonPath("$.data[0].details.toRevision").value("2"))
                .andExpect(jsonPath("$.data[0].details.totalMinorUnits").value("120"));
    }

    @Test
    void rejects_a_stale_correction_without_changing_the_expense() throws Exception {
        String groupId = createGroup();
        String expenseId = createExpense(groupId, 100);
        String path = "/api/v1/groups/" + groupId + "/expenses/" + expenseId + "/corrections";

        mockMvc.perform(post(path).with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "correction-first")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacementJson(120)))
                .andExpect(status().isOk());

        mockMvc.perform(post(path).with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "correction-stale")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacementJson(140)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("stale_version"));

        mockMvc.perform(post(path).with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "correction-invalid-revision")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacementJson(140)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/activity").with(as(ADMIN_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].targetRevision").value(2));
    }

    @Test
    void replays_the_same_correction_key_and_rejects_a_different_payload() throws Exception {
        String groupId = createGroup();
        String expenseId = createExpense(groupId, 100);
        String path = "/api/v1/groups/" + groupId + "/expenses/" + expenseId + "/corrections";

        String first = mockMvc.perform(post(path).with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "correction-replay")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacementJson(120)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post(path).with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "correction-replay")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacementJson(120)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstId = JsonPath.read(first, "$.data.id");
        String secondId = JsonPath.read(second, "$.data.id");
        Integer secondRevision = JsonPath.read(second, "$.data.revision");
        org.assertj.core.api.Assertions.assertThat(firstId).isEqualTo(secondId);
        org.assertj.core.api.Assertions.assertThat(secondRevision).isEqualTo(2);

        mockMvc.perform(post(path).with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "correction-replay")
                        .header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacementJson(130)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("idempotency_conflict"));
    }

    @Test
    void a_non_member_cannot_correct_or_read_activity() throws Exception {
        String groupId = createGroup();
        String expenseId = createExpense(groupId, 100);

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses/" + expenseId + "/corrections")
                        .with(as(OUTSIDER_SUBJECT))
                        .header("Idempotency-Key", "correction-outsider")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacementJson(120)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/activity").with(as(OUTSIDER_SUBJECT)))
                .andExpect(status().isNotFound());
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

    private String createExpense(String groupId, long total) throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "expense-" + java.util.UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Dinner","totalMinorUnits":%d,"currency":"INR","splitMethod":"EQUAL",
                                 "payers":[{"memberId":"%s","minorUnits":%d}],
                                 "participants":["%s","%s"],
                                 "occurredAt":"2026-11-01T00:00:00Z"}
                                """.formatted(total, ADMIN.value(), total, ADMIN.value(), MEMBER.value())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private static String replacementJson(long total) {
        return """
                {"description":"Dinner corrected","totalMinorUnits":%d,"currency":"INR","splitMethod":"EQUAL",
                 "payers":[{"memberId":"%s","minorUnits":%d}],
                 "participants":["%s","%s"],
                 "occurredAt":"2026-11-01T00:00:00Z"}
                """.formatted(total, ADMIN.value(), total, ADMIN.value(), MEMBER.value());
    }

    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject));
    }
}
