package com.dutch.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IdentityLifecycleMigrationScriptTest {

    @Test
    void stores_only_protected_identifier_material_and_refresh_digests() throws Exception {
        var migration = new String(getClass().getResourceAsStream(
                "/db/migration/V2__create_identity_lifecycle_tables.sql").readAllBytes(), StandardCharsets.UTF_8);
        var durableState = new String(getClass().getResourceAsStream(
                "/db/migration/V3__create_durable_authorization_state.sql").readAllBytes(), StandardCharsets.UTF_8);

        assertThat(migration).contains("CREATE TABLE identity", "lookup_digest", "ciphertext", "refresh_token_digest");
        assertThat(migration).doesNotContain("email TEXT", "phone TEXT", "refresh_token TEXT");
        assertThat(durableState).contains("CREATE TABLE oauth_authorization_state", "CREATE TABLE iam_session", "current_token_digest");
        assertThat(durableState).contains("UPDATE refresh_token_family", "active = FALSE");
        assertThat(durableState).contains("refresh_token_family_active_digest_ck", "NOT active OR current_token_digest IS NOT NULL");
        assertThat(durableState).doesNotContain("ADD COLUMN current_token_digest BYTEA NOT NULL");
        assertThat(durableState).doesNotContain("access_token TEXT", "refresh_token TEXT", "email", "phone");
    }
}
