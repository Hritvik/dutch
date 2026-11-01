package com.dutch.iam.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcIdentityLifecycleRepositoryTest {
    @Test
    void serializes_the_complete_published_envelope_without_identifiers_or_tokens() {
        var jdbc = mock(JdbcTemplate.class);
        var repository = new JdbcIdentityLifecycleRepository(jdbc);
        UUID identityId = UUID.randomUUID();
        var event = new IdentityVerifiedEvent(UUID.randomUUID(), identityId, 1,
                Instant.parse("2026-08-10T00:00:00Z"), UUID.randomUUID(), UUID.randomUUID());

        repository.append(event);

        var arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), arguments.capture());
        String envelope = (String) arguments.getValue()[5];
        assertThat(envelope)
                .contains("\"eventType\":\"iam.identity.verified.v1\"", "\"schemaVersion\":1",
                        "\"producer\":\"iam\"", "\"aggregate\"", "\"correlationId\"",
                        "\"causationId\"", identityId.toString(), "\"securityState\":\"PROFILE_REQUIRED\"")
                .doesNotContain("email", "phone", "accessToken", "refreshToken");
    }
}
