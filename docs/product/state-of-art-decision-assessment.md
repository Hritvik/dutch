# State-of-the-art decision assessment

This assessment compares Dutch's settled MVP against current primary-source product documentation, platform standards, IETF OAuth guidance, PostgreSQL, and AWS guidance. It is a review, not a decision rewrite.

Research evidence:

- [Product benchmark](../../research/state-of-art-product-benchmark.md)
- [Architecture benchmark](../../research/state-of-art-architecture-benchmark.md)
- [Mobile, offline, security, and release benchmark](../../research/state-of-art-mobile-security-benchmark.md)

## Overall conclusion

Dutch is strongly aligned with state-of-the-art shared-expense products and conservative production financial-system practice. It is notably stronger than category norms in immutable financial evidence, multi-payer allocation, privacy boundaries, explicit settlement allocations, and cautious conflict handling.

No major decision needs reversal. The principal trade-off is deliberate: Dutch gives up some consumer-app convenience and operational simplicity to preserve financial clarity and auditability. The implementation must protect that advantage with the safeguards below.

## Decision assessment

| Major decision | Assessment | State-of-the-art comparison | Required guardrail |
| --- | --- | --- | --- |
| Groups for ongoing sharing; Direct Expenses for one-off sharing | Keep | This is the standard organization model in Splitwise and comparable products. | In UI, call a multi-person Direct Expense a “one-off split” or lead with participant names so “Direct” is not mistaken for two-person-only. |
| Equal/exact/percentage splits and multi-payer contributions | Keep | Flexible paid/owed shares and multiple payers are supported by capable products. Dutch's stored exact allocations are stronger. | Default the composer to one payer + equal split; reveal additional payers and advanced split methods intentionally; always show paid/share totals. |
| Integer minor units, fixed Group currency, deterministic rounding | Keep | Per-currency, no-conversion simplification is conservative and trust-preserving. | Test `int64` bounds, ISO scale changes, 10,000-basis-point totals, duplicate canonical members, and deterministic remainder/tie behavior. |
| Immutable Obligation Entries, revisions/reversals, rebuildable projections | Keep | This matches production ledger/event-sourcing properties while avoiding projections as financial truth. | Specify one Ledger transaction strategy and test reversal-once, rebuild reconciliation, algorithm version, and concurrent command races. |
| Group debt simplification as a derived recommendation | Keep | Category products reduce transfers without changing net positions; Dutch preserves stronger evidence. | Label it “Suggested transfers,” never a recorded payment; recompute after any settlement. |
| Uncapped scoped and multi-scope settlements | Keep with UX guardrail | Scope-specific and cross-scope settling are established, but competitor documentation shows users find automatic balancing opaque. | Before confirmation, state scope(s), before/after impact, and any reversal. For Settle all, show one parent summary and hide allocation detail behind drill-in. |
| Open Active-Member correction plus immutable Activity | Keep | Collaborative additions are normal; Dutch is stronger in explainability and privacy. | Make destructive corrections explicit, name the affected scope, preserve revision evidence, and never expose raw contact identifiers. |
| IAM/UMS/Ledger/Notification/Gateway boundaries | Keep with cost discipline | Database-per-service and event-fed local projections are mature microservice practice. | Preserve a thin Gateway and shared contract fixtures in one repository; enforce database credentials/network isolation even while databases share one RDS instance. |
| Transactional outbox, FIFO transport, idempotent consumers | Keep | This is a robust answer to database-commit/publish failure. | Treat transport as at-least-once: safe outbox row claims, inbox/event-ID dedupe, aggregate-version monotonicity, monitored lag, and controlled DLQ replay. FIFO alone is not financial correctness. |
| REST commands, durable idempotency, version conflicts, cursor sync | Keep with one protocol clarification | Explicit commands, idempotency, preconditions, and opaque checkpoint sync are current reliable-client patterns. | Choose and document one conditional-conflict convention: retain `409 stale_version` with latest snapshot as Dutch's domain mapping, or use HTTP `412`; do not vary by endpoint. Bind cursors to identity/scope and preserve a tested full-resync path. |
| React Native + TypeScript + Expo development builds | Keep | Current Expo-supported production workflow with a viable native escape hatch. | Keep Expo SDK current, run dependency/New Architecture diagnostics in CI, and treat any native/config change as a new runtime/binary. |
| SQLite projection + SecureStore sessions + online-only financial submission | Keep | This is integrity-first rather than maximal offline convenience; it is appropriate for shared financial records. | Classify every local field and prohibit tokens/raw identifiers/internal entries in SQLite; test Android backup exclusion, iOS reinstall behavior, logout/account switch, and access-revocation wipe on physical devices. |
| OAuth code + PKCE, rotating refresh, server authorization | Keep | This meets current native-app OAuth and mobile-security guidance. | External browser/system auth only; S256 PKCE; exact redirect URI matching; no embedded client secret; token-family revocation on refresh reuse; recent-auth gates for export/recovery/deletion. |
| Durable Activity and privacy-preserving push | Keep | Provider push is inherently best-effort; Dutch correctly makes Activity authoritative. | Process push receipts, batch/throttle delivery, remove invalid tokens, and refresh authoritative state after deep links. |
| AWS RDS/Fargate/SNS-SQS FIFO/Valkey/S3-KMS | Keep | Managed services lower operational burden while preserving service ownership. | Exercise PITR/failover/outbox reconciliation against RPO/RTO; measure connection and FIFO-group throughput; keep Valkey disposable and make physical-RDS split triggers observable. |
| EAS channels/runtime-gated OTA promotion and forward-only migrations | Keep | This aligns with current Expo release guidance. | Record runtime/environment/channel/bundle hash/commit/API compatibility; test the exact staging bundle/config promoted to production; use expand/contract migrations and forward repair for financial history. |

## Prioritized additions before implementation

1. Write the Ledger transaction/concurrency specification and tests before service code: serializable retry (or explicit locking), idempotency races, concurrent Settlement/edit behavior, and exact reversal linkage.
2. Add user-comprehension acceptance tests for one-payer default, multi-payer contribution, Group simplification, overpayment, and Settle all. Measure whether people can predict balance effects before confirmation.
3. Make local-storage/security behavior executable in device tests: SecureStore persistence/backup differences, cache wiping, external OAuth session, refresh-token reuse, and exact redirect handling.
4. Make event and push operations testable: duplicate/out-of-order delivery, DLQ replay, provider receipts, invalid-token removal, and unchanged Activity/Ledger truth after provider failure.
5. Add release provenance and compatibility checks to CI before the first production-capable build.

## Deliberate non-adoptions

- Do not replace the Ledger with a cache, broker, or generic event stream as financial authority.
- Do not use broker FIFO/deduplication as a substitute for database idempotency.
- Do not add offline auto-submission just to match consumer offline-first marketing; reconsider only after an equally explicit, reviewable command-queue model is proven.
- Do not collapse identity, profile, and financial ownership into one service merely to reduce initial deployment count unless the product explicitly re-prioritizes delivery speed over the settled trust boundaries.
