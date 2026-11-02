package com.dutch.ledger.direct;

import static com.dutch.ledger.TestGroupConfiguration.ADMIN;
import static com.dutch.ledger.TestGroupConfiguration.ADMIN_SUBJECT;
import static com.dutch.ledger.TestGroupConfiguration.MEMBER;
import static com.dutch.ledger.TestGroupConfiguration.MEMBER_SUBJECT;
import static com.dutch.ledger.TestGroupConfiguration.OUTSIDER_SUBJECT;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dutch.ledger.TestGroupConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.assertj.core.api.Assertions;
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
class DirectExpenseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void creates_a_direct_expense_with_its_canonical_pair_stream_and_activity() throws Exception {
        createSharedGroup();
        String body = mockMvc.perform(post("/api/v1/direct-expenses")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "direct-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(directJson(100)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalMinorUnits").value(100))
                .andExpect(jsonPath("$.data.entries[0].minorUnits").value(50))
                .andExpect(jsonPath("$.data.streamIds[0]").value(
                        "direct:" + ADMIN.value() + ":" + MEMBER.value() + ":INR"))
                .andReturn().getResponse().getContentAsString();
        String expenseId = JsonPath.read(body, "$.data.id");

        mockMvc.perform(get("/api/v1/direct-expenses/" + expenseId).with(as(MEMBER_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(expenseId));

        mockMvc.perform(get("/api/v1/direct-expenses/" + expenseId + "/activity")
                        .with(as(ADMIN_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.action == 'EXPENSE_CREATED')].targetId")
                        .value(contains(expenseId)));
    }

    @Test
    void replays_the_same_key_and_rejects_a_changed_payload() throws Exception {
        createSharedGroup();
        String key = "direct-replay-" + UUID.randomUUID();
        String first = mockMvc.perform(post("/api/v1/direct-expenses")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(directJson(100)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/v1/direct-expenses")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(directJson(100)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Assertions.assertThat((String) JsonPath.read(second, "$.data.id"))
                .isEqualTo(JsonPath.read(first, "$.data.id"));

        mockMvc.perform(post("/api/v1/direct-expenses")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(directJson(120)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("idempotency_conflict"));
    }

    @Test
    void a_caller_outside_the_expense_and_an_unrelated_reader_are_rejected() throws Exception {
        createSharedGroup();
        mockMvc.perform(post("/api/v1/direct-expenses")
                        .with(as(OUTSIDER_SUBJECT))
                        .header("Idempotency-Key", "direct-outsider-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(directJson(100)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden"));

        String body = mockMvc.perform(post("/api/v1/direct-expenses")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "direct-hidden-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(directJson(100)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String expenseId = JsonPath.read(body, "$.data.id");

        mockMvc.perform(get("/api/v1/direct-expenses/" + expenseId).with(as(OUTSIDER_SUBJECT)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/direct-expenses/" + expenseId + "/activity")
                        .with(as(OUTSIDER_SUBJECT)))
                .andExpect(status().isNotFound());
    }

    @Test
    void requires_the_command_key() throws Exception {
        mockMvc.perform(post("/api/v1/direct-expenses")
                        .with(as(ADMIN_SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(directJson(100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }

    private void createSharedGroup() throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups").with(as(ADMIN_SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Shared flat\",\"currency\":\"INR\"}"))
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
    }

    private static String directJson(long total) {
        return """
                {"description":"Dinner","totalMinorUnits":%d,"currency":"INR","splitMethod":"EQUAL",
                 "payers":[{"memberId":"%s","minorUnits":%d}],
                 "participants":["%s","%s"],
                 "occurredAt":"2026-11-02T00:00:00Z"}
                """.formatted(total, ADMIN.value(), total, ADMIN.value(), MEMBER.value());
    }

    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject));
    }
}
