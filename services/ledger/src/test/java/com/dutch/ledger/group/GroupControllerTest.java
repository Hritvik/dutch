package com.dutch.ledger.group;

import static com.dutch.ledger.TestGroupConfiguration.ADMIN;
import static com.dutch.ledger.TestGroupConfiguration.ADMIN_SUBJECT;
import static com.dutch.ledger.TestGroupConfiguration.MEMBER;
import static com.dutch.ledger.TestGroupConfiguration.MEMBER_SUBJECT;
import static com.dutch.ledger.TestGroupConfiguration.OUTSIDER;
import static com.dutch.ledger.TestGroupConfiguration.OUTSIDER_SUBJECT;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dutch.ledger.TestGroupConfiguration;
import com.dutch.ledger.web.CorrelationIdFilter;
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
class GroupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void rejects_an_unauthenticated_request_with_the_standard_envelope() throws Exception {
        mockMvc.perform(get("/api/v1/groups"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(CorrelationIdFilter.HEADER))
                .andExpect(jsonPath("$.error.code").value("unauthorized"));
    }

    @Test
    void creates_a_group_with_the_creator_as_admin() throws Exception {
        mockMvc.perform(post("/api/v1/groups").with(as(ADMIN_SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Flat 12\",\"currency\":\"INR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"))
                .andExpect(jsonPath("$.data.currency").value("INR"))
                .andExpect(jsonPath("$.data.memberships[0].memberId").value(ADMIN.value()))
                .andExpect(jsonPath("$.data.memberships[0].role").value("ADMIN"));
    }

    @Test
    void a_non_member_cannot_enumerate_the_group() throws Exception {
        String groupId = createGroup();

        mockMvc.perform(get("/api/v1/groups/" + groupId).with(as(OUTSIDER_SUBJECT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"))
                .andExpect(jsonPath("$.error.message").value("Resource was not found"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void a_non_member_activation_is_not_enumerable() throws Exception {
        String groupId = createGroup();

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members/" + MEMBER.value() + "/activate")
                        .with(as(OUTSIDER_SUBJECT))
                        .header("If-Match", "\"0\""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void a_non_member_self_activation_is_not_enumerable_before_the_version_check() throws Exception {
        String groupId = createGroup();

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members/" + OUTSIDER.value() + "/activate")
                        .with(as(OUTSIDER_SUBJECT))
                        .header("If-Match", "\"99\""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void a_missing_if_match_returns_the_published_error() throws Exception {
        String groupId = createGroup();

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members").with(as(ADMIN_SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"mem_312345678901234567\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }

    @Test
    void an_invited_member_activates_and_then_reads_but_cannot_invite() throws Exception {
        String groupId = createGroup();
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members").with(as(ADMIN_SUBJECT))
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"" + MEMBER.value() + "\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/groups/" + groupId).with(as(MEMBER_SUBJECT)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members/" + MEMBER.value() + "/activate")
                        .with(as(MEMBER_SUBJECT))
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/groups/" + groupId).with(as(MEMBER_SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(groupId));

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members").with(as(MEMBER_SUBJECT))
                        .header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"mem_312345678901234567\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden"));
    }

    @Test
    void a_removed_member_must_be_reinvited_before_reactivating() throws Exception {
        String groupId = createGroup();
        activateMember(groupId);
        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + MEMBER.value())
                        .with(as(ADMIN_SUBJECT))
                        .header("If-Match", "\"2\""))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members/" + MEMBER.value() + "/activate")
                        .with(as(MEMBER_SUBJECT))
                        .header("If-Match", "\"3\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflict"));

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members").with(as(ADMIN_SUBJECT))
                        .header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"" + MEMBER.value() + "\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberships[1].state").value("INVITED"));

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members/" + MEMBER.value() + "/activate")
                        .with(as(MEMBER_SUBJECT))
                        .header("If-Match", "\"4\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberships[1].state").value("ACTIVE"));
    }

    @Test
    void the_last_admin_cannot_step_down() throws Exception {
        String groupId = createGroup();

        mockMvc.perform(patch("/api/v1/groups/" + groupId + "/members/" + ADMIN.value())
                        .with(as(ADMIN_SUBJECT))
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("last_admin"));
    }

    @Test
    void an_archived_group_keeps_read_access_but_blocks_new_members() throws Exception {
        String groupId = createGroup();
        activateMember(groupId);

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/archive").with(as(ADMIN_SUBJECT))
                        .header("If-Match", "\"2\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ARCHIVED"));

        mockMvc.perform(get("/api/v1/groups/" + groupId).with(as(MEMBER_SUBJECT)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members").with(as(ADMIN_SUBJECT))
                        .header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"mem_312345678901234567\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void an_inactive_member_keeps_read_access_but_not_mutation() throws Exception {
        String groupId = createGroup();
        activateMember(groupId);

        mockMvc.perform(delete("/api/v1/groups/" + groupId + "/members/" + MEMBER.value())
                        .with(as(ADMIN_SUBJECT))
                        .header("If-Match", "\"2\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberships[1].state").value("INACTIVE"));

        mockMvc.perform(get("/api/v1/groups/" + groupId).with(as(MEMBER_SUBJECT)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members").with(as(MEMBER_SUBJECT))
                        .header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"mem_312345678901234567\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejects_a_stale_if_match_and_accepts_the_current_version() throws Exception {
        String groupId = createGroup();
        String first = "mem_312345678901234567";
        String second = "mem_412345678901234567";

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members").with(as(ADMIN_SUBJECT))
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"" + first + "\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members").with(as(ADMIN_SUBJECT))
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"" + second + "\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("stale_version"));

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members").with(as(ADMIN_SUBJECT))
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"" + second + "\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void rejects_an_invalid_if_match() throws Exception {
        String groupId = createGroup();

        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members").with(as(ADMIN_SUBJECT))
                        .header("If-Match", "not-a-version")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"mem_312345678901234567\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }

    @Test
    void rejects_invalid_group_input() throws Exception {
        mockMvc.perform(post("/api/v1/groups").with(as(ADMIN_SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Flat\",\"currency\":\"rupee\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }

    private String createGroup() throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups").with(as(ADMIN_SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Flat 12\",\"currency\":\"INR\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private void activateMember(String groupId) throws Exception {
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

    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject));
    }
}
