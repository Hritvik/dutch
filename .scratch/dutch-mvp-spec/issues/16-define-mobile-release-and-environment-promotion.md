Type: grilling
Status: resolved
Blocked by: 01, 11

# Define mobile release and environment-promotion model

## Question

How should Dutch separate development, staging, and production services; distribute Expo development/preview/production builds; manage configuration and signing; promote compatible API/client releases; and roll back safely without corrupting financial history?

## Answer

### Environments and access

- Use isolated local, shared development, staging, and production environments. Production is in a separate AWS account from non-production; staging is production-like in topology/configuration but may use lower capacity. Each has separate databases, queues, buckets, secrets, IAM/OIDC clients, signing material, and push credentials. Production data never enters lower environments.
- Mobile configuration is environment-specific at build/update time. Only intentionally public values (API base URL, release channel, observability endpoint/project identity) enter the client configuration; no secret, database, provider credential, or privileged API key enters an app bundle. EAS/Apple/Google/AWS access is least-privilege, MFA-protected, and audited.

### Expo build and update flow

- Use Expo development builds with `expo-dev-client` for engineers, internal-distribution preview builds for QA/product, a staging build/channel for production-like validation, and store-distributed production builds for users. iOS uses TestFlight for pre-release; Android uses the appropriate Play testing track before staged production rollout.
- Configure EAS Build profiles/channels for `development`, `preview`, `staging`, and `production`. Use `runtimeVersion` with the `appVersion` policy: a native-library, native configuration, Expo SDK, or other runtime change increments app version and requires new iOS/Android binaries; only JavaScript/assets compatible with that runtime ship as EAS Updates. This follows [Expo’s current deployment guidance](https://docs.expo.dev/eas-update/deployment/) and [runtime-compatibility guidance](https://docs.expo.dev/build/updates/).
- A pull request produces lint/type/unit/API-contract checks and, when appropriate, a development/preview update or internal build. A release candidate is tested on physical iOS and Android devices against staging, including authentication, claiming, Expense revision, settlement, sync conflict, offline draft, access revocation, and notification journeys.
- Publish an OTA update to staging first. Promote the identical verified bundle to production rather than rebuilding it; Expo documents republishing the tested staging update to the production channel for this purpose. Production rollout begins with internal testers and then a store-controlled staged binary release where relevant. [Expo preview/update flow](https://docs.expo.dev/eas-update/preview/) and [update promotion](https://docs.expo.dev/eas-update/deployment/).

### Compatibility, promotion, and rollback

- Deploy backward-compatible backend/API changes before the client that consumes them. v1 changes are additive; clients support the currently released and immediately previous supported app runtime during rollout. API removal/change follows explicit deprecation and client-adoption measurement, never an immediate server cutover.
- Database and event changes use expand/contract evolution: add fields/tables/read paths; backfill/dual-read as needed; deploy writers; wait for all supported clients and consumers; then retire old paths. Ledger entries, Activity, idempotency outcomes, and outbox facts are immutable and are never rolled back or rewritten by deployment.
- Feature flags provide server-side kill switches for nonfinancial UI/flows. A bad compatible OTA is rolled back by republishing the last known-good update to the same channel; a bad binary is halted/rolled back through the store track. A bad backend release rolls back stateless application code only when schema/event compatibility permits; otherwise deploy a forward repair. Financial corrections remain attributable business commands, not release rollback scripts.
- A promotion requires green CI/security checks, compatible migration review, successful staging device E2E, observed health/error/queue/outbox checks, approved release notes, and a named rollback/incident owner. Record release version, runtime version, Git commit, EAS update/build IDs, API version, migration set, environment, and approval in the release log.
