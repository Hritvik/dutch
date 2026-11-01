package com.dutch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MemberMappingMigrationScriptTest {

    @Test
    void owns_a_local_identity_member_authorization_projection() throws Exception {
        var migration = new String(getClass().getResourceAsStream(
                "/db/migration/V2__create_identity_member_mapping.sql").readAllBytes(), StandardCharsets.UTF_8);

        assertThat(migration).contains("CREATE TABLE identity_member_mapping", "identity_id", "member_id", "mapping_version");
        assertThat(migration).doesNotContain("email", "phone", "access_token", "refresh_token");
    }
}
