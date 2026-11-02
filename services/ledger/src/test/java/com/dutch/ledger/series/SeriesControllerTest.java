package com.dutch.ledger.series;

import static com.dutch.ledger.TestGroupConfiguration.ADMIN;
import static com.dutch.ledger.TestGroupConfiguration.ADMIN_SUBJECT;
import static com.dutch.ledger.TestGroupConfiguration.MEMBER;
import static com.dutch.ledger.TestGroupConfiguration.MEMBER_SUBJECT;
import static com.dutch.ledger.TestGroupConfiguration.OUTSIDER_SUBJECT;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
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
class SeriesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void creates_generates_and_submits_a_group_series_once() throws Exception {
        String groupId = createGroup();
        String seriesId = createSeries(groupId);

        mockMvc.perform(get("/api/v1/groups/" + groupId + "/series").with(as(MEMBER_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id").value(hasItem(seriesId)));

        String generateKey = "generate-" + UUID.randomUUID();
        String occurrenceBody = mockMvc.perform(post("/api/v1/series/" + seriesId + "/occurrences")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", generateKey)
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.periodStart").value("2026-11-01"))
                .andReturn().getResponse().getContentAsString();
        String occurrenceId = JsonPath.read(occurrenceBody, "$.data.id");

        mockMvc.perform(get("/api/v1/series/" + seriesId).with(as(ADMIN_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.nextPeriodStart").value("2026-12-01"));

        mockMvc.perform(post("/api/v1/series/" + seriesId + "/occurrences")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "generate-stale-" + UUID.randomUUID())
                        .header("If-Match", "\"1\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("stale_version"));

        String submitKey = "submit-" + UUID.randomUUID();
        String first = mockMvc.perform(post("/api/v1/series/occurrences/" + occurrenceId + "/submit")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", submitKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.occurrence.status").value("SUBMITTED"))
                .andReturn().getResponse().getContentAsString();
        String expenseId = JsonPath.read(first, "$.data.expenseId");
        Assertions.assertThat((String) JsonPath.read(first, "$.data.occurrence.expenseId"))
                .isEqualTo(expenseId);

        String replay = mockMvc.perform(post("/api/v1/series/occurrences/" + occurrenceId + "/submit")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", submitKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Assertions.assertThat((String) JsonPath.read(replay, "$.data.expenseId")).isEqualTo(expenseId);

        mockMvc.perform(post("/api/v1/series/occurrences/" + occurrenceId + "/submit")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "submit-again-" + UUID.randomUUID()))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/series/" + seriesId + "/occurrences")
                        .with(as(ADMIN_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("SUBMITTED"));
    }

    @Test
    void skips_pauses_and_archives_with_version_guards() throws Exception {
        String groupId = createGroup();
        String seriesId = createSeries(groupId);
        String occurrenceId = generate(groupId, seriesId);

        mockMvc.perform(post("/api/v1/series/occurrences/" + occurrenceId + "/skip")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "skip-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SKIPPED"));

        mockMvc.perform(post("/api/v1/series/occurrences/" + occurrenceId + "/submit")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "skip-submit-" + UUID.randomUUID()))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/series/" + seriesId + "/status")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "pause-" + UUID.randomUUID())
                        .header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PAUSE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAUSED"));

        mockMvc.perform(post("/api/v1/series/" + seriesId + "/occurrences")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "paused-generate-" + UUID.randomUUID())
                        .header("If-Match", "\"3\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));

        mockMvc.perform(post("/api/v1/series/" + seriesId + "/status")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "resume-" + UUID.randomUUID())
                        .header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RESUME\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void a_direct_series_submits_a_normal_direct_expense() throws Exception {
        String body = mockMvc.perform(post("/api/v1/series")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "direct-series-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(directSeriesJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope.type").value("DIRECT"))
                .andReturn().getResponse().getContentAsString();
        String seriesId = JsonPath.read(body, "$.data.id");

        String occurrenceId = generateDirect(seriesId);
        String submitted = mockMvc.perform(post("/api/v1/series/occurrences/" + occurrenceId + "/submit")
                        .with(as(MEMBER_SUBJECT))
                        .header("Idempotency-Key", "direct-submit-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String expenseId = JsonPath.read(submitted, "$.data.expenseId");

        mockMvc.perform(get("/api/v1/direct-expenses/" + expenseId).with(as(MEMBER_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(expenseId));

        mockMvc.perform(get("/api/v1/series").with(as(MEMBER_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id").value(hasItem(seriesId)));
    }

    @Test
    void an_inactive_member_keeps_read_access_but_cannot_change_a_series() throws Exception {
        String groupId = createGroup();
        String seriesId = createSeries(groupId);
        String occurrenceId = generate(groupId, seriesId);

        String groupBody = mockMvc.perform(get("/api/v1/groups/" + groupId).with(as(ADMIN_SUBJECT)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Integer groupVersion = JsonPath.read(groupBody, "$.data.version");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/groups/" + groupId + "/members/" + MEMBER.value())
                        .with(as(ADMIN_SUBJECT))
                        .header("If-Match", "\"" + groupVersion + "\""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/series/" + seriesId).with(as(MEMBER_SUBJECT)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/series/" + seriesId + "/occurrences")
                        .with(as(MEMBER_SUBJECT))
                        .header("Idempotency-Key", "inactive-generate-" + UUID.randomUUID())
                        .header("If-Match", "\"2\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden"));

        mockMvc.perform(post("/api/v1/series/occurrences/" + occurrenceId + "/skip")
                        .with(as(MEMBER_SUBJECT))
                        .header("Idempotency-Key", "inactive-skip-" + UUID.randomUUID()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/series/" + seriesId + "/status")
                        .with(as(MEMBER_SUBJECT))
                        .header("Idempotency-Key", "inactive-status-" + UUID.randomUUID())
                        .header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PAUSE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void a_non_member_cannot_read_or_create_a_group_series() throws Exception {
        String groupId = createGroup();
        String seriesId = createSeries(groupId);

        mockMvc.perform(get("/api/v1/series/" + seriesId).with(as(OUTSIDER_SUBJECT)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/series")
                        .with(as(OUTSIDER_SUBJECT))
                        .header("Idempotency-Key", "outsider-series-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seriesJson(groupId)))
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

    private String createSeries(String groupId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/series")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "series-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seriesJson(groupId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private String generate(String groupId, String seriesId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/series/" + seriesId + "/occurrences")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "generate-" + UUID.randomUUID())
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private String generateDirect(String seriesId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/series/" + seriesId + "/occurrences")
                        .with(as(ADMIN_SUBJECT))
                        .header("Idempotency-Key", "generate-direct-" + UUID.randomUUID())
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private static String seriesJson(String groupId) {
        return """
                {"scope":{"type":"GROUP","groupId":"%s"},
                 "description":"Rent","totalMinorUnits":100,"currency":"INR","splitMethod":"EQUAL",
                 "payers":[{"memberId":"%s","minorUnits":100}],
                 "participants":[{"memberId":"%s"},{"memberId":"%s"}],
                 "cadence":"MONTHLY","dueDay":5,"timezone":"Asia/Kolkata",
                 "nextPeriodStart":"2026-11-01"}
                """.formatted(groupId, ADMIN.value(), ADMIN.value(), MEMBER.value());
    }

    private static String directSeriesJson() {
        return """
                {"scope":{"type":"DIRECT","memberIds":["%s","%s"]},
                 "description":"Rent","totalMinorUnits":100,"currency":"INR","splitMethod":"EQUAL",
                 "payers":[{"memberId":"%s","minorUnits":100}],
                 "participants":[{"memberId":"%s"},{"memberId":"%s"}],
                 "cadence":"MONTHLY","dueDay":5,"timezone":"Asia/Kolkata",
                 "nextPeriodStart":"2026-11-01"}
                """.formatted(ADMIN.value(), MEMBER.value(), ADMIN.value(), ADMIN.value(),
                MEMBER.value());
    }

    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject));
    }
}
