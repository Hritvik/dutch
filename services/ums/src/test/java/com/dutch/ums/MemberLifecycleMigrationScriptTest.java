package com.dutch.ums;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MemberLifecycleMigrationScriptTest {

    @Test
    void owns_member_profiles_by_identity_without_identifier_columns() throws Exception {
        var migration = new String(getClass().getResourceAsStream(
                "/db/migration/V2__create_member_profile_tables.sql").readAllBytes(), StandardCharsets.UTF_8);

        assertThat(migration).contains("CREATE TABLE member_profile", "identity_id", "member_id");
        assertThat(migration).doesNotContain("email", "phone", "access_token", "refresh_token");
    }
}
