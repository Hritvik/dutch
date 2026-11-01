# Dutch MVP capability handoff

## Capability

Dutch lets registered and placeholder members record one-time and recurring shared costs, allocate one or more payer contributions exactly, understand per-currency balances in Groups or Direct Expenses, record externally completed settlements, and collaborate through immutable revisions and Activity. A single React Native/Expo client serves iOS and Android; IAM, UMS, Obligation Ledger, Notification, and Gateway remain independently owned services.

## Fixed constraints

- Dutch never moves or holds money. A Settlement records an external payment and may be uncapped; a `Settle all` action visibly allocates one action across Group and direct scopes.
- All money is ISO currency plus integer minor units. A Group has one currency after financial history; currencies are never converted or combined.
- An Expense has one or more Payer Contributions summing exactly to its total and participant shares summing exactly to the same total. Exact, equal, and basis-point percentage splits produce stored deterministic minor-unit allocations.
- Ledger entries, financial revisions, Activity, idempotency outcomes, and outbox facts are immutable. Current balances, simplified Group plans, sync views, and Activity projections are rebuildable derivatives.
- Active collaborators may correct Expense and Settlement data. Deletes/edits/restores are attributable revisions; stale writes never silently overwrite. Inactive members retain read-only Group history; Group Admins alone manage Group configuration/membership.
- Identity, contact identifiers, credentials, claims, and profile lifecycle belong to IAM/UMS. Ledger has no raw email/phone data. Verified identifier matching produces explicit Claim Offers, not silent account claims.
- Mobile reads may be cached; financial writes are explicit online submissions of Local Drafts after current-state review. Push is non-authoritative; Activity and authoritative refresh determine truth.
- AWS deployment is single-region, Multi-AZ production with service-owned PostgreSQL databases, transactional outboxes, SNS/SQS FIFO, Fargate, Valkey only for abuse controls, and S3/KMS only for secure exports/secrets.

## Non-goals

- Payment initiation/custody, receipt uploads, comments, currency conversion, social login, web client, native iOS/Android codebases, shared Midas Ledger/database, global active-active writes, and self-operated Kafka/Kubernetes.

## Actors and primary surfaces

| Actor | Surface | Outcome |
| --- | --- | --- |
| Unauthenticated person | IAM onboarding | Verifies email or E.164 phone, creates Identity and Member Profile, reviews Claim Offers/invitations. |
| Active Member | Home, Groups, Direct Expenses | Reads current per-currency balances, adds/corrects Expenses, records Settlements, explores evidence. |
| Group Admin | Group settings/members | Invites, removes, changes Admin role, preserves at least one Active Admin. |
| Inactive Member | Historical Group view | Reads past Expense, Settlement, Activity, and balances; cannot mutate or newly participate. |
| Notification worker/operator | Event/delivery/operations surfaces | Delivers best-effort alerts, handles retries/DLQ, and observes nonfinancial delivery health. |

## Build phases

### Phase 0 — contract and test foundation

Create service modules/boundaries, PostgreSQL migrations, OpenAPI v1 and event schema repositories, local compose/test infrastructure, fixture builders, correlation/idempotency middleware, and CI gates. Establish the integer-money, deterministic-rounding, canonical-ID, authorization, error-envelope, and version/ETag test helpers before application workflows.

Exit only when every service owns a separate database role/schema, migrations run in clean Testcontainers PostgreSQL instances, the Gateway rejects unauthenticated calls, and contract fixtures are versioned.

### Phase 1 — IAM and UMS identity capability

Implement verified email/phone registration, Identity-to-Member creation, profile-required recovery, identifier changes, Placeholder/Claim Offer lifecycle, canonicalization, security lock/deactivate/delete, and PII-minimized UMS-to-Ledger lifecycle events.

Exit only when claim races, identifier enumeration resistance, alias resolution, deletion/anonymization, IAM/UMS outage rules, and projected authorization tests pass.

### Phase 2 — Ledger core and Group capability

Implement Group/Membership administration; fixed Group currency; multi-payer Expense creation; equal/exact/percentage allocation; recurring Expense Series and reviewable Pending Occurrences; immutable Obligation Entries; balances; deterministic simplified Group plans; revisions/deletion/restoration; and scoped Settlements.

Exit only when property/invariant tests prove every accepted revision balances exactly, reversal is exact/once-only, rounding is deterministic, and no direct database edit can change financial evidence.

### Phase 3 — mobile-facing projections and collaboration

Implement Gateway REST v1, ETags/expected versions, idempotency outcomes, Home/Group/Direct/Activity projections, cursor pagination/sync, Direct Expense authority, Activity/History views, and multi-scope Settlement Allocations.

Exit only when contract tests prove typed success/error envelopes, duplicate retry recovery, stale-write comparison, cursor reset/tombstones, scope authorization, and Activity evidence links.

### Phase 4 — mobile client and honest offline experience

Implement React Native/Expo onboarding, Balance-First Home/Groups/Activity navigation, Expense/Settlement creation and review, SecureStore IAM material, SQLite Cached Projection/Local Drafts, offline/read states, conflict review, access-revocation wipe, accessibility, and selected Calm balance-first visual system.

Exit only when iOS and Android device E2E journeys demonstrate the approved interaction model, including stale/offline/uncertain-submission states without false financial success.

### Phase 5 — events, notifications, and security completion

Implement transactional outbox relays, SNS/SQS FIFO consumers/DLQs, Notification preferences/tokens/retries, privacy-preserving deep links, rate limits, step-up authentication, audit redaction, privacy export, deletion/anonymization, and security monitoring.

Exit only when at-least-once event replays are idempotent, invalid device tokens are removed, push failure leaves Ledger truth unchanged, and security/abuse tests pass.

### Phase 6 — production readiness and controlled release

Provision AWS environments/infrastructure as code; configure backups/restore tests, dashboards/alerts/runbooks, EAS development/preview/staging/production channels, runtime versions, store tracks, and staged release/rollback operations.

Exit only when the resilience objectives, staging device validation, compatible migration rehearsal, and release evidence described below pass.

## Acceptance journeys

1. A person verifies email or phone, sees a Claim Offer for a matching Placeholder, explicitly claims it, and reaches the preserved Group/financial history without raw identifier disclosure.
2. An Active Member creates a Group Expense with several payer contributions and exact/equal/percentage shares. The response, balance, Activity, and deterministic entries agree exactly in minor units.
3. A Group or Direct Recurring Expense Series creates one timezone-correct Pending Occurrence per due period. Review/submission produces a normal Expense; skip, pause, archive, template change, and missed periods never create debt automatically.
4. Any Active Group Member corrects a different creator’s Expense. A stale simultaneous edit returns the current revision and preserves the Local Draft; no financial field is auto-merged.
5. Deleting and restoring an Expense changes balances via immutable reversal/new revision. Deletion is visible in Activity but not ordinary Expense History; restored evidence is intelligible.
6. A Group simplified plan suggests transfers, yet a Member records an arbitrary uncapped payment between Group Members. The plan recomputes without rewriting history.
7. `Settle all` presents explicit allocations, closes each selected Group/direct scope independently, records parent/allocation Activity, and avoids allocation-level push spam.
8. An offline user reads a clearly timestamped Cached Projection and writes a Local Draft. Reconnection refreshes scope and requires review; a lost response is safely recovered with the same idempotency key.
9. Membership removal produces an access tombstone and cache wipe. Inactive membership instead preserves read-only historical visibility and settleable balances.
10. A delayed, duplicated, or failed push never changes balances; opening it authenticates, deep-links, and refreshes authoritative state.
11. Security lock, session revocation, identifier change, rate-limit/claim abuse, Privacy Export, final anonymization, and service outage paths preserve the stated privacy/availability rules.

## Test and quality gates

- Use red–green–refactor TDD. Each behavior starts with a failing test; no skipped tests in release candidates. Target at least 80% line/branch/function coverage overall and effectively complete branch/invariant coverage for money, allocation, authorization, idempotency, revisions, and migration code.
- Unit-test pure money/allocation/rounding/version functions and mobile view/draft state. Property-test allocation totals, deterministic ties, reversals, canonical aliases, and sequence permutations.
- Run service integration tests against PostgreSQL Testcontainers; cover migrations, transaction boundaries, outbox relay, idempotency races, authorization projections, and rebuilds. Use consumer/producer contract tests for OpenAPI and event schemas.
- Run iOS and Android device E2E tests for every acceptance journey. Maintain deterministic fixture APIs and resettable test environments; do not depend on production data or push timing.
- CI requires formatting/lint/type checks, unit/integration/contract suites, coverage threshold, secret/dependency/static analysis, migration compatibility check, and UI accessibility checks. Staging promotion additionally requires device E2E, queue/outbox/health checks, and release-owner approval.
- Production gates require current/previous runtime compatibility, additive API/event/migration review, backup-restore rehearsal, alert/runbook verification, privacy/security review, and a tested rollback/forward-repair plan.

## State-of-the-art safeguards to absorb

The [state-of-the-art decision assessment](state-of-art-decision-assessment.md) does not alter the settled architecture. Its findings are absorbed as the following mandatory implementation gates:

- **Before Phase 1:** publish the Ledger transaction/concurrency contract. It must define serializable-retry or locking behavior, idempotency-key races, exact reversal linkage, aggregate ordering, and the one documented `409 stale_version` convention. Add property and integration tests before Ledger workflow code.
- **Before Phase 3:** publish cursor/event contracts with identity/scope binding, retention/full-resync behavior, aggregate-version monotonicity, outbox/inbox deduplication, DLQ replay, and projection-rebuild reconciliation. FIFO transport is never treated as exactly-once correctness.
- **Before Phase 4 exit:** run moderated comprehension tests for a standard one-payer Expense, multi-payer Expense, simplified Group transfer, overpayment, and Settle all. The UI must show scope, before/after effect, and allocation summary before financial confirmation.
- **Before Phase 5 exit:** run physical-device security tests for SecureStore persistence/Android backup exclusion, account switch/logout/access-revocation wipe, external OAuth with S256 PKCE and exact redirects, refresh-family reuse, push receipt handling, and invalid-token removal.
- **Before Phase 6 promotion:** record OTA environment/channel/runtime/bundle provenance, run New Architecture/dependency diagnostics, rehearse database restore plus outbox reconciliation, and validate capacity for database connections, queue age, and FIFO message groups.

SQLite encryption is deliberately conditional, not a hidden prerequisite: add SQLCipher or application-level envelope encryption only if a launch threat/compliance assessment requires protection from device-level forensic extraction. Similarly, split the shared RDS instance into physical service instances only when the documented noisy-neighbour, recovery-isolation, compliance, or capacity triggers occur.

## Observability and release evidence

Every request/event carries a correlation ID. Dashboards distinguish Ledger correctness from async delivery and report API SLO/error/latency, stale/draft recovery, sync reset, outbox/queue/DLQ, database health, auth/claim abuse, export, and notification delivery. A release record ties Git commit, build/runtime/update IDs, API/event/algorithm versions, migrations, environment, test evidence, approver, and rollback owner together.

## Handoff

The MVP is ready for phased TDD implementation. Public launch remains subject to the counsel-reviewed India-first legal/support gate, performance targets, and comprehension evidence specified in the implementation tickets. Do not reopen settled product/architecture decisions outside a new decision ticket and a `CONTEXT.md` update.

## Capability coordination

The active cross-service responsibility map for Slice 1 is maintained in
[Identity-to-Member onboarding capability coordination](../capabilities/slice-1-identity-member-onboarding.yaml).
It distinguishes settled service boundaries from consumer expectations, provider-owned
commitments, and the implementation coordinator's responsibilities; it does not replace
the versioned API and event contracts.
