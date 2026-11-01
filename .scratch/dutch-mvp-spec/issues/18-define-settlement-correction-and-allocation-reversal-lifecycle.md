Type: grilling
Status: resolved
Blocked by: 05, 06, 07

# Define Settlement correction and allocation-reversal lifecycle

## Question

How are single-scope and multi-scope Settlements edited, deleted, restored, or otherwise corrected without rewriting immutable evidence; which Settlement fields may change; how are prior Settlement Allocations reversed and replacement allocations created; and how do balances, Activity, sync, idempotency, and user-facing evidence explain that correction?

## Answer

### Parent-owned immutable correction

- A Settlement and every Settlement Allocation are immutable. A scope-level allocation is navigable evidence, never an independently editable record. Selecting correction from an allocation opens its parent Settlement, highlights that allocation, and explains that the complete recorded settlement will be corrected.
- Dutch never presents casual Edit or Delete for a recorded Settlement. The available actions are **Correct settlement**, **Void settlement**, and **Restore settlement**. Each uses the parent Settlement's expected version and idempotency key; a concurrent/stale correction commits nothing and returns the current parent evidence.
- `Correct settlement` runs atomically: it exactly reverses every active original allocation/Obligation Entry, then records a later immutable Settlement Revision with the reviewed replacement payment and allocation snapshot, Activity/outbox facts, authoritative projections, and response. No intermediate reopened balance is observable.

### Correction behavior and authorization

- The correction screen pre-populates the parent payment and every original allocation. The actor may correct date, payer, payee, amount, currency, Group/direct scopes, and allocation amounts. They must explicitly review the replacement allocation snapshot; Dutch never reruns `Settle all` against today's balances.
- `Settle all` therefore means all selected balances at its recorded moment, not an enduring instruction. Expenses or Settlements added later remain untouched by a correction unless the actor deliberately includes them in the replacement snapshot.
- A correction requires current modification authority in every original and replacement Group/direct scope. A person missing authority may inspect accessible parent/allocation evidence but cannot alter the multi-scope Settlement. Any currently authorized collaborator, not only the original recorder, may correct it.

### Void, restore, and visible evidence

- `Void settlement` applies only when the recorded external payment did not occur or was recorded entirely in error. It appends the exact reversal of every active allocation, removes the Settlement from ordinary active settlement lists, and retains the original and void evidence in Activity.
- `Restore settlement` creates a later revision that reapplies the original parent payment and allocation snapshot exactly. It does not recalculate current balances or rerun a settlement plan; a different desired payment/allocation is a new Correction or Settlement.
- Every correction, void, and restore has a concise parent Activity entry and scope-level linked allocation evidence. History presents before/after balance effects in human language; sync returns the new parent revision, allocations, projections, and Activity atomically.
