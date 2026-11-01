# Ledger write model and transaction boundaries

## Decision

The Obligation Ledger is an append-only PostgreSQL write model. Every accepted financial command runs as one short `SERIALIZABLE` transaction and produces immutable source records, exact Obligation Entries, synchronous rebuildable projections, an Activity record, a durable idempotency outcome, and outbox facts together. PostgreSQL serializable transactions guarantee committed work has an equivalent serial order, but applications must retry `SQLSTATE 40001`; Dutch therefore uses a bounded server retry and never silently downgrades to a weaker transaction. [PostgreSQL transaction isolation](https://www.postgresql.org/docs/current/transaction-iso.html)

This document is the Ledger's write-side design. The read/sync contract is intentionally deferred to **Design the Ledger read model and sync projections**.

## Model families

| Family | Records | Rule |
| --- | --- | --- |
| Scope and authority | `group`, `group_membership`, `direct_expense`, `ledger_member_projection` | Current authorization is evaluated locally; no raw email/phone reaches Ledger. |
| Immutable source | `expense`, `expense_revision`, `payer_contribution`, `participant_share`, `settlement`, `settlement_allocation`, `financial_correction` | Never update or delete financial evidence. A later revision/correction supersedes economic effect by new entries. |
| Immutable financial evidence | `obligation_entry`, `entry_reversal_link`, `allocation_snapshot` | Amounts are signed `BIGINT` minor units plus ISO currency and deterministic algorithm version. |
| Derived local state | `financial_stream`, `member_balance_projection`, `group_plan_projection`, `activity_projection` | Rebuildable and replaceable; never an independent financial source. |
| Reliability | `idempotency_outcome`, `outbox_event`, `inbox_event` | Part of the local transaction; never rely on FIFO delivery for correctness. |

An **Expense** and a **Settlement** are source records. An **Obligation Entry** is the only financial fact that changes a balance. A correction appends a new `financial_correction` action and entries; it does not mutate the corrected record.

### Scopes and financial streams

Separate a source's presentation scope from the balance stream it affects:

- A Group Expense and Group Settlement affect that Group/currency's `financial_stream`.
- A Direct Expense is a one-off source record. Its calculated bilateral entries affect one canonical direct-pair/currency `financial_stream` per pair created by its allocation; it does not create an ongoing Group-like workspace.
- A Direct Settlement chooses exactly one canonical direct-pair/currency stream.
- `Settle all` locks and applies immutable allocations to every selected Group and direct-pair stream; it is one parent Settlement, never a mutable collection of allocation commands.

Canonical direct-pair stream identity is `(min(member_a, member_b), max(member_a, member_b), currency)`. This preserves direct multi-person Expenses without inventing a lasting multi-person direct scope.

`financial_stream` is a durable lock/projection anchor with a unique natural key. It exists before any command that affects the stream commits. It carries a monotonic `stream_version`; it is not an editable balance.

## Immutable record shape

The Phase 0 migration must use `UUID` identifiers, `TIMESTAMPTZ` for server-recorded instants, `DATE` plus the agreed optional original local time/timezone fields for occurred evidence, `BIGINT` for money, and `VARCHAR(3)` ISO currency. No floating point financial column is allowed.

```text
Expense -> ExpenseRevision -> {PayerContribution, ParticipantShare}
                              -> ObligationEntry* -> Balance Projection*

Settlement -> SettlementAllocation* -> ObligationEntry* -> Balance Projection*

FinancialCorrection -> reverses one active source snapshot exactly
                    -> reversal ObligationEntry* + replacement source/entry snapshot*
                    -> Activity + Outbox
```

Each entry records: its source action/revision, balance-stream ID, debtor and creditor Member IDs, signed minor-unit amount semantics, currency, allocation-algorithm version, occurred evidence reference, actor, correlation ID, and server-recorded instant. Reversal entries retain the ID of the entry they reverse. A unique reversal-link constraint allows one reversal per original entry; restore/replacement creates a later source snapshot and new entries instead of deleting or reactivating an old entry.

### Database-enforced invariants

- Primary/foreign keys preserve links to source snapshots, Members, streams, and corrections. Financial source rows are `INSERT`-only for the runtime role; `UPDATE` and `DELETE` are revoked. Migration/restore credentials are separate from runtime credentials.
- `CHECK` constraints reject zero/nonpositive contributions or shares, invalid currency codes/scale, self-obligations, invalid direct-pair ordering, and invalid enum/state combinations.
- Unique constraints enforce one source revision sequence, stable participant ordering, one current idempotency identity, one direct stream natural key, and one exact reversal link per original entry.
- Deferred constraint triggers, run only at transaction commit, verify contribution/share totals equal the Expense total, each computed allocation set nets to zero, and each Settlement Allocation agrees with its parent currency/scope. The service still validates before persistence; database checks protect against a faulty write path.
- The normal Ledger runtime role may not alter entries, source snapshots, Activity, idempotency outcomes, or outbox facts after insertion. A correction is the only supported remedy. Projection tables are the sole mutable Ledger tables and are reconciled from entries.

## Command transaction

The Ledger write path uses explicit Spring JDBC/JdbcClient SQL and transaction templates rather than JPA dirty checking. It keeps SQL, lock order, transaction isolation, and affected row counts visible in code. Pure allocation/rounding remains side-effect-free Java code with property tests.

For every command, the service:

1. Authenticates the token's immutable Identity ID and resolves it through the local Member projection. It performs local scope/membership authorization; client-supplied actor or Member IDs are never trusted.
2. Canonicalizes alias Member IDs, validates the full command schema, calculates the complete desired source/entry snapshot, and determines every affected financial-stream key. A correction derives the old and replacement keys, then locks their union.
3. Begins a short `SERIALIZABLE` transaction. It acquires `financial_stream` row locks in stable lexicographic stream-key order, creating a direct-pair stream with a unique insert-if-absent then locking it. It then locks existing source roots in ascending UUID order. This order is mandatory for Expense corrections and multi-scope Settlement operations.
4. Inserts or locks the `(actor_identity_id, command_type, idempotency_key)` outcome row. The stored canonical request hash must match exactly. A duplicate with the same hash returns the stored terminal representation; a different hash returns the documented idempotency-key reuse error.
5. Re-reads authorization, expected versions, source state, and affected projections under the transaction. A stale expected version commits nothing and returns `409 stale_version` with the current snapshot. It never auto-merges financial fields.
6. Appends source/revision/correction records and exact Obligation Entries; applies only delta entries to the synchronous balance projection; increments every affected stream version; rebuilds the affected Group simplified-plan projection deterministically; and appends Activity.
7. Inserts the final idempotency result and one or more immutable outbox events in the same transaction, then commits. The response is sent only after commit.

The outbox relay later claims committed rows in small batches using `FOR UPDATE SKIP LOCKED`, whose locking clauses PostgreSQL supports. A relay crash after publish and before marking sent is expected; consumers deduplicate by stable event ID. [PostgreSQL `SELECT`](https://www.postgresql.org/docs/current/sql-select.html) [AWS transactional outbox pattern](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)

## Concurrency and retry contract

- Commands that affect the same stream serialize through ordered stream locks; commands on disjoint streams may proceed concurrently.
- `SERIALIZABLE` remains the final guarantee for predicate/read-write anomalies, including race conditions not covered by a single stream row. Retry the *entire* transaction on `40001` or deadlock `40P01`, with jittered bounded retry (three attempts total).
- Retry occurs only before a response is committed. If all attempts fail, return `503 concurrency_retry_exhausted` with `Retry-After`; the Local Draft remains reviewable and retry uses the same idempotency key. A serialization failure never becomes a financial rejection or success.
- Idempotency outcomes for accepted financial commands persist for the lifetime of the resulting financial record. Terminal nonfinancial validation outcomes may be retained under the API retention policy, but a hash mismatch always fails while a key is retained.
- The one documented semantic conflict is `409 stale_version`; authorization failures, validation failures, duplicate-key misuse, and temporary retry exhaustion remain distinct machine-readable errors. The API ticket defines exact envelopes/statuses.
- A settlement is deliberately not capped by the current balance. The locked transaction still recomputes positions and applies every stated allocation atomically, so an overpayment produces an explainable reversed balance rather than partial or stale scope effects.

## Projection and rebuild boundary

`member_balance_projection` stores only the fold of effective entries per `(financial_stream, member, currency)` and is modified in the same transaction as its entries. `group_plan_projection` is a deterministic, versioned derivative of Group positions. Activity is an append-only projection sourced by the same committed action.

Rebuild runs in a separate, observable worker against immutable source/entry records. It writes a versioned shadow projection, reconciles entry counts, signed totals, stream versions, and plan results, then atomically swaps the active projection pointer. Rebuild neither handles live commands nor changes source evidence. The later read-model ticket defines how projection-generation changes affect cursor sync.

## Required pre-code tests

Write these before Ledger workflow implementation:

1. Property tests: all valid multi-payer/equal/exact/percentage inputs allocate exactly; deterministic ties repeat; entry sets net to zero; reverse/replacement preserves a complete explainable history.
2. PostgreSQL integration tests: deferred constraints reject partial/imbalanced snapshots; runtime role cannot update/delete financial evidence; an entry rebuild equals the synchronous balance projection.
3. Concurrency integration tests: concurrent same-stream expenses/settlements serialize; same idempotency key returns one result; different hash fails; stale correction changes nothing; concurrent correction/Settlement and `Settle all` lock all required streams without deadlock; forced `40001` retries whole command.
4. Failure tests: crash before commit writes nothing; crash after commit but before response recovers the result through idempotency; delayed/duplicate outbox delivery does not duplicate consumer effects; projection rebuild is reconciled before activation.

## Operational limits

Record serializable-abort rate, lock-wait duration, deadlocks, retry-exhaustion count, transaction duration, projection-reconciliation variance, outbox age, and stream hot spots. Keep transactions free of network calls and user interaction. Reassess the locking granularity only with evidence that a stream is a throughput bottleneck; correctness does not depend on a broker message group.
