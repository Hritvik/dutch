Type: grilling
Status: resolved
Blocked by: 05

# Define collaborative editing and Activity Log semantics

## Question

How should create, edit, delete, membership change, and correction actions be represented and authorized so current members can collaborate freely while the Activity Log preserves an understandable, tamper-resistant history and inactive members retain correct historical visibility?

## Answer

### Collaboration and authorization

- Every Active Group Member may create, edit, delete, restore, and record settlements for the Group, regardless of who originally created the expense. Group Admins alone manage Group settings, membership, and Admin status.
- Every registered participant in a Direct Expense may make the same corrections and record settlements. A Placeholder Member cannot act until registration and claim link them to an Identity.
- An Inactive Group Member retains read-only access to that Group's past expenses, settlements, revision history, and Activity Log. They cannot create, modify, or newly participate. Rejoining restores collaboration access without changing history.

### Financial corrections and conflicts

- Editing a financial Expense uses the immutable reversal-and-replacement revision model established by the Obligation Ledger. Restore is available to any authorized collaborator; it creates a new current revision and an Activity entry.
- A deleted Expense is retained as a deletion event in the Activity Log but does not appear in the ordinary Expense History view. Its immutable Ledger evidence remains reconstructable.
- Commands carry expected versions. If a save is stale, Dutch commits nothing, shows the changes made since editing began, and requires an intentional reload and retry. It never silently overwrites or automatically merges financial changes.

### Activity Log and revision history

- Activity is append-only and immutable. It records the server-recorded action time, actor, action, affected scope and object, and concise human-readable change summary. The user-entered Expense date remains distinct from recorded time.
- Surviving Expenses expose a History view with exact revision links and before/after financial details; Activity stays scannable and links into that evidence where appropriate.
- Membership Activity includes invited, joined, removed, and Admin-role changes, but never the email address or phone number used for invitation or claim. Permanent account anonymization preserves the event under the actor label `Former member`.
- A multi-scope `Settle all` action creates one parent Activity entry for the recorded payment plus linked Settlement Allocation entries in each affected Group or direct scope. Each scope therefore explains precisely how it was closed.
- Activity entries cannot be edited or removed by users. Policy-required identity anonymization changes display data only; it does not erase the historical event.
