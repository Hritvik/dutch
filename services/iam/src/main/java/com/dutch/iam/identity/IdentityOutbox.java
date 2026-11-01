package com.dutch.iam.identity;

@FunctionalInterface
public interface IdentityOutbox {
    void append(IdentityVerifiedEvent event);
}
