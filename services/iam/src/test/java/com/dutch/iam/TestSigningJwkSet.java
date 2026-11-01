package com.dutch.iam;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPairGenerator;
import java.util.UUID;

final class TestSigningJwkSet {
    private TestSigningJwkSet() {
    }

    static String create() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var keyPair = generator.generateKeyPair();
            var key = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
                    .privateKey(keyPair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            return new JWKSet(key).toString(false);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Test signing key generation failed.", exception);
        }
    }
}
