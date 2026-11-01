Type: grilling
Status: resolved
Assignee: Codex

# Design the Ledger write model and transaction boundaries

## Question

What PostgreSQL aggregates, immutable source records, projections, constraints, locking/isolation approach, and transaction boundaries will enforce Dutch's financial invariants, idempotency, revision/reversal lifecycle, and rebuildability?

## Answer

The Ledger will use append-only PostgreSQL source/entry records and a short `SERIALIZABLE` transaction for every accepted financial command. Durable, ordered financial-stream row locks serialize overlapping Group/direct-pair scopes while serializable retry protects remaining read/write anomalies. Each commit contains source/revision/correction evidence, exact entries, synchronous rebuildable balance/plan/activity projections, durable idempotency result, and outbox facts.

The design differentiates one-off Direct Expense source records from canonical direct-pair/currency balance streams, allowing multi-person Direct Expenses without creating an ongoing direct workspace. Settlement correction/void/restore always append reversal/replacement evidence. Runtime database permissions make financial evidence insert-only; projections alone are mutable.

See [Ledger write model and transaction boundaries](../../../docs/architecture/ledger-write-model.md) and [ADR 0002](../../../docs/adr/0002-serializable-append-only-ledger-writes.md). The document also sets Phase 0's required property, PostgreSQL, concurrency, crash-recovery, and outbox tests.

## Workflow observations

- Requirements-level words such as "immutable" became testable only when attached to append-only permissions, reversal-link constraints, and a rebuild protocol. A future SDLC skill should require each architectural invariant to name its enforcing mechanism and test type.
- The architecture phase benefited from a small official-source check rather than importing an external framework; retain this as a research trigger for storage/concurrency decisions.
