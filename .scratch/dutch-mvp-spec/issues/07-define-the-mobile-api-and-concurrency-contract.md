Type: grilling
Status: resolved
Blocked by: 03, 04, 05, 06

# Define the mobile API and concurrency contract

## Question

What resource model, commands, queries, error envelope, pagination, idempotency, optimistic-concurrency behavior, versioning, and authorization context should the mobile-facing API expose across the critical MVP journeys?

## Answer

### Boundary and representation

- The mobile client calls a versioned REST/JSON API at `/api/v1` through the API Gateway. It presents an IAM bearer token; the Gateway and owning service derive the acting Identity from that verified token and never accept an actor Member identifier from the client.
- Successful responses use `{ "data": …, "meta": … }`; failures use `{ "error": { "code", "message", "details?" } }`. JSON properties are camelCase. `details` supplies field-level validation errors without exposing internal failures.
- Compatible additions (new optional fields, endpoints, or query parameters) stay in v1; removals, renames, type changes, or auth changes require a new path version. The OpenAPI document is the generated implementation contract.

### Resource queries and feeds

- The Gateway exposes screen-oriented read projections rather than raw Ledger evidence: Home, Groups, a Group overview/balances/expenses/Activity, Direct Expenses, and scoped or cross-scope Activity. The Obligation Entries remain internal Ledger records.
- Expenses and Activity are cursor-paginated in server-recorded-time order with a stable tie-breaker. Clients supply a bounded `limit`; MVP does not offer arbitrary server sorting.
- `GET /sync` accepts an opaque cursor and returns authorized changed resources, tombstones, and a `nextCursor`. The cursor is a signed/versioned server token scoped to the authenticated Member and holds only high-water positions for the relevant service projections, never financial or contact data.
- If a cursor cannot be honored, the API returns `410` with `sync_cursor_expired`; the app removes that checkpoint and completes a full authorized resync. A genuine loss of Group access is represented by tombstones so the client removes that scope's cached data.

### Financial commands

- Financial writes use explicit commands, not generic `PATCH` or physical `DELETE`: create a Group or Direct Expense; create an Expense revision; delete or restore an Expense; and record a Settlement (including its Group/direct/multi-scope allocations). Command responses return the authoritative updated resource, version, affected balance summaries, and resulting Activity entries.
- Mutations carry a client-generated `Idempotency-Key` header. The Ledger stores the canonical request hash and outcome for the financial record's lifetime; identical retries return the original result and a key reused with different input fails.
- Edits, deletion, and restore use the current resource version through `If-Match`/ETag semantics. A stale request returns `409` with `stale_version`, the current version, and the latest resource snapshot; no mutation is committed and the client must intentionally retry.

### Status and failure contract

- Creates return `201` and a `Location` header; successful action responses return `200` with the authoritative representation. Invalid JSON is `400`; valid JSON with semantically invalid financial input is `422` with field details; authentication and authorization use `401` and `403`; missing authorized resources use `404`.
- Transient Gateway, UMS, or Ledger unavailability returns `503` and `Retry-After`. The client retains its unsent command or draft and retries with the same idempotency key; it must not label the action rejected. Stable machine-readable error codes distinguish validation, authorization, state conflict, cursor recovery, and temporary availability.
