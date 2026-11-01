package com.dutch.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Boots the production security configuration with a real JWKS-backed decoder
 * instead of a mock, so a missing or unparseable resource-server setup fails CI.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks"
})
class GatewayJwtConfigurationTest {

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void starts_with_a_real_jwks_decoder() {
        assertThat(jwtDecoder).isNotNull();
    }
}
