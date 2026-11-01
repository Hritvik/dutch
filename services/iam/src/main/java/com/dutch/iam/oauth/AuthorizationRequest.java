package com.dutch.iam.oauth;

public record AuthorizationRequest(
        String clientId, String redirectUri, String codeChallenge, String codeChallengeMethod, String state) {
}
