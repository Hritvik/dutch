# Architecture verification and vertical build sequence

## Decision

Build Dutch through small vertical slices after a short foundation phase. Every slice starts with failing tests for its acceptance behavior, preserves the architecture's ownership/contract rules, and ends with evidence appropriate to its risk. Financial correctness, authorization, and recovery tests are never deferred behind UI polish or notification delivery.

## Non-negotiable gates

| Gate | Required evidence |
| --- | --- |
| Before a service feature | owning module/database/contract identified; threat and input boundary stated; RED tests written |
| Before a Ledger command | property invariants, PostgreSQL transaction/concurrency/idempotency tests, exact reversal/rebuild proof |
| Before a cross-service change | OpenAPI/event schema fixture, producer/consumer compatibility and duplicate/reorder tests |
| Before a mobile financial flow | iOS and Android device evidence for fresh, stale, offline, and lost-response states |
| Before staging | 80%+ aggregate line/branch/function coverage; effectively complete critical money/auth/migration branches; no skipped release tests |
| Before production | compatible migration rehearsal, restore/outbox/projection reconciliation, alert/runbook exercise, security/privacy review, runtime/update provenance |

Quality is not one final test phase. A failing acceptance, contract, or invariant test blocks the slice that introduced it; a notification/UI failure cannot be worked around by treating stale projections as authoritative.

## Delivery slices

### Slice 0 — repository, contract, and test foundation

Create the Gradle multi-project services, Expo app package, contract directories, database migration conventions, local dependency topology, CI quality gates, fixture builders, and correlation/error/idempotency helpers. Establish ArchUnit/module dependency tests so services cannot reach another service's repositories/migrations, and make each service start against its own Testcontainers PostgreSQL database.

Exit: a clean environment runs migrations, contract fixture validation, module-boundary tests, and empty service/mobile builds; the Gateway rejects unauthenticated Ledger routes.

### Slice 1 — identity-to-member onboarding

Implement IAM authorization-code/S256 PKCE flow, verified identifier lifecycle, UMS profile creation, local Ledger mapping projection, and the mobile auth/profile-required states. Include exact Identifier Reference/Claim Offer fixtures but defer financial access until mapping/profile gates are proven.

Exit: iOS/Android can authenticate with deterministic test identity, reach a Member profile, safely recover from UMS delay, and demonstrate token/refresh-family, claim-race, identifier-enumeration, and account-wipe tests.

### Slice 2 — Group and Membership foundation

Implement Group creation, Admin/membership lifecycle, local authorization, Group projection, and the mobile Group list/detail. No financial command is accepted until scope/membership checks are fully integrated.

Exit: Group Admin and collaborator permissions, Inactive historical access, no-PII display projection, membership event replay, and unauthorized resource non-enumeration tests pass on API and device.

### Slice 3 — Expense, entries, and balances

Implement integer-money primitives, multi-payer Expenses, equal/exact/percentage allocation, immutable entries, synchronous balance projection, Group simplified plan, and mobile Expense review/result. Start with pure property tests, then PostgreSQL integration and device confirmation.

Exit: allocation sums/deterministic ties/rebuild equality hold under generated input; concurrent duplicate/stale commands cannot create extra evidence; a device user completes the standard Expense journey and can inspect the exact result.

### Slice 4 — corrections, Settlements, and Activity

Implement Expense correction/delete/restore, Group Settlement, parent-owned `Settle all` allocations, Settlement correction/void/restore, and privacy-aware Activity/History. Ensure the mobile review shows scope and before/after consequences before submit.

Exit: reversal is exact/once-only, `Settle all` cannot mutate an allocation independently, stale correction preserves a Local Draft, and Activity explains every change without duplicate notification dependence.

### Slice 5 — Direct Expenses, Recurring Series, and sync/offline

Implement multi-person Direct Expense source records/direct-pair streams, recurring Series/Pending Occurrences, recipient-scoped projections, sync/full-resync, SQLite cache, Local Draft review, and access tombstones. Device tests cover offline read, uncertain submission, cursor expiry, and account/access wipe.

Exit: no Pending Occurrence creates debt without explicit submission; full/incremental sync converge under concurrent changes; cache/draft paths never show an unaccepted command as a balance.

### Slice 6 — outbox, notifications, privacy/support, and resilience

Implement outbox relays, FIFO consumers/DLQs, device preferences/delivery, privacy export/deletion/anonymization, safety/report flows, rate/abuse controls, and dashboards/runbooks. Notification is exercised as an entry point only; Activity refresh remains authoritative.

Exit: duplicate/replayed events are harmless, failed push does not affect finance, privacy boundaries/redaction pass, and DLQ/incident runbooks have been rehearsed.

### Slice 7 — production readiness and controlled launch

Consume an accepted production runtime (private networking, isolated restorable storage, FIFO delivery, secret injection, digest deployment) from a platform provider, plus scaling/cost limits, backup/restore, migration compatibility, EAS promotion, device acceptance, accessibility, performance, security, legal/support, and comprehension evidence. Launch only after staged release gates pass.

Exit: approved release record includes image/update/runtime/API/event/migration versions, tests, restore/outbox evidence, alert/runbook evidence, approver, and rollback/forward-repair owner.

## Verification architecture

| Concern | Primary verification |
| --- | --- |
| Service boundaries | Gradle/module dependency checks plus ArchUnit; separate database credentials in Testcontainers and AWS |
| Financial rules | Java unit/property tests; PostgreSQL deferred-constraint, serializable-retry, and rebuild integration tests |
| API/events | OpenAPI/JSON Schema validation, producer/consumer fixtures, compatibility, duplicate/out-of-order tests |
| IAM/privacy | token/PKCE/refresh/claim/authorization negative tests, secret/log-redaction checks, service-to-service scope tests |
| Mobile | Jest/React Native Testing Library, SQLite integration, generated HTTP fixtures, Maestro iOS/Android device E2E |
| Operations | Provider runtime plan/apply evidence, image/SBOM/dependency scan, migration rehearsal, restore/reconciliation, queue/DLQ and alert/runbook exercise |
| User understanding | moderated comprehension sessions for multi-payer, simplification, overpayment, and Settle all before public release |

Use a test pyramid with property/invariant tests at the base and resettable device journeys at the top. Tests must create their own data and never rely on shared state, production identities, real OTPs, or push timing. A regression produces a targeted test before its fix.

## Definition of done for a work item

1. Its behavior and scope trace to a settled requirement/architecture decision.
2. Tests were written red first and cover happy, invalid, authorization, stale/retry, and recovery paths applicable to the change.
3. Public/event contract changes are additive or carry compatibility/migration evidence.
4. Logs, metrics, rate limits, and error behavior respect data classification.
5. Module, dependency, migration, type/lint, coverage, and relevant device/contract checks pass.
6. The implementation does not introduce an unrecorded architecture decision; a hard-to-reverse trade-off adds an ADR.

## Continuous improvement loop for the future SDLC skill

At the end of every slice, record: the source requirement/ADR, planned vs actual boundaries, tests/evidence run, defects or decision reversals, user/comprehension feedback, and one process friction. The next slice reviews only the friction that has a proposed, reusable guardrail.

Promote a lesson into the SDLC skill only when it is observed in at least two slices or protects a high-severity financial/security failure. Keep the skill thin: route work to Wayfinder, research, domain modeling, TDD, security review, verification, and release workflows; do not copy their instructions. Version and forward-test any new guardrail against one realistic architecture and one implementation task before relying on it.

## Deferred (YAGNI)

- A general event-sourcing framework: immutable Ledger records/projections already meet the evidence need; introduce only if replay/version management becomes materially harder than the current model.
- Multi-region active-active, self-operated Kafka/Kubernetes, service mesh, separate physical RDS per service, and automated financial conflict merging: reconsider only at the stated scale/recovery/operational triggers.
- A large omnibus SDLC framework: create the thin orchestration skill next, from this completed pilot, not by importing an external workflow wholesale.
