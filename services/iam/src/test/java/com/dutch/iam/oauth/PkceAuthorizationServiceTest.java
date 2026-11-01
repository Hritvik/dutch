package com.dutch.iam.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PkceAuthorizationServiceTest {

    private static final String REDIRECT_URI = "dutch://oauth/callback";
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private final PkceAuthorizationService service = new PkceAuthorizationService(
            Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC),
            new RegisteredMobileClient("dutch-mobile", REDIRECT_URI));

    @Test
    void issues_and_redeems_an_authorization_code_only_for_exact_redirect_and_s256_verifier() {
        var issued = service.authorize(new AuthorizationRequest(
                "dutch-mobile", REDIRECT_URI, CHALLENGE, "S256", "opaque-client-state"));

        var outcome = service.redeem(issued.authorizationCode(), REDIRECT_URI, VERIFIER);

        assertThat(issued.accepted()).isTrue();
        assertThat(outcome).isEqualTo(CodeRedemption.ACCEPTED);
    }

    @Test
    void rejects_a_redirect_that_merely_has_the_same_prefix() {
        var issued = service.authorize(new AuthorizationRequest(
                "dutch-mobile", "dutch://oauth/callback/extra", CHALLENGE, "S256", "opaque-client-state"));

        assertThat(issued.accepted()).isFalse();
        assertThat(issued.authorizationCode()).isNull();
    }

    @Test
    void rejects_plain_pkce_and_revokes_a_code_after_reuse_or_bad_verifier() {
        var plain = service.authorize(new AuthorizationRequest(
                "dutch-mobile", REDIRECT_URI, VERIFIER, "plain", "opaque-client-state"));
        var issued = service.authorize(new AuthorizationRequest(
                "dutch-mobile", REDIRECT_URI, CHALLENGE, "S256", "opaque-client-state"));

        var invalidVerifier = service.redeem(issued.authorizationCode(), REDIRECT_URI, "wrong-verifier-value-that-is-long-enough-to-be-accepted");
        var reuse = service.redeem(issued.authorizationCode(), REDIRECT_URI, VERIFIER);

        assertThat(plain.accepted()).isFalse();
        assertThat(invalidVerifier).isEqualTo(CodeRedemption.REAUTHENTICATION_REQUIRED);
        assertThat(reuse).isEqualTo(CodeRedemption.REAUTHENTICATION_REQUIRED);
    }
}
