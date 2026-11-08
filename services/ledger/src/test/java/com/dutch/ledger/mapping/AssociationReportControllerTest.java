package com.dutch.ledger.mapping;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AssociationReportControllerTest {

    private static final String TOKEN = "test-service-token";

    private JdbcMemberAssociationReportConsumer consumer;
    private MockMvc mvc;

    @BeforeEach
    void prepare() {
        consumer = Mockito.mock(JdbcMemberAssociationReportConsumer.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new AssociationReportController(consumer, TOKEN))
                .build();
    }

    @Test
    void acknowledges_an_applied_report_only_after_the_consumer_returns() throws Exception {
        when(consumer.consume(any())).thenReturn(true);

        mvc.perform(post("/internal/ums/association-reports")
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report("PROFILE_READY", "ACTIVE", "\"generation\": 3,")))
                .andExpect(status().isAccepted());

        verify(consumer).consume(any(MemberAssociationReport.class));
    }

    @Test
    void a_duplicate_or_stale_report_is_acknowledged_without_being_applied() throws Exception {
        when(consumer.consume(any())).thenReturn(false);

        mvc.perform(post("/internal/ums/association-reports")
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report("PROFILE_READY", "ACTIVE", "\"generation\": 3,")))
                .andExpect(status().isOk());
    }

    @Test
    void a_wrong_service_token_is_denied_before_parsing() throws Exception {
        mvc.perform(post("/internal/ums/association-reports")
                        .header("X-Service-Token", "other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report("PROFILE_READY", "ACTIVE", "\"generation\": 3,")))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/internal/ums/association-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report("PROFILE_READY", "ACTIVE", "\"generation\": 3,")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(consumer);
    }

    @Test
    void a_conflicting_event_id_is_a_conflict() throws Exception {
        when(consumer.consume(any())).thenThrow(new MemberMappingConflictException());

        mvc.perform(post("/internal/ums/association-reports")
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report("PROFILE_READY", "ACTIVE", "\"generation\": 3,")))
                .andExpect(status().isConflict());
    }

    @Test
    void a_retired_generation_is_unprocessable() throws Exception {
        when(consumer.consume(any())).thenThrow(new RetiredGenerationException());

        mvc.perform(post("/internal/ums/association-reports")
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report("PROFILE_READY", "ACTIVE", "\"generation\": 3,")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void a_report_for_another_app_is_forbidden() throws Exception {
        when(consumer.consume(any())).thenThrow(new ForeignAssociationReportException());

        mvc.perform(post("/internal/ums/association-reports")
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report("PROFILE_READY", "ACTIVE", "\"generation\": 3,")))
                .andExpect(status().isForbidden());
    }

    @Test
    void a_malformed_report_is_rejected_without_reaching_the_consumer() throws Exception {
        mvc.perform(post("/internal/ums/association-reports")
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"ums.profile.association-report.v1\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/internal/ums/association-reports")
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report("PROFILE_READY", "ACTIVE", "\"generation\": \"three\",")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(consumer);
    }

    private static String report(String profileState, String lifecycleState, String liveFields) {
        return """
                {
                  "schemaVersion": 1,
                  "eventId": "evt_ingress_000000000001",
                  "eventType": "ums.profile.association-report.v1",
                  "producer": "ums",
                  "environment": "test",
                  "occurredAt": "2026-11-08T09:00:00Z",
                  "correlationId": "corr-ingress",
                  "causationId": "550e8400-e29b-41d4-a716-446655440000",
                  "aggregate": {
                    "type": "UMS_PROFILE_BINDING",
                    "id": "aps_ingress_0123456789012345",
                    "version": 7
                  },
                  "destination": { "appRegistrationId": "dutch-development" },
                  "payload": {
                    "appSubject": "aps_ingress_0123456789012345",
                    "profileState": "%s",
                    "lifecycleState": "%s",
                    %s
                    "profileRevision": "5",
                    "contentObligation": false
                  }
                }
                """.formatted(profileState, lifecycleState, liveFields);
    }
}
