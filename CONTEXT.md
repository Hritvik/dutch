# Dutch

Dutch is a shared-expense domain in which people record costs, divide responsibility, track balances, and settle what they owe.

## Language

**Member**:
A person who participates in shared expenses and balances in Dutch. A member may or may not yet have a registered account.
_Avoid_: Participant, person

**Identity**:
An authenticated principal with credentials managed by IAM. An identity may be linked to one registered member profile.
_Avoid_: User, account, member

**Member Profile**:
The UMS-managed representation of a registered member, including their display and contact information and their link to an identity.
_Avoid_: User profile, identity profile, account

**Placeholder Member**:
A member recorded before they register, identified by a name and optionally contact details. A registered member may claim it through an exact match to an IAM-verified email address or E.164 phone number; a name alone never establishes ownership.
_Avoid_: Guest, dummy user, unregistered user

**Claim Offer**:
UMS's invitation for a registered member to confirm ownership of a Placeholder Member after an exact match to an IAM-verified identifier. Confirmation needs no Group Admin approval and never exposes the matched identifier in Activity.
_Avoid_: Automatic merge, account claim, member invitation

**Identifier Reference**:
An IAM-issued opaque reference to the contact value associated with a Placeholder Member. UMS uses it to request exact verified-identifier matching and create Claim Offers without storing or receiving the raw email address or phone number.
_Avoid_: Contact copy, hashed email, public identifier

**Split**:
The allocation of an expense among members using equal shares, exact amounts, or percentages.
_Avoid_: Division, breakdown

**Group**:
A named set of members whose shared expenses and resulting balances are tracked together. A Group with financial history is archived rather than deleted; archiving preserves history, Settlements, and corrections while stopping new Expenses and membership/settings changes.
_Avoid_: Circle, room

**Archived Group**:
A Group with financial history that is closed to new Expenses, members, and settings changes but retains history, read access, Settlements, and corrections. An Admin may reopen it.
_Avoid_: Deleted group, frozen ledger

**Group Membership**:
A member's participation in a group. Placeholder and Invited memberships may carry financial allocations without app access; Active membership grants access and collaboration; Inactive membership preserves historical expenses and settleable balances, grants read-only access to that Group's past records, and does not allow new participation or modification. Leaving or Admin removal makes a membership Inactive; becoming Active again requires a new invitation and acceptance.
_Avoid_: Group user, participant record

**Group Admin**:
An active group member allowed to manage group details, memberships, and Admin status. Every group must retain at least one active Admin.
_Avoid_: Owner, moderator

**Expense**:
A recorded cost paid by one or more members through payer contributions and allocated among members through a split.
_Avoid_: Bill, transaction

**Payer Contribution**:
The exact amount an individual member paid toward an Expense. All Payer Contributions for an Expense sum exactly to its total amount.
_Avoid_: Payer share, payment split

**Direct Expense**:
A one-off expense shared by two or more selected members without belonging to a named group. It uses an ad hoc scope with its own participant list and currency rather than creating ongoing group membership; it has no independent leave, archive, or member-management lifecycle.
_Avoid_: Ungrouped expense, personal expense

**Recurring Expense Series**:
A Group or direct-relationship schedule that creates nonfinancial pending occurrences on a weekly or monthly cadence. It owns a current participant/template snapshot and pinned IANA schedule timezone; each accepted occurrence becomes a separate ordinary Expense.
_Avoid_: Automatic debt, repeating transaction

**Pending Occurrence**:
A durable, nonfinancial snapshot created for one due period of a Recurring Expense Series. It remains pending until explicitly submitted as an Expense or skipped, and is never itself an obligation.
_Avoid_: Future expense, scheduled debt

**Group Currency**:
The single currency in which a group records expenses, settlements, and balances. Balances in different currencies remain separate and are never converted or combined.
_Avoid_: Default currency, base currency

**Occurred Date and Time**:
The date a user says an Expense or Settlement occurred, with an optional local time. When time is supplied, Dutch retains the entered local date/time, IANA timezone, and resolved instant; when absent, Dutch preserves only the date and does not infer a time. This is distinct from the server-recorded time used by Activity.
_Avoid_: Transaction timestamp, created time

**Settlement**:
A freely recorded payment from one member to another outside Dutch, changing their balance without Dutch moving or holding money. A Group settlement may be between any two Group members; its simplified settlement plan only suggests a recipient and amount. A Settlement may be allocated to one selected Group, to the selected pair's direct balance in a currency, or—when settling all applicable balances—to multiple Group and direct scopes. It is not attached to a particular Direct Expense. It may reduce, clear, or reverse the prior balance because recorded payments have no balance-based upper limit. It is corrected, voided, or restored through immutable parent actions rather than edited in place.
_Avoid_: Transfer, payout, payment transaction

**Settlement Allocation**:
The portion of a Settlement applied to one Group or direct scope. Allocations make a multi-scope settlement visible and explainable within every affected scope. It is navigable evidence, not independently editable; correction opens the parent Settlement with the relevant allocation highlighted.
_Avoid_: Split settlement, payment split

**Void Settlement**:
An immutable correction that exactly reverses every active allocation of a Settlement because the recorded external payment did not occur or was recorded in error. It does not erase the original evidence.
_Avoid_: Delete payment, erase settlement

**Balance**:
A member's net financial position after applying Expenses and Settlements. Within a Group, Dutch derives a simplified settlement plan from Member net positions without rewriting the underlying obligations.
_Avoid_: Wallet balance, account balance

**Obligation Ledger**:
Dutch's record of expense-derived obligations, settlements, and member balances. It is independent of Midas's personal Accounting Ledger.
_Avoid_: Ledger, Accounting Ledger, shared ledger

**Obligation Entry**:
An immutable, scoped amount one member owes another because of a specific Expense revision, Settlement, or reversal. Current balances are derived by folding entries rather than mutating stored totals.
_Avoid_: Posting, debt row, balance update

**Simplified Settlement Plan**:
A derived set of minimum transfers that settles all net Member positions within one Group and currency. It never changes the underlying Obligation Entries or rewrites their history.
_Avoid_: Debt rewrite, obligation migration

**Allocation Algorithm Version**:
The immutable identifier of the deterministic matching and rounding rules used to create an Expense's Obligation Entries or derive a Simplified Settlement Plan. Later rule changes do not recalculate past financial results.
_Avoid_: Current calculator, mutable rule set

**Activity Log**:
The append-only, immutable, human-readable history of who created, changed, or deleted group and expense records, including the server-recorded time, actor, and change. It retains deleted-expense events as evidence; deleted expenses do not appear in the normal expense History view. Entries for surviving records link to their exact revision, whose History shows before-and-after financial detail. A multi-scope Settlement has one parent Activity entry and linked allocation entries in every affected scope. Membership events do not expose email addresses or phone numbers; an anonymized actor appears as Former member.
_Avoid_: Audit log, edit history

**Notification**:
A best-effort, non-authoritative alert about relevant Activity. It may be pushed to a registered device according to current preferences, but it never changes financial state; the app refreshes the authoritative scope after opening it.
_Avoid_: Alert log, financial confirmation

**Report**:
A private request for support review of an Expense, Settlement, Activity entry, or Member as incorrect, unauthorized, or abusive. It preserves evidence and creates no automatic financial, access, or notification change.
_Avoid_: Dispute verdict, balance correction

**Safety Block**:
A Member's personal boundary that prevents either party from initiating a new Direct Expense/Series or Group invitation involving the other. It preserves existing Groups, financial history, obligations, and authorized correction paths.
_Avoid_: History deletion, debt cancellation

**Cached Projection**:
A local, read-only copy of the currently authorized Home, Group, Direct Expense, Balance, and Activity views. It is useful when offline but is not authoritative and identifies when it was last synchronized.
_Avoid_: Offline ledger, local balance source

**Local Draft**:
An unfinished Expense or Settlement command kept only on a device until its user explicitly submits it online. It is not a financial record and must be reviewed against current authorized state before submission.
_Avoid_: Queued transaction, offline payment

**Sync Cursor**:
An opaque, signed checkpoint for one Member's authorized Ledger change feed. It binds a recipient feed and authorization epoch to the last delivered change sequence, but contains no financial content, contact identifier, or scope list. An expired or invalid cursor requires an authorized full sync.
_Avoid_: Sync data, balance token, client-generated cursor

**Privacy Export**:
A securely requested, time-limited copy of a Member's own profile and data they are currently authorized to view. It does not reveal other Members' private contact identifiers.
_Avoid_: Database dump, account backup

**Anonymization**:
The irreversible removal of a deleted Member's personal profile and credential data while retaining immutable shared financial evidence under Former member.
_Avoid_: Financial deletion, history erasure
