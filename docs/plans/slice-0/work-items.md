# Work Items — Dutch Slice 0 foundation

These work items implement the foundation slice defined in [verification and build sequence](../../architecture/verification-build-sequence.md). Work items are numbered `W-N` for cross-reference only. `Depends on` lines refer to other work items in this file.

## Shared reference artifacts

- [Solution structure](../../architecture/solution-structure.md) — module ownership and dependency rules for the monorepo.
- [API and event contracts](../../architecture/api-event-contracts.md) — versioned HTTP/event boundary and compatibility rules.
- [Runtime, observability, and delivery](../../architecture/runtime-observability-delivery.md) — environment, secrets, migration, and promotion expectations.
- [Verification and build sequence](../../architecture/verification-build-sequence.md) — Slice 0 exits and invariant-first test gates.

## W-1 — Establish the owned application workspace

**Summary.** Dutch needs a shape that keeps services independent as it grows. This work turns the initial scaffold into the agreed workspace. It makes each service and the mobile app visible as a separate owner. It does not deliver product behavior yet.

**Work to be done.**

- Create the agreed service, mobile, contract, infrastructure, and build-logic locations.
  - Apply the Gradle multi-project boundary from [solution structure](../../architecture/solution-structure.md#repository-layout); keep Expo tooling inside `apps/mobile`.
- Give each service a minimal runnable health surface and its own configuration boundary.
  - Gateway remains data-free; no service shares a domain module or database access path.
- Add automated module-boundary checks that reject forbidden service dependencies.

**Justification.** This is the foundation Slice 0 requires before any service can own a contract, migration, or test independently.

**References.**

- **Architecture** — [solution structure](../../architecture/solution-structure.md#ownership-and-allowed-dependencies) defines independent service ownership and the prohibition on shared domain code.
- **ADR** — [ADR 0001](../../adr/0001-modular-monorepo-with-independent-service-delivery.md) records why coordinated repository work must not weaken deployability or data ownership.
- **Build sequence** — [Slice 0](../../architecture/verification-build-sequence.md#slice-0--repository-contract-and-test-foundation) requires the multi-project and mobile package foundation.

**Acceptance criteria.**

- [x] Gateway, IAM, UMS, Ledger, Notification, and the mobile application each have an explicit owned module/package location.
- [x] The backend and mobile bootstrap independently with their native build tools.
- [x] Automated module-boundary tests reject a service dependency on another service's domain/persistence implementation.

**Depends on.** None.

## W-2 — Publish the first contract and fixture backbone

**Summary.** Teams need one shared language without sharing domain code. This work creates that language for HTTP and events. It makes responses and errors testable before service features exist. It also gives clients a safe place to integrate against fixtures.

**Work to be done.**

- Create the versioned OpenAPI, event-schema, and compatibility-fixture structure.
  - Start with common envelopes, correlation/error behavior, health readiness, and the IAM-to-UMS identity lifecycle event needed by the first vertical path.
- Validate request/response and producer/consumer examples automatically.
- Keep generated types as consumers of the source contracts, not alternative contract definitions.

**Justification.** Slice 0 explicitly requires versioned contract repositories and fixtures before service workflows are added.

**References.**

- **Architecture** — [contract artifacts and tests](../../architecture/api-event-contracts.md#contract-artifacts-and-tests) defines the source locations and compatibility expectations.
- **Event contract** — [event contract](../../architecture/api-event-contracts.md#event-contract) defines immutable envelope fields and consumer deduplication requirements.
- **Build sequence** — [Slice 0](../../architecture/verification-build-sequence.md#slice-0--repository-contract-and-test-foundation) requires contract fixture validation before capability work.

**Acceptance criteria.**

- [x] HTTP success/error fixtures validate against the versioned API contract.
- [x] A PII-free IAM identity lifecycle event validates for both producer and consumer examples.
- [x] A compatibility check fails when a fixture breaks the published contract.

**Depends on.** W-1.

## W-3 — Make service databases and migrations independently testable

**Summary.** Each service needs a database it can safely change without affecting another. This work establishes independent migration and test paths. It proves a clean environment can start from no data. It does not add financial tables or product workflows.

**Work to be done.**

- Give IAM, UMS, Ledger, and Notification distinct database, runtime-role, and migration boundaries.
  - Use isolated PostgreSQL Testcontainers and service-specific migration credentials in tests.
- Establish the migration runner and clean-database integration test convention.
- Add a minimal transactional outbox/inbox schema convention without publishing product events yet.

**Justification.** The architecture requires per-service migration ownership and clean PostgreSQL integration testing before feature data is introduced.

**References.**

- **Architecture** — [model families](../../architecture/ledger-write-model.md#model-families) identifies service-local reliability records and source/projection separation.
- **Runtime** — [delivery and migration flow](../../architecture/runtime-observability-delivery.md#delivery-and-migration-flow) requires service-specific compatible migrations.
- **ADR** — [ADR 0001](../../adr/0001-modular-monorepo-with-independent-service-delivery.md) establishes separate service database ownership.

**Acceptance criteria.**

- [x] Each owning service migrates a new PostgreSQL database without another service's credentials or schema.
- [x] Integration tests prove migrations run cleanly and rollback/cleanup leaves no cross-service state.
- [x] The runtime role cannot directly modify another service's database.

**Depends on.** W-1.

## W-4 — Establish reproducible local and delivery quality gates

**Summary.** Dutch needs feedback before a feature becomes expensive to correct. This work makes local development and delivery checks repeatable. It connects build, contract, database, security, and infrastructure evidence. It creates no production resources or user capability.

**Work to be done.**

- Provide the local dependency topology and deterministic fixture/reset conventions.
- Add CI checks for module boundaries, backend/mobile builds, migrations, contracts, lint/type checks, coverage, secret/dependency checks, and infrastructure validation.
- Create the OpenTofu environment/module skeleton and artifact-provenance record shape.
  - Keep environment secrets external and require redacted plans; do not apply cloud infrastructure in this item.

**Justification.** Slice 0 requires local development, CI quality gates, and infrastructure scaffolding before application slices can safely progress.

**References.**

- **Runtime** — [infrastructure and access control](../../architecture/runtime-observability-delivery.md#infrastructure-and-access-control) defines OpenTofu, isolated environments, and secret boundaries.
- **Runtime** — [required evidence before production promotion](../../architecture/runtime-observability-delivery.md#required-evidence-before-production-promotion) supplies the future evidence shape.
- **Build sequence** — [non-negotiable gates](../../architecture/verification-build-sequence.md#non-negotiable-gates) defines the required build, contract, and security checks.

**Acceptance criteria.**

- [x] A new contributor can run the documented local dependencies and reset deterministic fixtures without production access.
- [x] CI blocks a broken migration, contract fixture, module-boundary rule, or secret/dependency policy violation.
- [x] Infrastructure code validates and plans without embedding credentials or applying resources.

**Depends on.** W-1, W-2, W-3.

## W-5 — Deliver the authenticated Member bootstrap path

**Summary.** This is the first end-to-end Dutch path. A person can verify an identity and become a Member. The mobile app reaches a safe authenticated shell only after the profile is ready. It proves the services and client can work together without exposing private identifiers.

**Work to be done.**

- Implement the narrow IAM verification, profile-required, and UMS Member Profile creation path.
  - Use the published identity lifecycle contract and PII-free mapping event; defer Claim Offers, Placeholder matching, and Ledger financial access to later slices.
- Implement Gateway routing and the mobile external-browser PKCE/session bootstrap states.
- Demonstrate local event delivery and Ledger's read-only receipt of the initial Member mapping projection.
- Add device, contract, negative authorization, refresh/recovery, and outage tests for this path.

**Justification.** Slice 0 calls for the first thin vertical path from IAM verification through UMS profile creation to a mobile authenticated shell.

**References.**

- **Architecture** — [registration, profile, and claim flows](../../architecture/identity-membership-integration.md#registration-profile-and-claim-flows) defines the IAM → UMS → Ledger lifecycle without a distributed transaction.
- **Architecture** — [tokens, session, and authorization](../../architecture/identity-membership-integration.md#tokens-session-and-authorization) defines PKCE, token data minimization, and local service authorization.
- **API/event contract** — [external HTTP boundary](../../architecture/api-event-contracts.md#external-http-boundary) assigns IAM/UMS/Gateway route ownership.
- **Mobile** — [boot, session, and sync state machines](../../architecture/mobile-client-architecture.md#boot-session-and-sync-state-machines) defines authenticated, profile-required, and wipe states.
- **Build sequence** — [Slice 1](../../architecture/verification-build-sequence.md#slice-1--identity-to-member-onboarding) supplies the acceptance path this foundation enables.

**Acceptance criteria.**

- [ ] A deterministic iOS and Android development-build journey verifies a test identity, creates a Member Profile, and reaches the authenticated shell only after profile completion.
- [ ] UMS delay leaves the client in an honest profile-required state and prevents Ledger access without losing the authenticated session.
- [ ] Contract tests prove the identity lifecycle event contains no raw identifier, credential, or token data.
- [ ] Automated tests cover invalid authorization, PKCE/refresh failure, duplicate event delivery, and logout/account-wipe paths.

**Depends on.** W-1, W-2, W-3, W-4.
