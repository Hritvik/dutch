Type: grilling
Status: resolved
Assignee: Codex

# Design the Ledger read model and sync projections

## Question

What service-owned read projections, cursor/change-feed mechanics, authorization filtering, cache invalidation semantics, and rebuild/recovery processes will provide fast, correct mobile reads without treating a local cache or asynchronous event stream as financial truth?

## Answer

Ledger owns rebuildable mobile projections and appends recipient-scoped, ordered change records in the same transaction that updates a projection. An opaque, signed Sync Cursor binds only the recipient feed, authorization epoch, last sequence, and format/expiry metadata; it contains no financial/contact/scope data. Per-recipient journal fan-out makes history-preserving Inactive membership, true-revocation tombstones, privacy-filtered Activity, and offline convergence explicit.

Incremental sync atomically applies resource versions, tombstones, and cursor in SQLite. Cursors/journal history retain for 90 days. A recipient-bound 15-minute snapshot with `asOfSequence`, followed by incremental catch-up, safely handles first sync/expiry and concurrent writes; incompatible projection contracts force the same full-resync route.

See [Ledger read and sync model](../../../docs/architecture/ledger-read-sync-model.md) and [ADR 0003](../../../docs/adr/0003-recipient-scoped-sync-journal.md). `CONTEXT.md` now defines Sync Cursor precisely.

## Workflow observations

- Existing requirements held the product rule for cursors; architecture work supplied the minimal stored checkpoint, journal audience, retention, and recovery proof. The future SDLC skill should explicitly test that every product promise has an operational mechanism.
- Privacy was best resolved at the projection/change-audience boundary, before mobile cache design, rather than added as a UI-only filter.
