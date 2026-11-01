Type: grilling
Status: resolved
Assignee: Codex

# Design the external API and event contracts

## Question

What concrete REST resources/commands, request and response envelopes, versioning/error/idempotency conventions, and inter-service event contracts make the settled mobile, identity, financial, notification, and evolution requirements implementable and contract-testable?

## Answer

Gateway will route service-owned `/api/v1` REST/JSON APIs without composing domain writes. Ledger uses explicit intent commands for financial/lifecycle actions, screen-oriented reads, mandatory idempotency keys, `If-Match` resource preconditions, and one stable error taxonomy—including `409 stale_version`, `409 sync_cursor_expired`, and `503` retry exhaustion. `Settle all` is a parent Settlement command with complete allocations; no allocation mutation endpoint exists.

Service event contracts are immutable JSON Schema/AsyncAPI events with stable IDs, aggregate-local versions, schema version, correlation/causation IDs, outbox production, and idempotent consumer handling. Payloads deliberately exclude raw identifiers, credentials, and private Activity text; Notification receives only audience references/action class and renders best-effort generic pushes.

See [API and event contracts](../../../docs/architecture/api-event-contracts.md) and [ADR 0004](../../../docs/adr/0004-explicit-commands-and-versioned-contracts.md).

## Workflow observations

- Existing requirements were sufficient to define command intent, but the API phase made error/retry behavior and contract ownership testable. A future SDLC skill should require contract fixtures alongside every new service boundary.
- Separating projection reads from immutable evidence prevents accidental mobile dependency on Ledger tables and keeps external evolution manageable.
