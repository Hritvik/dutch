package com.dutch.iam.identity;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("development")
@RequestMapping("/internal/development/verified-identities")
final class DevelopmentVerifiedIdentityController {
    private final DevelopmentVerifiedIdentity identities;
    private final IdentityProvisioningService provisioning;

    DevelopmentVerifiedIdentityController(DevelopmentVerifiedIdentity identities, IdentityProvisioningService provisioning) {
        this.identities = identities;
        this.provisioning = provisioning;
    }

    @PostMapping("/{seed}")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@PathVariable String seed) {
        var identityId = identities.identityId(seed);
        try {
            provisioning.provisionVerified(identityId, IdentifierType.EMAIL, seed + "@development.invalid");
        } catch (DataIntegrityViolationException duplicateDeterministicIdentity) {
            // The deterministic development fixture is intentionally idempotent.
        }
        return Map.of("identityId", identityId, "state", "PROFILE_REQUIRED");
    }
}
