package com.dutch.iam.oauth;

import java.util.Objects;

public record RegisteredMobileClient(String clientId, String redirectUri) {
    public RegisteredMobileClient {
        Objects.requireNonNull(clientId);
        Objects.requireNonNull(redirectUri);
    }
}
