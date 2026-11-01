Type: grilling
Status: resolved

# Specify the Obligation Ledger model and invariants

## Question

What immutable records, calculations, rounding rules, currency constraints, correction semantics, and concurrency invariants must the Obligation Ledger enforce so expenses, splits, settlements, and balances remain exact, explainable, and reconstructable?

## Answer

### Source records and money

- The Ledger is the Dutch-only financial source of truth. It records immutable `Obligation Entries`; current balances and simplified settlement plans are derived projections, never editable balance rows. Midas's Accounting Ledger is independent.
- Every money amount is an ISO 4217 currency plus a signed 64-bit integer in that currency's minor unit. Floating-point values are forbidden. The currency-scale registry validates amount scale and arithmetic bounds.
- A Group has exactly one currency once it has financial history. A Direct Expense has its own selected currency. Currencies are never converted or combined.
- An Expense revision contains a complete immutable snapshot: scope, description, total, currency, occurred-at time, category and note; one or more exact Payer Contributions; the participant list; split method and resulting exact participant shares; and the stable ordering used for deterministic ties.
- Contributions are positive and sum exactly to the Expense total. Participant shares sum exactly to the same total. A member may pay, participate, both, or pay without taking a share. Duplicate contributions or participants and zero/self obligations are invalid after canonical-member alias resolution.
- Equal and percentage splits are converted to minor units deterministically. Percentage input is integer basis points totaling 10,000. Any remainder is assigned by the largest-remainder rule, breaking exact ties by the stored participant ordering; the resulting allocated minor-unit shares are stored.

### Expense obligations, corrections, and membership

- For each revision, a Member's net position is contribution minus share. Positive positions are creditors and negative positions are debtors. The Ledger deterministically matches the largest debtor to the largest creditor until all positions are zero; stable ordering and an allocation-algorithm version break ties. The resulting directed Obligation Entries are the immutable, explainable allocation of that single Expense—not Group-wide debt simplification.
- A financial edit appends an exact reversal of the active revision's entries and then the replacement revision plus its new entries, Activity Log record, and outbox event in one Ledger transaction. A metadata-only revision creates a new snapshot but need not reverse financial entries. Delete is a tombstone plus reversal; restore creates a later revision. Past system knowledge is never rewritten.
- Placeholder, Invited, and Active Group Members may be allocated in new expenses. An Inactive Member cannot be newly added, but can be retained, removed, or have their existing allocation corrected. Historical obligations remain intact.

### Balances and simplification

- A balance is the fold of effective entries within one scope and currency. Opposite bilateral entries net naturally; an effective reversal cancels its original entry exactly once.
- Within a Group and currency, the Ledger folds all member positions then derives a Simplified Settlement Plan by deterministically matching largest debtors and creditors. It is a recommendation/derived projection, not a history rewrite; it has at most member-count minus one suggested transfers and records its algorithm version.
- The Ledger maintains a synchronous, rebuildable balance projection in the same transaction that accepts an entry. This gives current, fast Home and Group reads; immutable entries remain authoritative and can rebuild the projection.

### Settlements

- A Settlement records an externally completed payment only; Dutch neither moves nor holds funds. There is no balance-based upper limit. A payment may reduce, clear, or reverse a balance.
- A Group settlement may be between any two Group Members. The simplified plan suggests the optimal recipient and amount, but never restricts a real payment. The plan is recalculated after settlement.
- At creation, a settlement can be allocated to a selected Group, the payer/payee's direct balance in a currency, or a `Settle all` action spanning applicable Group and direct scopes. It is never attached to one Direct Expense.
- `Settle all` clears every selected scope independently rather than silently netting offsets across scopes. It creates visible, linked Settlement Allocations so each Group and direct scope shows the portion that closed its obligation. Where opposite directions exist, the user sees the required payments in both directions.

### Integrity, concurrency, and recovery

- Each mutation carries a client-generated idempotency key scoped to acting Identity and command type. The Ledger atomically stores a canonical request hash and final result: a retry with the same hash returns that result; reuse with a different hash fails; concurrent duplicates serialize to one result.
- Edit, delete, restore, settlement, and membership-changing commands carry an expected version. A stale command commits nothing and returns the current version/state for resolution. Create commands validate current authorization, scope, membership, currency, contributions, and shares atomically.
- A Settlement rechecks current positions atomically even though it is not capped by them. Every accepted financial command writes its Activity Log/outbox record in the same transaction; downstream delivery is at-least-once and consumers are idempotent.
