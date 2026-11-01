package com.dutch.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CorrelationIdTest {

    @Test
    void echoes_a_value_that_matches_the_pinned_pattern() {
        assertThat(CorrelationId.resolve("cor_0123456789012345")).isEqualTo("cor_0123456789012345");
        assertThat(CorrelationId.resolve("4ed612d7-f73e-426c-a9f1-3eb756b5f11a"))
                .isEqualTo("4ed612d7-f73e-426c-a9f1-3eb756b5f11a");
    }

    @Test
    void regenerates_missing_unsafe_or_unbounded_values() {
        for (String value : new String[]{null, "", " leading", "cor has space", "cor_\"quote", "c".repeat(129)}) {
            String resolved = CorrelationId.resolve(value);
            assertThat(resolved).matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
            assertThat(resolved).startsWith("cor_");
        }
    }

    @Test
    void generates_distinct_values() {
        assertThat(CorrelationId.generate()).isNotEqualTo(CorrelationId.generate());
    }
}
