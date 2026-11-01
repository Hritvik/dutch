# Dutch architecture benchmark against current practice

**Scope.** This is an evidence-based review of the settled Dutch MVP decisions, not a redesign. “State of the art” here means current, documented production patterns and standards from the owning organizations (AWS, PostgreSQL, IETF). The product’s core financial rules are also assessed for the properties production financial systems require: exactness, reproducibility, attribution, and safe retry.

## Executive assessment

Dutch is aligned with a conservative, production-grade financial-system architecture: authoritative relational writes, immutable evidence, derived projections, database-per-service ownership, transactional outbox, idempotent consumers, and explicit optimistic concurrency. The main risks are not conceptual gaps; they are implementation discipline and MVP operational cost. In particular: write the Ledger transaction/concurrency design before code, treat FIFO as a transport aid rather than financial correctness, and keep independently deployable services logically separate even when their databases initially share one RDS instance.

| Decision | Benchmark result | Material caveat |
| --- | --- | --- |
| Separate Gateway, IAM, UMS, Ledger, Notification; database-per-service ownership | **Aligned** | More operational overhead and cross-service read complexity than a modular monolith. |
| Ledger as immutable obligations plus rebuildable balance/activity projections | **Strongly aligned** | Reversals, projection rebuilds, and algorithm versions must be tested as first-class behavior. |
| Exact multi-payer allocation, integer minor units, deterministic rounding | **Strongly aligned** | `int64` overflow, currency registry changes, and allocation ordering need explicit guards. |
| Local transaction + outbox + idempotent consumers | **Strongly aligned** | Outbox relays can publish duplicates; ordering is only per aggregate. |
| REST command API, durable idempotency, ETags and cursor sync | **Aligned** | HTTP specifies `412` for failed `If-Match`; Dutch’s deliberate `409` contract should be documented as an API convention. |
| AWS RDS PostgreSQL Multi-AZ, SNS/SQS FIFO, Fargate, Valkey and S3/KMS | **Aligned for an MVP** | FIFO’s broker-level deduplication is time-bounded; database idempotency remains mandatory. |

## 1. Service boundaries, ownership, and IAM

### Assessment

The five-service model and rule that no service reads another service’s database are current microservice practice. AWS’s database-per-service guidance says individual stores are private to the service and persistent data is accessed through APIs; it specifically calls out loose coupling, independent scaling, and separate security/compliance needs as benefits. It also names the expected cost: cross-service transactions and queries become harder, requiring API composition, CQRS, or event-fed aggregates. [AWS database-per-service pattern](https://docs.aws.amazon.com/prescriptive-guidance/latest/modernization-data-persistence/database-per-service.html)

Dutch’s local UMS member projection in Ledger is therefore a well-chosen CQRS-style read boundary. Local signed-token validation also avoids turning IAM into the availability dependency of every financial request. This matches JWT guidance: use explicit algorithm verification and validate issuer/audience; RFC 8725 further recommends issuer-specific validation rules to avoid cross-JWT confusion. [RFC 8725 — JSON Web Token Best Current Practices](https://datatracker.ietf.org/doc/rfc8725)

### Risks and targeted recommendations

1. **Operational surface area is the real trade-off.** Five deployables are justified by PII/credential and financial-data isolation, but at MVP traffic they are costlier than a modular monolith. Preserve the settled boundaries, while using one repository, shared contract test fixtures, and a deliberately thin Gateway. Do not allow the Gateway to become an orchestration service.
2. **A shared RDS *instance* is acceptable only with real isolation.** Separate databases/roles, network rules, migrations, backups, and no cross-database queries preserve the important boundary. Document the trigger to split physical instances (noisy-neighbour, recovery-isolation, compliance, or capacity evidence).
3. **Make token validation a tested policy.** Pin allowed asymmetric algorithms, validate `iss`, `aud`, `exp`, `nbf`, and key rotation behavior; never infer authorization solely from a token’s stale membership claims. Ledger remains final authority from its projected membership state.

## 2. Immutable financial ledger and multi-payer allocation

### Assessment

Dutch’s entries-as-truth and balances/plans-as-rebuildable projections is stronger than storing editable “who owes whom” totals. It makes every balance explainable from immutable evidence, supports corrections by exact reversal/replacement, and protects historical calculations from future simplification-algorithm changes. This is the right fit for a financial record even though Dutch is not a payment or custody system.

The money model is equally sound: ISO-currency + signed integer minor units, no binary floating point; contribution total and share total must each equal the expense total; deterministic largest-remainder allocation with the tie order stored. Multi-payer is handled rigorously by calculating each member’s net contribution-minus-share, then deriving directed obligations. Keeping that expense-level allocation separate from group-wide simplification avoids conflating evidence with a suggested payment plan.

### Risks and targeted recommendations

1. **Run every accepted Ledger command in one clearly specified transaction.** PostgreSQL’s serializable isolation permits only executions equivalent to some serial order, but PostgreSQL explicitly requires applications to retry serialization failures (`SQLSTATE 40001`). [PostgreSQL transaction isolation](https://www.postgresql.org/docs/current/transaction-iso.html) Choose either serializable transactions with bounded server retry or carefully locked/versioned rows; prove the selected strategy against concurrent settlement, edit, and idempotency-key races.
2. **Keep a strict event/revision linkage.** Each reversal should identify the exact original entry/revision and be constrained to one effective reversal. Persist the allocation and simplification algorithm version plus stable tie order; never rebuild history using a newer algorithm.
3. **Add money-boundary tests.** Reject values outside `int64`, invalid ISO scale, duplicate canonical members, zero/self entries, and percentages not totaling 10,000 basis points. Property-test allocation conservation and reversal conservation across generated participant/contribution combinations.
4. **Clarify settlement semantics in implementation.** An uncapped settlement is valid evidence, but allocation validation should distinguish “user recorded an external payment” from “this payment fully clears a scope.” The system should not reject overpayment, yet it must atomically recompute each scope projection and show the resulting reversed balance.

## 3. Outbox, events, and projections

### Assessment

The local transactional outbox is the standard remedy for a database update plus event publish (“dual write”). AWS recommends writing the state and outbox record in the same transaction, publishing only committed rows, preserving the producer’s event sequence where required, and making consumers idempotent because duplicates are possible. [AWS transactional outbox pattern](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)

Dutch’s immutable outbox facts, event IDs, consumer processed-event record, per-aggregate order, and asynchronous Notification are directly aligned. Importantly, the outbox gives atomic *source write + durable intent to publish*—not end-to-end exactly once—which is precisely why Dutch’s Ledger truth does not depend on Notification delivery.

### Risks and targeted recommendations

1. **Use an outbox sequence per producing aggregate and claim rows safely.** Polling relays should use short transactions, locked/claimed rows, retry metadata, and monitored lag. A relay crash after broker publish but before marking sent is normal; publish duplicates and rely on consumer idempotency.
2. **Use full snapshots or versioned events deliberately.** Ledger consumers that need current Member authorization should be able to reject old/out-of-order versions; all consumer updates must be monotonic by aggregate version, not merely arrival time.
3. **Treat DLQ replay as a controlled production operation.** Preserve event ID/correlation ID, repair the consumer or payload, and replay without bypassing dedupe. Alert on outbox age, queue age, and DLQ depth separately from financial API correctness.

## 4. HTTP commands, idempotency, concurrency, and sync

### Assessment

Explicit financial commands are a good complement to REST reads: they make revision/delete/restore/settlement intent apparent and map cleanly to immutable Ledger actions. Durable client-generated idempotency keys are current HTTP API practice. The active IETF HTTPAPI draft defines `Idempotency-Key` specifically to make non-idempotent `POST`/`PATCH` fault tolerant, says clients must not reuse a key for a different payload, and suggests `422` for that misuse. It is a work-in-progress rather than an RFC, so Dutch should treat it as a compatibility reference—not normative protocol law. [IETF Idempotency-Key draft](https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header-07)

ETag/`If-Match` is also a standards-aligned lost-update control. RFC 9110 says a server must evaluate `If-Match` before the change and must not perform the method when it is false; it identifies `412 Precondition Failed` as the normal response. [RFC 9110 — conditional requests](https://datatracker.ietf.org/doc/rfc9110/)

Cursor sync is appropriate for offline clients: a signed, member-scoped, opaque position means the client does not interpret storage sequence details and can recover via an explicit full resync. Tombstones make access revocation/cache removal unambiguous.

### Risks and targeted recommendations

1. **Keep the durable idempotency scope exactly as decided: actor + command type + key + request hash.** Atomically record an in-progress/final outcome and return the original representation for identical retries. A lost response must never create a second expense.
2. **Make `409 stale_version` an intentional documented deviation/abstraction.** RFC 9110’s conditional-header semantics point to `412`; retaining Dutch’s richer `409` with latest snapshot is viable, but API consumers must be told it is a domain conflict mapping. Alternatively return `412` and use the same error body. Do not use both arbitrarily.
3. **Cursor retention and authorization must be bound together.** On every sync verify the cursor’s identity/scope and return `410 sync_cursor_expired` for retention loss or no-longer-valid checkpoint; return access tombstones for known revoked scopes. The gateway must not put PII or balances inside the cursor.

## 5. AWS runtime, data, and messaging

### Assessment

RDS PostgreSQL supports Multi-AZ, VPC placement, TLS, snapshots, and point-in-time restoration; automated backup recovery is possible to any point inside the selected retention window. [Amazon RDS for PostgreSQL](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_PostgreSQL.html) and [RDS automated backups/PITR](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_WorkingWithAutomatedBackups.html). That is a sensible lower-operations baseline for a single-region MVP, provided restore drills—not only backup configuration—validate the RPO/RTO.

SNS FIFO + SQS FIFO is appropriate where Dutch needs per-aggregate order. AWS documents that ordering applies within a message group, while different groups may be processed out of order. SNS FIFO deduplication uses a five-minute deduplication interval; it is not a substitute for durable consumer dedupe, especially across relay retries and replays. [SNS FIFO delivery](https://docs.aws.amazon.com/sns/latest/dg/fifo-message-delivery.html) and [SNS FIFO deduplication](https://docs.aws.amazon.com/sns/latest/dg/fifo-message-dedup.html).

Fargate, Valkey only for disposable abuse state, and S3/KMS only for private exports/secrets are appropriately managed-service choices. Fargate removes EC2 cluster management and provides task-level CPU, memory, network, and IAM configuration; its required `awsvpc` networking also permits security groups per task. [AWS Fargate](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/AWS_Fargate.html) and [ECS networking](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/security-network.html). S3 Block Public Access, lifecycle expiry, and SSE-KMS align with the export boundary, while Secrets Manager/KMS can encrypt secret values and restrict access with least-privilege key policies. [S3 Block Public Access](https://docs.aws.amazon.com/AmazonS3/latest/userguide/access-control-block-public-access.html), [S3 lifecycle](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lifecycle-mgmt.html), [Secrets Manager data protection](https://docs.aws.amazon.com/secretsmanager/latest/userguide/data-protection.html), and [KMS least privilege](https://docs.aws.amazon.com/kms/latest/developerguide/least-privilege.html). Their restriction away from Ledger truth is a key strength.

### Risks and targeted recommendations

1. **Model FIFO groups as aggregate streams.** Use Group/Expense/Member IDs where sequence matters; do not expect order between aggregates. Ensure each consumer’s projection update tolerates cross-aggregate reordering.
2. **Do not claim “exactly once” globally.** Broker dedupe has conditions and a bounded window. The durable outbox/inbox/idempotency design—not SNS/SQS alone—provides the financial safety property.
3. **Test restore and failover.** RDS PITR restores a new instance/cluster; the runbook must cover application configuration, event/outbox reconciliation, data verification, and timing against the stated 15-minute RPO/4-hour RTO.
4. **Right-size the launch architecture.** Two AZs and redundant Gateway/Ledger tasks are good; IAM/UMS/Notification availability targets, connection pool sizing, database connection limits, and queue throughput/message-group cardinality need measured capacity tests before production. Use private subnets, least-privilege task roles, and planned task recycle/deploys (Fargate platform changes require new task starts).
5. **Keep Valkey disposable.** AWS documents asynchronous replication and potential loss on a single node/failover; its present restriction to rate-limit/abuse counters is exactly right. [ElastiCache replication](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/Replication.html)

## Overall conclusion

The settled design compares well with current high-assurance event-driven and financial API practice. Its most valuable choices are the immutability boundary, exact multi-payer allocation, durable idempotency, and local transactional outbox. The implementation should resist “simplifications” that make projections authoritative, allow cross-service database reads, or rely on FIFO delivery for correctness. The targeted recommendations above are safeguards and test obligations; they do not require changing the settled product or architecture choices.
