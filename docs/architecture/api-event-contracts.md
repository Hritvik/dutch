# Dutch API and event contracts

## Decision

Gateway exposes versioned REST/JSON routes under `/api/v1`. Each owning service supplies its OpenAPI source in `contracts/http`; Gateway routes but does not transform domain data or orchestrate workflows. Commands are explicit when their outcome is an immutable financial or lifecycle action. Internal services exchange immutable, versioned events described in `contracts/events` with JSON Schema and AsyncAPI.

All JSON uses camelCase. Success is `{ "data": ... }`; errors are `{ "error": { "code", "message", "details?", "correlationId" } }`. `correlationId` is safe for support lookup and never substitutes for a resource ID, idempotency key, or authorization decision.

## External HTTP boundary

| Owner | Mobile route family | Responsibilities |
| --- | --- | --- |
| IAM | `/api/v1/auth/*` | registration, verified email/phone, OIDC PKCE, refresh/revoke, recovery; no Ledger data |
| UMS | `/api/v1/me`, `/api/v1/claim-offers`, `/api/v1/members/*` | profile, claim-offer, placeholder/invitation-facing data; no credentials or balances |
| Ledger | `/api/v1/ledger/*` | Groups, Direct Expenses, Recurring Expense Series, balances, Activity/History, sync, and finance commands |
| Notification | `/api/v1/notifications/*` | device registration and preferences only |

Every authenticated request includes a bearer access token and `X-Correlation-Id` (Gateway creates one if absent). Services validate token issuer/audience/algorithm locally and make the final resource authorization decision. IDs in paths or bodies never choose the acting Member.

### Ledger reads

The initial OpenAPI contract contains the following projection routes:

```text
GET  /api/v1/ledger/home
GET  /api/v1/ledger/groups
POST /api/v1/ledger/groups
GET  /api/v1/ledger/groups/{groupId}
GET  /api/v1/ledger/groups/{groupId}/activity?cursor=&limit=
GET  /api/v1/ledger/expenses/{expenseId}
GET  /api/v1/ledger/direct-expenses?cursor=&limit=
GET  /api/v1/ledger/recurring-expense-series?scope=&cursor=&limit=
GET  /api/v1/ledger/recurring-expense-series/{seriesId}
GET  /api/v1/ledger/sync?cursor=&limit=
POST /api/v1/ledger/sync-snapshots
GET  /api/v1/ledger/sync-snapshots/{snapshotId}?cursor=&limit=
```

These return mobile projections and evidence navigation, not raw Obligation Entries or database tables. Feed/list cursors are opaque, recipient/scope authorized, and stable only for their documented projection contract. `limit` is allowlisted with a safe default and maximum; unknown filters/sorts are rejected rather than dynamically converted into SQL.

### Ledger commands

Each financial or mutable command has a specific intent endpoint. Every command validates a JSON schema at the boundary and applies service-local authorization again inside the Ledger transaction.

```text
POST /api/v1/ledger/groups
POST /api/v1/ledger/groups/{groupId}/members
POST /api/v1/ledger/groups/{groupId}/members/{memberId}/remove
POST /api/v1/ledger/groups/{groupId}/archive
POST /api/v1/ledger/expenses
POST /api/v1/ledger/expenses/{expenseId}/corrections
POST /api/v1/ledger/expenses/{expenseId}/delete
POST /api/v1/ledger/expenses/{expenseId}/restore
POST /api/v1/ledger/settlements
POST /api/v1/ledger/settlements/{settlementId}/corrections
POST /api/v1/ledger/settlements/{settlementId}/void
POST /api/v1/ledger/settlements/{settlementId}/restore
POST /api/v1/ledger/recurring-expense-series
POST /api/v1/ledger/recurring-expense-series/{seriesId}/pause
POST /api/v1/ledger/recurring-expense-series/{seriesId}/resume
POST /api/v1/ledger/recurring-expense-series/{seriesId}/end
POST /api/v1/ledger/pending-occurrences/{occurrenceId}/submit
POST /api/v1/ledger/pending-occurrences/{occurrenceId}/skip
POST /api/v1/ledger/settlement-previews
```

`settlement-previews` is read-only calculation and creates no financial record. `POST /settlements` accepts either one selected scope or a complete `settleAll` allocation set; the response always includes an explicit before/after allocation summary. No allocation endpoint exists, because an allocation cannot be independently edited.

## Command preconditions and idempotency

All state-changing commands require `Idempotency-Key`, a client-generated UUID. Corrections, deletion/restoration, membership changes, series changes, and any command with a prior resource require `If-Match: "<resourceVersion>"`; creations that do not replace a prior resource do not. The Ledger transaction retains the `(acting Identity, command type, key, canonical request hash)` outcome as specified in the write-model design.

| Outcome | HTTP | Contract behavior |
| --- | --- | --- |
| New accepted creation | `201` | `Location` plus authoritative projection/action response |
| New accepted action against existing record | `200` | authoritative parent/action response |
| Exact idempotent retry | original status | byte-equivalent stored `data` representation and correlation metadata |
| Same key, different canonical request | `409 idempotency_key_reused` | commits nothing |
| Stale expected resource version | `409 stale_version` | commits nothing; returns current permitted snapshot/version |
| Invalid schema/semantic invariant | `422 validation_error` | field-level safe details; draft remains editable |
| Missing/invalid token; forbidden resource | `401` / `403` | generic, no existence/privacy leak beyond authorized context |
| Invalid/expired sync cursor | `409 sync_cursor_expired` | full-sync guidance, no hidden resource detail |
| Temporary dependency/concurrency exhaustion | `503` | `Retry-After`; same idempotency key remains retryable |

Malformed JSON is `400`; throttling is `429` with `Retry-After`; unexpected faults are generic `500` responses. API versions support current and immediately previous mobile runtime per the settled evolution policy. Additive fields/endpoints are compatible; removals, changed meanings, or changed JSON types require a new version or explicitly gated feed generation.

## Event contract

Every produced event uses this immutable envelope:

```json
{
  "eventId": "uuid",
  "eventType": "ledger.expense.committed.v1",
  "schemaVersion": 1,
  "occurredAt": "2026-08-10T12:34:56Z",
  "producer": "ledger",
  "aggregate": { "type": "group", "id": "uuid", "version": 42 },
  "correlationId": "uuid",
  "causationId": "uuid",
  "payload": {}
}
```

`aggregate.version` is monotonic only within the named aggregate/stream. A consumer must not infer a global order. The producer assigns `eventId` as the broker deduplication ID and records it in its outbox transaction. Consumers atomically record processed event IDs with their local effect; duplicate delivery is normal and harmless.

Initial event families are:

| Producer | Events | Consumers |
| --- | --- | --- |
| IAM | `iam.identity.verified.v1`, `iam.identity.security-state-changed.v1`, `iam.identity.deleted.v1` | UMS |
| UMS | `ums.member.created.v1`, `ums.member.claim-offered.v1`, `ums.member.canonicalized.v1`, `ums.member.anonymized.v1` | Ledger, Notification where applicable |
| Ledger | `ledger.membership.changed.v1`, `ledger.expense.committed.v1`, `ledger.expense.corrected.v1`, `ledger.settlement.committed.v1`, `ledger.settlement.corrected.v1`, `ledger.recurring-occurrence.created.v1`, `ledger.activity.occurred.v1` | Notification and Ledger-owned workers |

Financial event payloads carry stable IDs, action/revision identity, affected stream/version, currency/minor-unit values where the consumer legitimately needs them, and no raw email/phone, credential, access token, or private Activity text. `ledger.activity.occurred.v1` carries recipient Member IDs, an activity/deep-link reference, action class, and privacy tier; Notification evaluates recipient preferences then builds generic push text. It must never use push delivery as financial confirmation.

Schemas evolve additively with defaults. Consumers ignore unknown fields and tolerate absent newer fields. An incompatible shape/meaning uses a new event type/version and, where required, controlled dual publish. Producers/consumers validate examples in both directions in CI; generated models are convenience code, never the source of truth.

## Contract artifacts and tests

```text
contracts/
  http/{gateway,iam,ums,ledger,notification}/openapi.yaml
  http/fixtures/{success,error,stale-version,idempotency}/
  events/{iam,ums,ledger}/json-schema/
  events/asyncapi.yaml
  events/fixtures/{producer,consumer}/
```

Phase 0 requires OpenAPI request/response validation, consumer-driven compatibility tests, JSON Schema validation, event duplicate/reorder tests, endpoint authorization/negative tests, idempotency replay tests, and a check that examples contain no raw identifiers/secrets. Gateway routing tests prove it does not compose domain writes or turn downstream errors into false success.

## Security controls

- Enforce TLS, token validation, schema/size limits, parameterized database access, input allowlists, per-Identity/IP rate limits, and redacted structured logs at the boundary.
- Do not log authorization headers, idempotency keys with payloads, cursor/snapshot tokens, financial request bodies, or raw identifiers. Never return stack traces or database constraint text.
- No external endpoint accepts a Member ID as authority. A resource ID may locate an authorized record; authorization is always resolved from verified Identity plus service-local state.
