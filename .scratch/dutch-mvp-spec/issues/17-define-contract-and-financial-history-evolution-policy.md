Type: grilling
Status: resolved
Blocked by: 07, 11, 16

# Define contract and financial-history evolution policy

## Question

How must Dutch evolve API schemas, events, database projections, rounding/allocation algorithms, and immutable financial history while old clients and consumers remain in use, including compatibility windows, backfills, and retirement criteria?

## Answer

### Compatibility contract

- `/api/v1` is additive-only: responses may gain optional fields; requests may gain optional fields/endpoints; clients ignore unknown response fields; servers supply documented defaults for omitted optional request fields. Removing/renaming a field, changing a type/meaning, making an optional field required, changing auth semantics, or changing URI semantics requires a new API version.
- The service supports the current and immediately previous production mobile runtime for at least 180 days after a documented deprecation. It measures active runtime/API use before retirement, gives in-app/update notice, and blocks a version only for a security or data-integrity emergency. API version retirement returns a stable `api_version_sunset` response with update guidance.
- Sync resources remain projection contracts, not Ledger internals. An incompatible sync shape increments the sync-feed version and returns `sync_cursor_expired`, causing the established authorized full-resync path; additive resource fields do not invalidate cursors.

### Events and storage evolution

- Every event retains its immutable ID, type, producer, aggregate ID, occurrence time, correlation ID, and schema version. Event schemas evolve additively with defaults; consumers ignore unknown fields and tolerate absent fields introduced after their version. Incompatible meaning or shape creates a new event type/version and, where needed, dual-publishes during migration. Events and outbox facts are never edited in place.
- Relational changes follow expand/contract: add compatible schema/read paths; deploy readers; backfill through idempotent, observable, throttled jobs; deploy writers/dual writes where needed; validate counts and invariants; wait for supported clients and consumers; then retire the old path. Each migration is reversible until the contract is intentionally retired, and is rehearsed on a restored production-like dataset.
- Rebuildable balances, Activity views, sync read models, and UMS projections may be versioned and rebuilt from authoritative facts. Rebuilds run separately from live writes, are reconciled against source totals, and are switched atomically only after validation. They never become an alternate financial source of truth.

### Immutable financial meaning

- Expense revisions store their resulting exact shares, Payer Contributions, Obligation Entries, currency-scale context, participant tie order, and Allocation Algorithm Version. Those results are never recalculated merely because code, a currency registry, or a matching algorithm later changes.
- A new rounding/matching algorithm is introduced under a new immutable version and applies only to newly created financial revisions. A financial edit first exactly reverses the prior version's entries, then creates a later revision with its own stored algorithm version; a metadata-only edit retains the existing financial result. The Activity Log identifies the new revision without rewriting prior evidence.
- Data-correction scripts may repair nonfinancial projections or append a governed, attributable financial correction command. They may not update/delete Ledger entries, idempotency outcomes, historical Activity, or settled algorithm results directly in the database.

### Governance and retirement

- Every breaking/public-contract, event, financial-algorithm, or migration change has an owner, compatibility assessment, rollout/rollback plan, test fixture, monitoring metric, and explicit retirement criterion. Contract tests cover current and previous supported clients; consumer-driven event tests cover producers/consumers; migration tests prove upgrade, backfill, rebuild, and rollback/forward-repair paths.
- Version/algorithm registries and compatibility matrices live beside the API/OpenAPI and event schemas. Release records tie each deployed client runtime, API/event version, migration set, and algorithm version to a Git commit and deployment, enabling reconstruction years later.
