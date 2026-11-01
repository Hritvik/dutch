Type: grilling
Status: resolved
Blocked by: 03, 07

# Define notification and activity delivery

## Question

Which domain events produce in-app activity and push notifications, who receives them, how are duplicates and ordering handled, what preferences are allowed, and how does the system behave when delivery is delayed or permanently fails?

## Answer

### Activity and recipient rules

- Every committed financial and membership event creates the durable, in-app Activity Log evidence established in **Define collaborative editing and Activity Log semantics**.
- Push is narrower: invitation and claim outcomes alert the affected registered Member; Group Expense creation, revision, deletion/restoration, Settlement, and membership/Admin changes alert other Active Group Members, never the actor. Equivalent Direct Expense events alert every other registered Direct Expense participant. Placeholder Members receive no push until they have claimed an Identity.
- A multi-scope `Settle all` action has one parent event and per-scope Activity entries. Each affected recipient receives at most one summary push for that action, rather than an allocation-level burst.

### Preferences and content

- Members may mute push notifications per Group and globally opt out of nonessential financial updates. Invitations and access/security notices remain available; the in-app Activity Log is never muted or removed.
- The app requests OS notification permission contextually, not during initial onboarding.
- Push content is privacy-preserving by default: it identifies that there is activity in a scope but does not expose amounts, expense descriptions, or Member names on a lock screen. Opening a notification authenticates as needed, deep-links to the narrowest current or historical context, and refreshes authoritative state.
- Current preferences apply when Notification attempts delivery. A mute made before a delayed event is delivered suppresses that push.

### Delivery behavior

- Notification consumes immutable Ledger/UMS outbox events at least once and de-duplicates by stable event ID. Push order is not guaranteed across aggregates; Activity and refreshed projections are authoritative.
- Bursty nonurgent events for one Group collapse into a summary. Invitations, removals/access changes, and a directly relevant Settlement remain individually delivered.
- Transient provider failures retry with bounded exponential backoff. A provider-confirmed invalid token is removed. Permanent or delayed push delivery never changes or invalidates the underlying financial command; the Activity Log remains the durable fallback.
