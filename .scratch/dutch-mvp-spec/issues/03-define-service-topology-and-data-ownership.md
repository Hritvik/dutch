Type: grilling
Status: resolved

# Define service topology and data ownership

## Question

What deployable boundaries, authoritative data ownership, synchronous calls, asynchronous events, and failure semantics should connect the API gateway, IAM, UMS, Obligation Ledger, and Notification services without creating distributed transactions across financial invariants?

## Answer

### Deployable boundaries and ownership

- **API Gateway** owns no domain data. It routes requests, validates tokens coarsely, applies rate limits, and propagates correlation context.
- **IAM** owns Identities, credentials, verified email and phone identifiers, sessions, and tokens in its own database.
- **UMS** owns Member Profiles, Placeholder Members, Identity-to-Member links, verified-identifier associations, and claim or merge state in its own database.
- **Obligation Ledger** owns Groups, Group Memberships, Expenses, Splits, Settlements, Balances, and the Activity Log in its own database.
- **Notification** owns device tokens, notification preferences, and delivery attempts in its own database and worker.

Each boundary is independently deployable from the first MVP. No service reads another service's database.

### Interaction model

- Mobile commands and immediate reads use synchronous REST through the API Gateway.
- The API Gateway remains infrastructure-only: it neither orchestrates domain workflows nor joins service data.
- Purpose-built mobile read endpoints are exposed by the service that owns the view. The Obligation Ledger may use its local, event-fed Member projection to render financial views without synchronous joins.
- IAM issues signed tokens. The gateway and services validate them locally rather than calling IAM on each request.
- UMS publishes Member lifecycle, claim, and merge facts. The Obligation Ledger consumes the minimum Member reference data required for authorization and display.
- Obligation Ledger publishes committed financial and activity facts. Notification consumes them asynchronously.
- No workflow uses a distributed database transaction or synchronous chain of events.

### Stable membership identity

UMS assigns a permanent Member ID before the Obligation Ledger references a Placeholder Member. Claiming attaches an IAM Identity to the same Member ID, so claiming does not rewrite financial records. If two Member IDs must be merged, UMS publishes an idempotent canonicalization event; the Obligation Ledger preserves historical references in the Activity Log while resolving future use to the canonical Member ID.

### Authorization

The gateway authenticates and performs coarse route checks. The service owning the data makes the final authorization decision. The Obligation Ledger therefore determines active membership, Group Admin status, and expense permissions. Services derive Identity from a verified token and use trusted UMS mapping data; they never trust client-supplied Identity or acting-Member IDs.

### Failure semantics

- With IAM unavailable, valid unexpired tokens continue to work; registration, verification, refresh, and recovery pause.
- With UMS unavailable, existing financial operations continue against the Obligation Ledger's Member projection; profile changes, invitations, Placeholder Member creation, and claiming pause.
- Notification outages never fail financial operations; delivery resumes from durable events.
- Event-transport outages never roll back source changes: services commit state and outbox records locally, then publish later.
- When the Obligation Ledger is unavailable, authoritative financial reads and writes fail clearly; the gateway never fabricates success or serves a cached balance as authoritative.
- Clients retry only naturally idempotent operations or commands carrying idempotency keys.

### Event contract

Events use at-least-once delivery. Every producer commits an outbox record with its state change. Events carry a stable event ID, type, schema version, occurrence time, producer, correlation ID, and aggregate ID. Ordering is guaranteed only per aggregate where required. Consumers record processed event IDs and apply events idempotently. Facts are immutable; incompatible schemas use new versions. Poison events enter a dead-letter path with alerting and controlled replay. Broker selection remains with **Select persistence, messaging, and deployment topology**.
