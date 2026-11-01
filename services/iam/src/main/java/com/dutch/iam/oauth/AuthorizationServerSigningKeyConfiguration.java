package com.dutch.iam.oauth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Signing material is deployment-owned. IAM never generates a production signing
 * key at startup: doing so would invalidate tokens after a restart and make JWKS
 * key continuity impossible.
 */
@Configuration(proxyBeanMethods = false)
public class AuthorizationServerSigningKeyConfiguration {

    @Bean
    JWKSource<SecurityContext> iamJwkSource(
            @Value("${iam.oauth.signing-jwk-set-json:}") String signingJwkSetJson) {
        if (signingJwkSetJson.isBlank()) {
            throw new IllegalStateException("IAM OAuth signing JWK set is required.");
        }
        try {
            JWKSet keySet = JWKSet.parse(signingJwkSetJson);
            if (keySet.getKeys().stream().noneMatch(key -> key.isPrivate())) {
                throw new IllegalStateException("IAM OAuth signing JWK set must contain private signing material.");
            }
            return new ImmutableJWKSet<>(keySet);
        } catch (java.text.ParseException exception) {
            throw new IllegalStateException("IAM OAuth signing JWK set is invalid.", exception);
        }
    }
}
