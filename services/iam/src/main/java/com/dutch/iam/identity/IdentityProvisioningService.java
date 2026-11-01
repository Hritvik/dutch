package com.dutch.iam.identity;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class IdentityProvisioningService {
    private final IdentityStore store;
    private final IdentityOutbox outbox;
    private final IdentifierProtector protector;
    private final Clock clock;

    public IdentityProvisioningService(IdentityStore store, IdentityOutbox outbox,
            IdentifierProtector protector, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.outbox = Objects.requireNonNull(outbox);
        this.protector = Objects.requireNonNull(protector);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public void provisionVerified(UUID identityId, IdentifierType type, String rawIdentifier) {
        Objects.requireNonNull(identityId);
        Objects.requireNonNull(type);
        var now = clock.instant();
        var identity = new Identity(identityId, "PROFILE_REQUIRED", now, now);
        var identifier = new ProtectedVerifiedIdentifier(UUID.randomUUID(), identityId, type,
                protector.lookupDigest(type, rawIdentifier), protector.encrypt(type, rawIdentifier),
                protector.keyVersion(), 1, now);
        store.save(identity, identifier);
        outbox.append(new IdentityVerifiedEvent(UUID.randomUUID(), identityId, 1, now,
                UUID.randomUUID(), UUID.randomUUID()));
    }
}
