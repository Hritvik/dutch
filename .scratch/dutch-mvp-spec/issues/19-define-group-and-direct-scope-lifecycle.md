Type: grilling
Status: resolved
Blocked by: 04, 05, 06

# Define Group and Direct-scope lifecycle

## Question

How do Groups and multi-person Direct Expense scopes archive, leave, remove/reinvite/reactivate Members, handle non-zero balances and the final Admin, and retain or revoke participant visibility and correction authority without compromising historical evidence?

## Answer

### Group lifecycle

- A Group with no Expense, Settlement, or financial correction may be permanently deleted by a Group Admin. Its deletion emits a sync tombstone and removes the local scope. A Group with financial history is never hard-deleted.
- An Admin archives a financial Group. Archive hides it from ordinary active work and blocks new Expenses, members, and settings changes; it preserves read history, active-member Settlements/corrections, balances, and Activity so outstanding obligations remain settleable. An Admin may reopen it.
- Archived Groups produce no ordinary Expense/member push notifications. Relevant correction, Settlement, access, and security notifications remain available to eligible Members; Activity remains complete.

### Group Membership lifecycle

- A Member leaving voluntarily or removed by a Group Admin becomes Inactive immediately. Their balances and read-only historical visibility remain; they cannot create, modify, or be newly allocated. Any sole Active Group Admin must appoint another Active Admin before leaving/removal.
- Reactivation is never silent: an Inactive Member receives a new invitation and must explicitly accept before becoming Active. Historical records remain unchanged. Placeholder/Invited/Active/Inactive financial/allocation rules remain as previously settled.

### Direct Expense lifecycle

- A Direct Expense is a self-contained, permanent historical scope, not an ongoing Group. It has no leave, archive, invite, or independent membership-management action.
- A participant list changes only by an immutable Expense Revision. Every registered Member appearing in any revision retains read-only access to the Direct Expense's full revision and Activity history; only a registered participant in the current revision may create a correction. A registered historical participant may still record an external direct Settlement for their own visible pairwise balance, so removal never makes an outstanding obligation un-settleable.
- Corrections preserve old participant snapshots and corresponding evidence. Account deactivation/deletion follows the settled Identity/Member rules: it stops acting access while retaining/anonymizing immutable financial history as appropriate.
