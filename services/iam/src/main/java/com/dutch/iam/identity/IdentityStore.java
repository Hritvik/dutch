package com.dutch.iam.identity;

@FunctionalInterface
public interface IdentityStore {
    void save(Identity identity, ProtectedVerifiedIdentifier identifier);
}
