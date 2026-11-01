package com.dutch.ledger.settlement;

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
class SettlementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void records_a_group_settlement_and_voids_it_once() throws Exception {
        String groupId = createGroup();
        String key = "settlement-" + UUID.randomUUID();
        String body = mockMvc.perform(post("/api/v1/groups/" + groupId + "/settlements")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementJson(100)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.totalMinorUnits").value(100))
                .andExpect(jsonPath("$.data.allocations[0].scopeId")
                        .value("group:" + groupId + ":INR"))
                .andExpect(jsonPath("$.data.allocations[0].minorUnits").value(100))
                .andReturn().getResponse().getContentAsString();
        String settlementId = JsonPath.read(body, "$.data.id");

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/activity").with(as(ADMIN_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("SETTLEMENT_RECORDED"))
                .andExpect(jsonPath("$.data[0].targetId").value(settlementId))
                .andExpect(jsonPath("$.data[0].details.totalMinorUnits").value("100"));

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/settlements/" + settlementId + "/void")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "void-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VOIDED"));

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/settlements/" + settlementId + "/void")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "void-" + UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/activity").with(as(ADMIN_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].action").value("SETTLEMENT_VOIDED"))
                .andExpect(jsonPath("$.data[1].details.status").value("VOIDED"));
    }

    @Test
    void replays_a_settlement_key_and_rejects_a_changed_payload() throws Exception {
        String groupId = createGroup();
        String key = "settlement-replay-" + UUID.randomUUID();
        String path = "/api/v1/groups/" + groupId + "/settlements";

        String first = mockMvc.perform(post(path).with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementJson(100)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post(path).with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementJson(100)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Assertions.assertThat((String) JsonPath.read(second, "$.data.id"))
                .isEqualTo(JsonPath.read(first, "$.data.id"));

        mockMvc.perform(post(path).with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementJson(130)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("idempotency_conflict"));
    }

    @Test
    void handles_explicit_group_allocations_and_rejects_duplicate_or_foreign_scopes() throws Exception {
        String groupId = createGroup();
        String scope = "group:" + groupId + ":INR";

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/settlements")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "settlement-scope-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payerMemberId":"%s","payeeMemberId":"%s","totalMinorUnits":100,
                                 "currency":"INR","occurredAt":"2026-11-01T00:00:00Z",
                                 "allocations":[{"scopeId":"%s","minorUnits":100}]}
                                """.formatted(ADMIN.value(), MEMBER.value(), scope)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allocations.length()").value(1))
                .andExpect(jsonPath("$.data.allocations[0].scopeId").value(scope));

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/settlements")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "settlement-duplicate-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payerMemberId":"%s","payeeMemberId":"%s","totalMinorUnits":100,
                                 "currency":"INR","occurredAt":"2026-11-01T00:00:00Z",
                                 "allocations":[{"scopeId":"%s","minorUnits":60},
                                                {"scopeId":"%s","minorUnits":40}]}
                                """.formatted(ADMIN.value(), MEMBER.value(), scope, scope)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/settlements")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "settlement-foreign-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payerMemberId":"%s","payeeMemberId":"%s","totalMinorUnits":100,
                                 "currency":"INR","occurredAt":"2026-11-01T00:00:00Z",
                                 "allocations":[{"scopeId":"group:grp_999999999999999999:INR","minorUnits":100}]}
                                """.formatted(ADMIN.value(), MEMBER.value())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void a_non_member_cannot_record_a_settlement() throws Exception {
        String groupId = createGroup();

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/settlements")
                        .with(as(OUTSIDER_SUBJECT))
                        .header("Idempotency-Key", "settlement-outsider-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settlementJson(100)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
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

    private static String settlementJson(long total) {
        return """
                {"payerMemberId":"%s","payeeMemberId":"%s","totalMinorUnits":%d,
                 "currency":"INR","occurredAt":"2026-11-01T00:00:00Z"}
                """.formatted(ADMIN.value(), MEMBER.value(), total);
    }

    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject));
    }
}
