package com.dutch.iam.oauth;

public record AuthorizationCodeIssue(boolean accepted, String authorizationCode) {
    public static AuthorizationCodeIssue rejected() {
        return new AuthorizationCodeIssue(false, null);
    }
}
