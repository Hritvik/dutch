# ADR 0009: Integrate IAM and UMS as independent provider repositories

## Status

Accepted

## Context

Dutch began with IAM and UMS prototypes inside its product monorepo. Their
requirements and provider implementation efforts now live in dedicated
repositories and serve portfolio-level responsibilities. Continuing to treat
the Dutch prototypes as canonical would create two sources of truth for
identity, profiles, and their contracts.

## Decision

Dutch owns Gateway, Ledger, Notification, Mobile, and Dutch-specific contracts.
It consumes pinned IAM and UMS provider contracts from their dedicated
repositories. The in-repository IAM and UMS modules are non-production
development adapters/reference evidence until retired or explicitly migrated;
new provider behavior is not added there. No provider availability is assumed
for Dutch domain implementation, but real onboarding and release acceptance
require provider-owned commitments and evidence.

## Consequences

Dutch can implement financial and mobile slices behind conformant development
adapters, while a versioned integration LLD prevents those adapters from
becoming an undeclared production identity system. This adds contract-pinning
and cross-repository compatibility work before integrated release.
