Type: grilling
Status: resolved
Blocked by: 03

# Define the identity and membership lifecycle contract

## Question

What exact lifecycle and contracts govern IAM Identities, UMS Member Profiles, Placeholder Members, verified email/phone identifiers, claiming and merging, deactivation, account deletion, and authorization when either IAM or UMS is unavailable?

## Answer

### Registration and the Identity–Member boundary

IAM verifies an email address or E.164 phone number and creates a permanent Identity. The authenticated client then asks UMS to idempotently create or promote exactly one canonical Member Profile. If UMS is unavailable, the Identity remains valid with `PROFILE_REQUIRED` onboarding state but cannot use financial routes. UMS publishes `MemberProfileCreated`; no distributed transaction spans IAM and UMS.

One active Identity links to exactly one canonical Member, and one registered Member links to at most one active Identity. Shared accounts and delegated identity use are forbidden. Credential or device changes never change Member ID; recovery restores the existing link.

### Verified identifiers

An Identity may own multiple verified emails and E.164 phone numbers. Each normalized verified identifier is globally unique to one active Identity. Adding or changing one requires fresh IAM verification. The final recovery-capable identifier cannot be removed without another recovery method. IAM treats identifiers as credentials; UMS owns preferred profile contact behavior. Identifier conflicts reveal no account details and enter a secure recovery flow.

Raw identifiers never appear in tokens or lifecycle events. `IdentityIdentifiersChanged` carries only Identity and version context. UMS retrieves verified identifiers from IAM through an authenticated service call only when matching or revalidating claims.

### Placeholder claims and canonicalization

An exact normalized match against an IAM-verified email or phone produces a seven-day, single-use **Claim Offer**, not a silent claim. It identifies the affected Groups and obligations without displaying the identifier. The registered member explicitly confirms; no Group Admin approval is required.

Claim Offers bind Identity ID, Placeholder Member ID, verified-identifier ID, and identifier version. Removal, reassignment, Identity lock, or deletion invalidates them. Confirmation synchronously revalidates the identifier with IAM and pauses if IAM is unavailable. Acceptance is idempotent and serialized so only one Identity can win.

If initial registration matches placeholders, the oldest confirmed Placeholder Member ID becomes the canonical Member ID; other confirmed IDs become aliases. If an existing registered Member later matches placeholders, their existing Member ID remains canonical. Canonical ID never changes again. UMS publishes versioned, PII-free canonicalization facts.

The Obligation Ledger resolves aliases for current authorization, writes, and derived balances while preserving original Member IDs in immutable Expenses, Settlements, revisions, and Activity. Historical reconstruction can show records before canonicalization separately and current views as one person.

### Group participation and access

Group Membership has four lifecycle states:

- **Placeholder** — eligible for financial allocation without an acting Identity.
- **Invited** — linked to a registered Member and financially addressable, but no Group access until accepted.
- **Active** — accepted access with the settled collaboration permissions.
- **Inactive** — no new participation or modification; historical records and balances remain visible and settleable.

Adding a person permits bookkeeping allocation immediately but never silently grants a registered Identity access. UMS resolves or creates the Member without disclosing registration state; Obligation Ledger owns the membership. Declining access leaves prior obligations intact for auditable correction if needed.

A confirmed Placeholder claim commits canonical ownership in UMS, then publishes `PlaceholderClaimConfirmed`. Obligation Ledger idempotently applies mappings, activates claimed memberships, records Activity, and emits `ClaimedMembershipsActivated`. The client shows activation as pending until that event completes. Ledger downtime delays access but never rolls back the confirmed identity claim. Ordinary invitation acceptance is a direct Ledger command.

### Authorization projections

IAM tokens carry immutable authentication context—Identity ID, session ID, issuer, audience, authentication time, and expiry—not Member Profile fields.

UMS feeds Obligation Ledger two minimal versioned projections:

1. Authorization mapping: Identity ID, canonical Member ID, lifecycle status, aliases, and mapping version.
2. Display reference: canonical Member ID, display name, avatar reference, and display version.

No contact identifiers, recovery data, or private preferences are copied. Ledger uses the authorization mapping plus its own Group Membership state for local domain authorization, and the display reference for purpose-built financial responses. Missing or inactive mappings fail closed. Existing projections permit financial work during UMS outages.

IAM publishes lock and session-revocation events for local deny lists. Short access-token lifetime is the hard upper bound if delivery is delayed; refresh always requires IAM. Exact token lifetime belongs to the security-control decision.

### Security lock, deactivation, and deletion

- **Security lock** blocks IAM authentication and revokes sessions without changing UMS or financial history.
- **Deactivate** is a reversible pause: revoke sessions, mark the profile inactive, and remove acting Group access while preserving balances.
- **Delete** revokes access immediately and allows a 30-day recovery window. Afterward IAM removes credentials and identifiers; UMS anonymizes PII.

Obligation Ledger never deletes Member IDs, Expenses, Settlements, balances, or Activity. Anonymization replaces current display data with “Former member” and removes the avatar through a versioned UMS event. Pending invitations and Claim Offers are withdrawn. A sole Group Admin must designate another active Admin first. Re-registration after final deletion creates a new Identity and Member and never silently reclaims anonymized history.

### Failure and consistency rules

- Existing unexpired tokens and projected mappings keep authorized financial work available during IAM or UMS outages, subject to locally received locks and revocations.
- Registration/profile completion, identifier changes, recovery, and claiming pause whenever their authoritative owner is unavailable.
- Every lifecycle producer uses a transactional outbox; consumers apply monotonic versions and event IDs idempotently.
- UMS is authoritative for Identity–Member linkage, aliases, and profile lifecycle. Obligation Ledger is authoritative for Group Membership access and financial authorization.
- Eventual lifecycle transitions are shown honestly as pending; no gateway or client fabricates completion.
