package com.dutch.ledger.sync;

import static com.dutch.ledger.TestGroupConfiguration.ADMIN;
import static com.dutch.ledger.TestGroupConfiguration.ADMIN_SUBJECT;
import static com.dutch.ledger.TestGroupConfiguration.MEMBER;
import static com.dutch.ledger.TestGroupConfiguration.MEMBER_SUBJECT;
import static com.dutch.ledger.TestGroupConfiguration.OUTSIDER;
import static com.dutch.ledger.TestGroupConfiguration.OUTSIDER_SUBJECT;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dutch.ledger.TestGroupConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
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
class SyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SyncJournal journal;

    @Autowired
    private SyncCursorCodec codec;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void requires_a_cursor_then_pages_only_the_callers_changes() throws Exception {
        mockMvc.perform(get("/api/v1/ledger/sync").with(as(OUTSIDER_SUBJECT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("sync_cursor_expired"));

        String snapshot = mockMvc.perform(post("/api/v1/ledger/sync-snapshots")
                        .with(as(OUTSIDER_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.asOfSequence").value(0))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(snapshot, "$.data.token");

        String initial = mockMvc.perform(get("/api/v1/ledger/sync-snapshots/" + token)
                        .with(as(OUTSIDER_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes", hasSize(0)))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(initial, "$.data.cursor");

        journal.append(List.of(OUTSIDER), "EXPENSE", "exp_012345678901234567", "upsert", 1,
                Map.of("description", "Dinner"), "act_012345678901234567", Instant.now());
        long memberStart = journal.maxSequence(MEMBER);
        journal.append(List.of(MEMBER), "EXPENSE", "exp_112345678901234567", "upsert", 1,
                Map.of("description", "Lunch"), null, Instant.now());

        mockMvc.perform(get("/api/v1/ledger/sync").param("cursor", cursor).param("limit", "10")
                        .with(as(OUTSIDER_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes", hasSize(1)))
                .andExpect(jsonPath("$.data.changes[0].resourceId").value("exp_012345678901234567"))
                .andExpect(jsonPath("$.data.changes[0].payload.description").value("Dinner"));

        String memberCursor = codec.sign(MEMBER, memberStart);

        mockMvc.perform(get("/api/v1/ledger/sync").param("cursor", memberCursor)
                        .param("limit", "10").with(as(MEMBER_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes[*].resourceId")
                        .value(hasItem("exp_112345678901234567")));
    }

    @Test
    void a_snapshot_returns_the_latest_non_tombstone_resource() throws Exception {
        journal.append(List.of(ADMIN), "SERIES", "ser_012345678901234567", "upsert", 1,
                Map.of("status", "ACTIVE"), null, Instant.now());
        journal.append(List.of(ADMIN), "SERIES", "ser_012345678901234567", "upsert", 2,
                Map.of("status", "PAUSED"), null, Instant.now());
        journal.append(List.of(ADMIN), "SERIES", "ser_112345678901234567", "upsert", 1,
                Map.of("status", "ACTIVE"), null, Instant.now());
        journal.append(List.of(ADMIN), "SERIES", "ser_112345678901234567", "tombstone", 2,
                Map.of(), null, Instant.now());

        String snapshot = mockMvc.perform(post("/api/v1/ledger/sync-snapshots")
                        .with(as(ADMIN_SUBJECT)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(snapshot, "$.data.token");

        mockMvc.perform(get("/api/v1/ledger/sync-snapshots/" + token).with(as(ADMIN_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes[?(@.resourceId == 'ser_012345678901234567')].payload.status")
                        .value(contains("PAUSED")))
                .andExpect(jsonPath("$.data.changes[?(@.resourceId == 'ser_112345678901234567')]")
                        .isEmpty());
    }

    @Test
    void a_tampered_or_foreign_cursor_is_rejected_and_the_limit_is_bounded() throws Exception {
        String snapshot = mockMvc.perform(post("/api/v1/ledger/sync-snapshots")
                        .with(as(ADMIN_SUBJECT)))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(snapshot, "$.data.token");
        String page = mockMvc.perform(get("/api/v1/ledger/sync-snapshots/" + token)
                        .with(as(ADMIN_SUBJECT)))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page, "$.data.cursor");

        mockMvc.perform(get("/api/v1/ledger/sync").param("cursor", cursor + "x")
                        .with(as(ADMIN_SUBJECT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("sync_cursor_expired"));

        mockMvc.perform(get("/api/v1/ledger/sync").param("cursor", cursor)
                        .with(as(MEMBER_SUBJECT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("sync_cursor_expired"));

        mockMvc.perform(get("/api/v1/ledger/sync").param("cursor", cursor).param("limit", "500")
                        .with(as(ADMIN_SUBJECT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }

    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject));
    }
}
