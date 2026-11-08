package com.dutch.gateway.profile;

import java.util.Optional;

/** Canonical UMS profile facts the Dutch edge needs. */
public interface UmsProfileSource {

    /** The restricted-safe profile status for the bearer's own profile. */
    Status status(String bearerToken);

    /** The display projection for the bearer's own profile. */
    Optional<String> displayName(String bearerToken);

    record Status(String readiness, String lifecycle) {

        public boolean ready() {
            return "PROFILE_READY".equals(readiness) && "ACTIVE".equals(lifecycle);
        }
    }

    /** UMS is unavailable or answered in a shape Dutch cannot trust. */
    class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
