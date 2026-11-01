Type: prototype
Status: resolved
Blocked by: 01, 02, 07

# Define mobile cache, draft, and synchronization behavior

## Question

What information is cached for offline reading, how are local expense drafts represented and recovered, how is staleness communicated, and what interaction should occur when server state changed before an online-first submission?

## Answer

Prototype: [mobile-sync-state-prototype.html](../prototypes/mobile-sync-state-prototype.html)

### Cache boundary and freshness

- SQLite holds the complete currently authorized client read projection: Home summaries; accessible Group and Direct Expense views; balances; and the Activity needed to explain those views. It does not contain IAM session/refresh material, raw email or phone identifiers, or internal Obligation Entries. IAM material remains in SecureStore.
- A Cached Projection is read-only and never treated as financial authority. Financial screens show an offline or refreshing state and the last successful synchronization time whenever currentness is not known.
- The client applies each sync page and its replacement cursor atomically in local storage. It applies tombstones immediately, including the removal of Group-scoped cached data after genuine access revocation.
- On `sync_cursor_expired`, the client drops its cursor, retains Local Drafts, clears/rebuilds the authorized projection through a full sync, and does not permit draft submission until the relevant scope has been refreshed. Logout, Identity change, and access revocation remove the corresponding cached projection and drafts.

### Draft lifecycle and submission

- A user may create or edit an Expense or Settlement as a Local Draft while offline or online. A draft stores its command fields, scope, source resource version where applicable, creation/update times, and submission state. It is retained until submitted successfully or explicitly discarded.
- Drafts are never silently queued, auto-submitted, or represented as balances, Activity, or a pending financial record. The user explicitly selects Submit after connectivity is available.
- Immediately before submission, the app refreshes the relevant scope and validates the draft against current membership, scope/currency, balance context, and expected version. If a related Expense changed, the app presents the latest revision and the draft side-by-side for an intentional reload or reapplication; it never merges financial fields automatically.
- The client creates an idempotency key when it first submits a command and retains that key through every retry. A lost response remains `submission uncertain` until retry with the same key returns the authoritative result. Validation failure preserves the editable draft; a successful command replaces it with the returned authoritative resource, balances, and Activity entries.
- A `503` or offline transition leaves the draft locally intact and clearly unsent. A `409 stale_version` leaves it in `needs review`, with the latest server snapshot. Any new authorization or membership failure marks it blocked and explains that the user must revise or discard it.

### Interaction model

- Opening the app, resuming it, manually refreshing, and opening a notification start incremental sync when possible. Offline reading remains available from the Cached Projection.
- The prototype validates the visible states: local offline draft, server change before send, uncertain submission after a lost response, access-revocation tombstone, and expired-cursor recovery. In every state, the app distinguishes a local intent from an accepted financial fact.
