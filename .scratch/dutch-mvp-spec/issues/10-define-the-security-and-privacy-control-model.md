Type: grilling
Status: resolved
Blocked by: 03, 04, 05, 07

# Define the security and privacy control model

## Question

What threat model and concrete controls protect credentials, tokens, verified identifiers, placeholder claiming, group authorization, financial history, service-to-service calls, abuse-sensitive endpoints, auditability, export, anonymization, and operational secrets for the MVP?

## Answer

### Threat model and security boundary

Dutch protects against account takeover, stolen devices/tokens, identifier enumeration and fraudulent Placeholder claims, replayed or manipulated financial commands, unauthorized Group access, malicious but authorized collaborators, service-to-service impersonation, notification/cache/log leakage, and abuse of verification/recovery endpoints. It does not move money or custody payment credentials.

- IAM uses mobile OAuth/OIDC authorization-code flow with PKCE. Access tokens are signed, audience- and issuer-bound, short lived (at most 15 minutes), and include only immutable authentication context. Refresh tokens rotate, detect reuse, have a 30-day maximum session lifetime, and live only in OS SecureStore.
- Token validation checks signature, key ID, issuer, audience, expiry, not-before, session revocation, and Identity status. Refresh, recovery, identifier change, Privacy Export, deletion, and recovery-capable credential changes require recent reauthentication/verification. Optional device biometrics may guard opening the app but never replace IAM authentication.
- The Gateway performs coarse authentication/rate checks; IAM, UMS, and Ledger each make the final authorization decision for their data. The Ledger authorizes every Group/Direct Expense command from trusted Identity-to-Member projections and current membership, never client-supplied actor or scope claims. Inaccessible scopes return non-enumerating `404` responses.

### Privacy and financial evidence

- IAM alone holds raw verified email addresses, phone numbers, credentials, and recovery material. UMS holds Member Profile/contact preferences. Ledger holds only Member IDs, display references, Group/financial data, and Activity; Notification holds only device tokens, preferences, and delivery records. No token, event, Activity item, push payload, API error, or standard log includes raw identifiers or secrets.
- TLS protects every network hop; databases, backups, and device storage are encrypted at rest. Mobile cached projections are scoped to the current authenticated Identity and removed on logout, Identity change, access revocation, or final deletion. Session/refresh material never enters SQLite, analytics, crash reporting, or backups.
- Immutable financial records and Activity are retained for reconstruction. Deletion immediately revokes access, offers the previously settled 30-day recovery period, then removes IAM credentials/identifiers and UMS PII. Ledger replaces display data with `Former member`; it never erases Expenses, Settlements, Balances, or Activity.
- A Privacy Export is requested after recent reauthentication, produced asynchronously, limited to the requester's profile and data they are currently authorized to view, protected by an expiring single-use download, and logged without its contents. It redacts other Members' private identifiers.

### Claims, abuse prevention, and command integrity

- Placeholder claims retain the settled exact-IAM-verified-identifier, seven-day, single-use Claim Offer model. Claim/identifier/OTP/recovery responses are generic and non-enumerating. Attempts are centrally rate-limited and risk-monitored by Identity, normalized identifier, device/session, and IP; repeated failures trigger progressive cooldown and security review rather than revealing account state.
- Financial commands require validated schemas, server-side numeric bounds, canonical member resolution, scope/currency/membership checks, `Idempotency-Key`, and expected versions where applicable. Ledger serializes its invariants in one transaction; it rejects reuse of a key with changed input, stale writes, and unauthorized state. Parameterized queries and allow-list validation are mandatory at each service boundary.
- Active collaborators are allowed to make attributable changes by product design; immutable revisions, Activity, explicit conflict handling, and idempotency make misuse detectable and reconstructable rather than attempting to conceal it.

### Service and operational controls

- Service-to-service calls use workload identities, mutual TLS, audience-bound short-lived service credentials, least-privilege authorization, and network segmentation. Event consumers authenticate producers, verify schema/version, and preserve the existing idempotent event-ID model.
- Secrets are stored only in an environment-appropriate secret manager, injected at runtime, access-audited, and rotated. They are prohibited from source control, builds, client bundles, logs, and error responses. Production errors are generic to clients and carry correlation IDs; structured server logs redact tokens, identifiers, money notes, and request bodies by default.
- Rate limits apply to all public API paths, with stricter configurable limits for login, verification, recovery, identifier change, claim, export, sync, and financial mutation endpoints. Limits return `429` with `Retry-After`; they do not change financial command semantics.
- CI and release gates include secret scanning, dependency/SCA review, static analysis, authenticated/unauthenticated authorization tests, schema/input tests, idempotency and stale-write tests, log-redaction checks, and regular dependency patching. Production security events—token reuse, repeated claim failures, unusual verification volume, and failed service authentication—are alerted with runbooks.
