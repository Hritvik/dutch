package com.dutch.iam.identity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("development")
public final class DevelopmentVerifiedIdentity {
    public UUID identityId(String seed) {
        if (seed == null || !seed.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Development identity seed is invalid.");
        }
        return UUID.nameUUIDFromBytes(("dutch-development:" + seed).getBytes(StandardCharsets.UTF_8));
    }
}
