Label: wayfinder:map

# Chart Dutch's implementation-ready solution design

## Destination

An implementation-ready solution design for Dutch's settled MVP: concrete service and client architecture, persistence and contracts, operational controls, and a verified phased build route that preserves the product and financial invariants already decided.

## Notes

- This is the architecture/design map, separate from the completed requirements map at `../dutch-mvp-spec/map.md`.
- Consult `CONTEXT.md`, the completed requirements tickets, and `docs/product/dutch-mvp-capability.md` before resolving a decision. Do not reopen product requirements unless implementation constraints expose a genuine conflict.
- Use `domain-modeling` when a canonical term changes; `api-design`, `backend-patterns`, `security-review`, `tdd-workflow`, and `verification-loop` where their decisions apply.
- Planning only: resolve architecture decisions and create durable design artifacts; do not begin product implementation here.
- Prefer small, composable workflow skills. This map is the pilot input for a future thin SDLC orchestration skill; record workflow friction in ticket resolutions.

## Decisions so far

<!-- Resolved architecture decisions are indexed here; detail lives in the ticket. -->

- [Select the solution structure](issues/01-select-the-solution-structure.md) — A modular monorepo keeps independently deployable, database-owning services and one Expo app coordinated without a shared domain kernel or cross-service database access.
- [Design the Ledger write model and transaction boundaries](issues/02-design-the-ledger-write-model-and-transaction-boundaries.md) — Serializable, ordered-stream transactions append immutable evidence, projections, idempotency results, and outbox facts together; retries make contention explicit rather than unsafe.
- [Design the Ledger read model and sync projections](issues/03-design-the-ledger-read-model-and-sync-projections.md) — Recipient-scoped, versioned projection changes and opaque cursor checkpoints provide privacy-safe incremental sync, explicit tombstones, and a convergent full-resync path.
- [Design the external API and event contracts](issues/04-design-the-external-api-and-event-contracts.md) — Explicit, idempotent financial commands and screen-oriented reads share versioned HTTP/event contracts while preserving stale-state recovery, privacy, and at-least-once safety.
- [Design identity and membership integration](issues/05-design-identity-and-membership-integration.md) — IAM vaults identifiers and credentials while UMS owns explicit claims/canonical Members; Ledger authorizes locally from PII-free projections and current Membership.
- [Design the mobile client architecture](issues/06-design-the-mobile-client-architecture.md) — Native Expo navigation, SQLite projections, SecureStore sessions, single-flight sync, and explicit Local Draft review provide a consistent and honest iOS/Android client.
- [Design runtime topology, observability, and delivery](issues/07-design-runtime-topology-observability-and-delivery.md) — A managed single-region AWS runtime uses isolated environments, per-service ownership, evidence-gated promotion, and rehearsed recovery without premature global/distributed operations.
- [Design the architecture verification and build sequence](issues/08-design-the-architecture-verification-and-build-sequence.md) — Invariant-first, red-first vertical slices verify contracts, devices, recovery, and releases at the point each risk is introduced, while capturing evidence to improve the delivery workflow.

## Not yet specified

- The exact module/package layouts, table/column definitions, OpenAPI/AsyncAPI shapes, and IaC implementation will be derived only after their governing architecture decisions are resolved.
- The vertical-slice order may need refinement after the write model, contract boundaries, and mobile architecture are jointly specified.

## Out of scope

- Reopening settled MVP product behavior without an implementation-discovered conflict.
- Installing or adopting an external SDLC framework that overwrites project conventions or tool configuration.
- Implementing the Dutch application itself.
