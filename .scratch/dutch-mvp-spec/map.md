Label: wayfinder:map

# Chart an implementation-ready Dutch MVP specification

## Destination

An implementation-ready MVP specification for Dutch: a cross-platform iOS/Android shared-expense app with settled product behavior, domain invariants, mobile flows, service boundaries, API contracts, security model, data ownership, deployment shape, and acceptance criteria, ready for phased TDD implementation.

## Notes

- Domain: shared expenses for registered and placeholder members.
- Consult `/Users/hritvikdinkar/repositories/dutch/CONTEXT.md` and use the `domain-modeling`, `grilling`, `research`, `prototype`, `security-review`, `api-design`, and `product-capability` skills where their ticket calls for them.
- Planning only: tickets resolve decisions and produce specification context; they do not implement the product.
- Client: one cross-platform mobile client for iOS and Android, online-first with cached reads and local drafts.
- Backend boundaries already fixed: IAM, UMS, Dutch-only Obligation Ledger, Notification, and an API gateway. Midas's Accounting Ledger remains independent.
- MVP product boundaries already fixed: groups and direct expenses; placeholder members claimable by verified email or E.164 phone; equal/exact/percentage splits; one or more payer contributions per Expense; one currency per group; externally completed settlements; Group debt simplification; collaborative expense CRUD with an append-only Activity Log; Admin-managed membership.

## Decisions so far

<!-- Resolved tickets are indexed here by name; the answer remains in the ticket. -->

- [Select the cross-platform mobile stack](issues/01-select-the-cross-platform-mobile-stack.md) — React Native with TypeScript and Expo development builds provides the fastest integrated iOS/Android MVP path while retaining native escape hatches.
- [Define service topology and data ownership](issues/03-define-service-topology-and-data-ownership.md) — Five independently deployable, database-owning boundaries combine synchronous mobile APIs with transactional-outbox events and service-local authorization, without distributed transactions.
- [Learn mobile information architecture and interaction-model best practices](issues/13-learn-mobile-information-architecture-and-interaction-model-best-practices.md) — Scope-led navigation, contextual actions, evidence-backed balances, honest state, auditable mutations, accessibility, and journey testing provide the criteria for Dutch's interaction model.
- [Define the mobile information architecture and core interaction model](issues/02-define-mobile-information-architecture.md) — A Balance-First structure uses Home, Groups, and Activity as stable destinations, with scope-aware creation, evidence-backed balances, auditable collaboration, contextual settlement, and honest offline recovery.
- [Define the identity and membership lifecycle contract](issues/04-define-identity-and-membership-lifecycle.md) — IAM Identities map one-to-one to canonical UMS Members through verified, explicit claims and versioned projections, while Ledger-owned membership states preserve bookkeeping, access control, outages, and immutable history without distributed transactions.
- [Specify the Obligation Ledger model and invariants](issues/05-specify-obligation-ledger-invariants.md) — Immutable minor-unit entries, multi-payer allocation, deterministic rounding and Group simplification, transparent multi-scope settlements, and atomic idempotent/versioned commands keep financial history exact and reconstructable.
- [Define collaborative editing and Activity Log semantics](issues/06-define-collaborative-editing-and-activity-history.md) — Authorized collaborators edit through immutable, version-checked revisions while an append-only, privacy-aware Activity Log makes every correction, membership change, and multi-scope settlement explainable.
- [Define the mobile API and concurrency contract](issues/07-define-the-mobile-api-and-concurrency-contract.md) — A versioned REST API provides scope-aware projections, explicit immutable financial commands, opaque cursor sync, durable idempotency, ETag conflict recovery, and honest failure states.
- [Define notification and Activity delivery](issues/08-define-notification-and-activity-delivery.md) — Activity is durable evidence, while privacy-preserving, preference-aware pushes are deduplicated best-effort entry points that never determine financial truth.
- [Define mobile cache, draft, and synchronization behavior](issues/09-define-mobile-cache-draft-and-sync-behavior.md) — A complete authorized read cache supports honest offline viewing, while explicit local drafts are refreshed, reviewed, and idempotently submitted only online.
- [Define the security and privacy control model](issues/10-define-the-security-and-privacy-control-model.md) — IAM-led short-lived sessions, local scope authorization, PII minimization, abuse controls, secure deletion/export, and audited least-privilege operations protect shared financial collaboration without custody.
- [Select persistence, messaging, and deployment topology](issues/11-select-persistence-messaging-and-deployment-topology.md) — Managed AWS PostgreSQL, transactional outboxes, FIFO SNS/SQS, scoped Valkey, S3/KMS, and Fargate provide correctness and recovery without self-operated infrastructure.
- [Define the mobile visual system and screen composition](issues/14-define-the-mobile-visual-system-and-screen-composition.md) — A calm balance-first visual system makes financial position, scope, evidence, and intentional actions legible across mobile states without turning notifications or decoration into financial truth.
- [Define operational resilience policy](issues/15-define-operational-resilience-policy.md) — A single-region, Multi-AZ MVP uses explicit availability/recovery objectives, tested encrypted backups, redacted observability, incident runbooks, and cost limits instead of premature multi-region complexity.
- [Define mobile release and environment-promotion model](issues/16-define-mobile-release-and-environment-promotion.md) — Isolated environments, Expo build/update channels, runtime-gated OTA promotion, additive API rollout, and forward-only financial changes enable safe iOS/Android releases.
- [Define contract and financial-history evolution policy](issues/17-define-contract-and-financial-history-evolution-policy.md) — Additive contracts, versioned events/projections, expand/contract migrations, and immutable stored allocation rules let Dutch evolve without reinterpreting past finances.
- [Define the implementation handoff and acceptance model](issues/12-define-the-implementation-handoff-and-acceptance-model.md) — Six vertical phases, acceptance journeys, TDD/contract/device tests, and operational release gates make the settled MVP ready for implementation.
- [Define Settlement correction and allocation-reversal lifecycle](issues/18-define-settlement-correction-and-allocation-reversal-lifecycle.md) — Parent-owned immutable corrections reverse and replace complete Settlement allocation snapshots; void and restore preserve evidence without ever recalculating past Settle all behavior.
- [Define Group and Direct-scope lifecycle](issues/19-define-group-and-direct-scope-lifecycle.md) — Empty Groups may be discarded, financial Groups archive with settleable evidence, and Direct Expenses retain historical participant access without becoming ongoing workspaces.
- [Define financial dates, currency, and input policy](issues/20-define-financial-dates-currency-and-input-policy.md) — User-entered date/time evidence, configured ISO currencies, no future financial records, and server-enforced plain-text/amount bounds make input exact and predictable.
- [Define recurring Expense series lifecycle](issues/23-define-recurring-expense-series-lifecycle.md) — Group and direct schedules generate timezone-pinned, reviewable pending items; only an explicitly submitted occurrence becomes an immutable Expense.
- [Define trust, dispute, and support operations](issues/21-define-trust-dispute-and-support-operations.md) — Private reports and safety blocks provide support/safety boundaries without automated balance changes, hidden financial overrides, or erased shared evidence.
- [Define launch-market, legal, scale, and measurement targets](issues/22-define-launch-market-legal-scale-and-measurement-targets.md) — India-first launch uses counsel-reviewed privacy/support duties, an 18+ no-custody scope, explicit capacity targets, and measurable financial-comprehension gates.

## Not yet specified


## Out of scope

- Native iOS and Android codebases maintained separately.
- In-app money movement, payment-provider integration, or custody of funds.
- Currency conversion, exchange-rate lookup, or combining balances across currencies.
- Receipt images, comments, location, and custom categories.
- Social login and a web client.
- Sharing a runtime Ledger service or database with Midas.
