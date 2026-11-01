# State-of-the-art benchmark: mobile, offline, security, and release

**Scope.** This evaluates the settled Dutch decisions in the mobile stack, cache/sync, notifications, IAM/security, privacy, and release tickets against current primary documentation and standards. It does **not** change those decisions. “State of the art” here means a mature, widely applicable production baseline—not a claim that every advanced control is necessary for an MVP.

## Executive assessment

Dutch’s mobile/security posture is unusually strong for a shared-expense MVP. The design follows the important modern separation of concerns: a native client holds only protected session material and a non-authoritative local projection; the server remains financial authority; notification delivery is not financial truth; and releases distinguish compatible OTA code from native runtime changes.

| Decision area | Benchmark | Assessment | Main condition / trade-off |
| --- | --- | --- | --- |
| React Native + TypeScript + Expo development builds | Expo’s supported production workflow | Strong MVP fit | Keep a small native escape-hatch budget and stay current with Expo SDK upgrades. |
| SecureStore for refresh/session material, SQLite for cache/drafts | Platform keystore/keychain separation | Correct boundary | SQLite is private app storage, not automatically encrypted-at-rest by Expo; preserve its minimal-data policy and evaluate app-level DB encryption only if threat/compliance requires it. |
| Full authorized cache; explicit online financial submission | Offline-first read / online-authoritative write | Conservative and financially appropriate | Less seamless than an offline command queue, but it avoids invisible financial commits and conflict merges. |
| Opaque cursor sync + tombstones + atomic cursor advance | Change-feed/checkpoint pattern | Strong | Server must retain/replay changes correctly and make full-resync inexpensive. |
| In-app Activity authoritative; push best-effort/deep-link refresh | Push-provider delivery model | Strong | Receipt processing and invalid-token cleanup are operational requirements. |
| OAuth/OIDC authorization code + PKCE, short access tokens, rotating refresh tokens | IETF native-app OAuth BCP | Current best practice | Use an external browser/system auth session, strict redirect registration, and refresh-token reuse detection. |
| Least-PII service ownership, scoped cache clearing, anonymized retained financial history | OWASP MASVS privacy/storage principles | Strong privacy-by-design direction | Confirm jurisdiction-specific retention/export/deletion rules before launch. |
| Isolated environments; EAS channels/runtime versions; staged OTA/store promotion | Expo’s current release guidance | Strong | OTA must be runtime-compatible and use the same tested bundle for promotion. |

## 1. Cross-platform client and local persistence

### React Native, TypeScript, and Expo development builds — **strong fit**

React Native is a native-app implementation model for the purposes of the IETF native-app OAuth BCP, even when an app uses web technologies internally. That is relevant because Dutch’s authentication and security assumptions are properly being made as **native client** assumptions, rather than treating the app as a browser website. [RFC 8252, §3](https://datatracker.ietf.org/doc/html/rfc8252#section-3)

Expo’s current documentation explicitly positions development builds (`expo-dev-client`) as the way to preview updates that are not available in Expo Go when a `runtimeVersion` is used. That confirms the selected development-build path is supported rather than a workaround. [Expo: Using EAS Update](https://docs.expo.dev/build/updates/)

Expo’s current New Architecture guidance says it is always enabled in SDK 55+ and that Expo modules support it; React Native describes its JSI/bridgeless model as the platform direction, while cautioning that a migration does not automatically improve every app. In practice, Dutch should use a current Expo SDK, run `expo-doctor` in CI, and reject dependencies that are incompatible with the New Architecture. [Expo: New Architecture](https://docs.expo.dev/guides/new-architecture/) [React Native: New Architecture](https://reactnative.dev/architecture/landing-page)

**Trade-off:** the chosen stack is a delivery-velocity choice, not a promise of zero native work. Native libraries/config changes alter the runtime compatibility boundary and must go through a new binary. The existing release model already recognizes that distinction.

### SecureStore for tokens, SQLite for non-secret read data — **correct separation, with one important caveat**

`expo-secure-store` uses Android encrypted SharedPreferences backed by Android Keystore and iOS Keychain. That is the appropriate place for small, revocable refresh/session material; Expo also warns not to treat SecureStore as the only copy of irreplaceable critical data. [Expo SecureStore: platform storage and persistence](https://docs.expo.dev/versions/latest/sdk/securestore/)

The settled decision to keep tokens out of SQLite, logs, analytics, and backups aligns with OWASP MASVS’s explicit secure-storage and privacy domains. [OWASP MASVS](https://mas.owasp.org/MASVS/)

Two platform facts should become implementation acceptance criteria:

- iOS Keychain data can survive uninstall/reinstall for the same bundle ID, while Android SecureStore data does not; server-side revocation and local account-binding are therefore essential. [Expo SecureStore: data persistence](https://docs.expo.dev/versions/latest/sdk/securestore/#data-persistence)
- Android backup must exclude SecureStore’s encrypted preferences because restored values cannot be decrypted after keystore removal. Expo’s config plugin handles this by default, but a custom backup configuration must retain the exclusion. [Expo SecureStore: Android Auto Backup](https://docs.expo.dev/versions/latest/sdk/securestore/#android-auto-backup)

Expo SQLite supplies a supported local relational database, including transactions, so it is technically suitable for an atomic projection-plus-cursor store. [Expo SQLite](https://docs.expo.dev/versions/latest/sdk/sqlite/)

Expo also identifies SQLite as a good persistence choice for local-first applications and supports migrations and optional SQLCipher. This validates the technology choice, while keeping the use of SQLCipher conditional on a decided device-forensics/regulatory requirement rather than treating it as automatic MVP complexity. [Expo: local-first guide](https://docs.expo.dev/guides/local-first/) [Expo SQLite](https://docs.expo.dev/versions/latest/sdk/sqlite/)

**Caveat:** Expo SQLite documentation does not make the database a general secure vault. Dutch’s current data minimization, identity scoping, logout/revocation wipe, and no-raw-PII rule are therefore the key protections. If the product’s threat model later requires protection from a device-level attacker or forensic extraction, add an explicit risk decision for SQLCipher/app-level envelope encryption and the associated key lifecycle; do not assume SQLite alone provides it.

## 2. Offline, synchronization, and financial integrity

### Cached read model + explicit submission — **more conservative than many consumer apps, and appropriate**

Many collaborative consumer clients queue writes offline and reconcile later. Dutch intentionally does not: drafts are local intent, never balances or Activity, and only submit after scope refresh. For a financial record where every accepted command must be attributed, idempotent, and conflict-aware, this is a defensible state-of-the-art *integrity-first* design.

The design’s strongest properties are:

- sync pages and their next cursor commit atomically;
- a cursor represents server position, not client-derived time;
- tombstones remove data after access revocation;
- retries reuse the same idempotency key; and
- a lost response stays “submission uncertain” until the server returns the stored result.

This resembles reliable checkpointed replication more than screen-by-screen refresh. The principal trade-off is user experience: someone offline can prepare work but cannot create a financially accepted Expense or Settlement until reconnecting. That is explicitly preferable to speculative financial writes for MVP.

### Cursor sync — **strong, provided the server guarantees its contract**

Opaque cursors with server-controlled issuance, scoped high-water marks, expiry, tombstones, and full-resync recovery are a mature API synchronization pattern. The following guardrails are essential and match Dutch’s decision:

- bind cursor validity to authenticated identity/access scope, not merely a device;
- do not encode private financial data in the token;
- retain enough change history to honour the advertised window;
- return `410 Gone`/a stable cursor-expired code rather than silently skipping history; and
- make resource upserts and cursor replacement one local transaction.

This is better suited to concurrent financial changes than timestamp polling, offset pagination, or “last opened screen” sync, all of which can skip or duplicate changes at moving boundaries.

## 3. Authentication, authorization, and privacy

### OAuth/OIDC for a native public client — **matches IETF BCP**

RFC 8252 requires native apps to use an external user-agent for OAuth authorization requests and says public native clients **MUST** implement PKCE; it recommends browser/in-app browser tabs that retain browser security properties rather than an embedded web view. [RFC 8252, §§4–6](https://datatracker.ietf.org/doc/html/rfc8252#section-4) [RFC 8252, §8.12](https://datatracker.ietf.org/doc/html/rfc8252#section-8.12)

RFC 9700—the current OAuth security BCP—adds the current requirement that authorization servers perform exact redirect-URI string matching (apart from the defined localhost native-app exception) and reinforces PKCE as a core protection. [RFC 9700, §2.1](https://datatracker.ietf.org/doc/html/rfc9700#section-2.1)

Accordingly, Dutch’s code+PKCE design, short-lived audience/issuer-bound access token, server-side authorization, session revocation, rotating refresh tokens, and recent reauthentication for recovery/export/destructive controls are at or above the normal production baseline.

**Targeted implementation checks:** use authorization code flow only (not implicit grant); use `S256` PKCE; register exact redirect URIs; prefer claimed HTTPS/App/Universal Links where supported; never embed a client secret in the app; and invalidate the entire token family on refresh-token reuse. Device biometrics should remain a local app-unlock control, not an IAM identity proof—exactly as Dutch specifies.

### Server-side authorization and command integrity — **strong**

The Ledger derives its actor from a verified token and its current Identity-to-Member projection, rather than trusting a client-supplied actor/scope. This prevents a central class of mobile authorization failure. Version conditions, idempotency keys, canonical server validation, and immutable Activity together address replay, duplicate retries, concurrent edits, and a malicious-but-authorized collaborator without pretending client-side checks are a security boundary.

OWASP MASVS explicitly treats authentication/authorization, secure storage, secure network communication, privacy, and resilience as distinct mobile control groups. Dutch’s split of client secure storage, TLS, server authorization, abuse rate limits, immutable history, and redacted logs maps cleanly to that model. [OWASP MASVS control groups](https://mas.owasp.org/MASVS/)

### Privacy boundary and retained financial evidence — **strong, but policy-sensitive**

Restricting raw email/phone/credentials to IAM, retaining only display references in Ledger, avoiding PII in push payloads/events/logs, and anonymizing a deleted person’s ledger display data is a robust minimization boundary. OWASP highlights that app-sandbox storage still has exposure risk from application/device vulnerabilities and backup mechanisms; Dutch’s restricted cache and wipe-on-access-loss policy meaningfully reduces that exposure. [OWASP MASWE-0006](https://mas.owasp.org/MASWE/MASVS-STORAGE/MASWE-0006/)

The planned privacy export requires recent authentication and gives an expiring single-use download. This is a sensible security posture. The remaining non-technical question is jurisdiction: financial-record retention, former-member visibility, account-deletion timing, and export-response deadlines must be validated against the markets in which Dutch launches. No framework choice substitutes for that legal/product policy.

## 4. Notifications

### Durable Activity, best-effort push — **correct and provider-aware**

Expo describes push as a service that routes through Expo and then the platform providers, FCM and APNs; sending therefore involves provider tokens and delivery receipts rather than a transactional application guarantee. [Expo push overview](https://docs.expo.dev/push-notifications/overview/) [Expo: send notifications](https://docs.expo.dev/push-notifications/sending-notifications/)

Dutch’s design is precisely the robust response:

- a Ledger event and in-app Activity are durable evidence;
- Notification consumes idempotently and push is best-effort;
- a deep link refreshes authoritative state instead of trusting notification text;
- invalid device tokens are removed; and
- per-group mute/opt-in is evaluated at delivery time.

Privacy-preserving lock-screen text and notification coalescing are product-quality additions beyond the transport baseline. The provider adapter is also a sound portability decision: Expo Push can be used now without coupling the product model to Expo forever.

Expo documents a 600 notifications/second/project limit and recommends throttling, retry, and receipt checking. The service therefore needs the already-designed delivery-attempt store plus receipt polling/handling and rate-aware batching; this is not merely client notification code. Direct FCM/APNs remains a later option if provider-specific control, payloads, or compliance requires it. [Expo: sending notifications](https://docs.expo.dev/push-notifications/sending-notifications/) [Expo: custom push services](https://docs.expo.dev/push-notifications/sending-notifications-custom/)

## 5. Release and environment promotion

### EAS channels + runtime compatibility — **current Expo guidance**

Expo’s current deployment guide recommends channels such as staging/production, a distinct runtime version for each app binary version, and promotion by republishing the tested staging update to production. It also supports per-update gradual rollouts and rollback. [Expo: Deploy updates](https://docs.expo.dev/eas-update/deployment/)

Expo specifically warns that a JavaScript update incompatible with the native runtime can fail or crash, and says a native-runtime change requires a new runtime version. [Expo: Using EAS Update](https://docs.expo.dev/build/updates/)

Dutch’s development/preview/staging/production build profiles, `appVersion` runtime policy, staging-first promotion of the identical verified bundle, and staged store release therefore align directly with current official guidance.

**Release caveat:** EAS Update environment values behave differently from build-profile `env` values; Expo says build-profile `env` values are not automatically available to `eas update`. Promotion automation must explicitly select the correct EAS environment and must test the same configuration used in production. [Expo: Using EAS Update—environment variables](https://docs.expo.dev/build/updates/#environment-variables-and-eas-update)

### AWS environment isolation — **appropriate operational baseline**

Separate production/non-production accounts, separate databases/queues/secrets, least-privilege credentials, and a no-production-data-in-lower-environments rule are mature cloud isolation practices. Fargate’s managed task model remains suitable for this service count because it removes EC2 cluster operation while retaining task-level CPU, memory, networking, and IAM configuration. [AWS: Fargate for ECS](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/AWS_Fargate.html)

The architecture’s forward-only financial migration rule is more important than the deployment product: schema/API/event expansion and contraction avoids having a mobile version or asynchronous consumer encounter a removed field or reinterpreted ledger record. That is the correct discipline for a financial-history system.

## Targeted recommendations without changing the decisions

1. **Make local-data classification executable.** Document each SQLite table/field as `financial projection`, `draft`, or prohibited (`token`, raw identifier, internal entry), and assert the prohibition in tests.
2. **Add secure-storage acceptance tests on physical devices.** Cover Android backup exclusion, iOS reinstall behaviour, biometric invalidation, logout/account switch, and access-revocation wipe. Expo notes that simulator biometric behaviour differs from real devices. [Expo SecureStore](https://docs.expo.dev/versions/latest/sdk/securestore/)
3. **Publish the cursor contract as an API invariant.** Include atomic apply, identity binding, retention window, expiry/full resync, and test cases for a lost response, duplicated page, revoked Group, and cursor expiry.
4. **Treat OAuth configuration as security-critical infrastructure.** Automated tests should verify `S256` PKCE, exact redirect URI allowlists, no embedded secrets, refresh-family reuse detection, and recent-auth gates.
5. **Operationalize notification receipts.** Monitor provider receipt errors and invalid-token removal; Activity—not push counts—should be the reconciliation/audit measure.
6. **Gate every OTA by runtime compatibility and environment provenance.** Record runtime version, EAS environment/channel, bundle hash/update ID, Git commit, API compatibility, and staged-device validation before promotion.
7. **Enforce New Architecture compatibility.** Use a supported current Expo SDK and run Expo dependency diagnostics in CI; do not add an unmaintained native dependency that blocks the React Native platform path.
8. **Keep the deliberate UX trade-off.** Do not introduce background auto-submission merely to appear offline-first. Reconsider it only after the Ledger has a tested command-queue/review model that preserves the same explicit acceptance guarantees.

## Sources

- [RFC 8252: OAuth 2.0 for Native Apps](https://datatracker.ietf.org/doc/html/rfc8252)
- [RFC 9700: Best Current Practice for OAuth 2.0 Security](https://datatracker.ietf.org/doc/html/rfc9700)
- [OWASP Mobile Application Security Verification Standard](https://mas.owasp.org/MASVS/)
- [OWASP MASWE-0006: sensitive data in private storage](https://mas.owasp.org/MASWE/MASVS-STORAGE/MASWE-0006/)
- [Expo SecureStore](https://docs.expo.dev/versions/latest/sdk/securestore/)
- [Expo SQLite](https://docs.expo.dev/versions/latest/sdk/sqlite/)
- [Expo push notifications](https://docs.expo.dev/push-notifications/overview/)
- [Expo: sending push notifications](https://docs.expo.dev/push-notifications/sending-notifications/)
- [Expo: deploy updates](https://docs.expo.dev/eas-update/deployment/)
- [Expo: using EAS Update](https://docs.expo.dev/build/updates/)
- [AWS: Fargate for Amazon ECS](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/AWS_Fargate.html)
