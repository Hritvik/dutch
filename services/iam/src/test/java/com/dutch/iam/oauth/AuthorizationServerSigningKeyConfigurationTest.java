package com.dutch.iam.oauth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPairGenerator;
import org.junit.jupiter.api.Test;

class AuthorizationServerSigningKeyConfigurationTest {

    private final AuthorizationServerSigningKeyConfiguration configuration =
            new AuthorizationServerSigningKeyConfiguration();

    @Test
    void refuses_to_start_without_deployment_owned_signing_material() {
        assertThatThrownBy(() -> configuration.iamJwkSource(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("IAM OAuth signing JWK set is required.");
    }

    @Test
    void refuses_a_jwk_set_that_cannot_sign_tokens() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var key = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) generator.generateKeyPair().getPublic())
                .keyID("public-only-test-key")
                .build();

        assertThatThrownBy(() -> configuration.iamJwkSource(new JWKSet(key).toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("IAM OAuth signing JWK set must contain private signing material.");
    }
}
