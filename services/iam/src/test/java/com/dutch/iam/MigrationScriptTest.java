package com.dutch.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationScriptTest {

    @Test
    void ownsAnInitialReliabilityMigration() throws Exception {
        var migration = new String(getClass().getResourceAsStream(
                "/db/migration/V1__create_reliability_tables.sql").readAllBytes(), StandardCharsets.UTF_8);

        assertThat(migration).contains("CREATE TABLE outbox_event", "CREATE TABLE inbox_event");
    }
}
