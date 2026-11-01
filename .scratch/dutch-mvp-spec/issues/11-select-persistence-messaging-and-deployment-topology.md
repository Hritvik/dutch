Type: research
Status: resolved
Blocked by: 03, 05, 10

# Select persistence, messaging, and deployment topology

## Question

Which persistence engines, ownership pattern, event transport, caching layer, object or secret storage, and deployment topology satisfy the settled service and correctness contracts with the lowest justified operational burden for the MVP?

## Answer

Research report: [persistence-messaging-deployment-topology.md](../../../research/persistence-messaging-deployment-topology.md)

- Deploy the MVP in one AWS region selected for the launch market and data-residency requirements. Use ECS on Fargate behind a public ALB/WAF; all services and databases remain in private subnets. The Gateway, IAM, UMS, Ledger, and Notification worker are separate immutable container services with least-privilege task roles. Production uses two Availability Zones and at least two Gateway and Ledger tasks; pre-production may explicitly accept single-AZ/single-task risk.
- Use Amazon RDS PostgreSQL Multi-AZ. Initially this is one physical RDS instance with a separate database, login role, migration stream, backup/restore test, and network policy for IAM, UMS, Ledger, and Notification. This preserves service-owned data and removes cross-service credentials while avoiding four managed database instances at MVP scale. Split to physical instances when recovery isolation, compliance, or noisy-neighbour evidence justifies it.
- The Ledger database is the financial source of truth. Each owning service writes source state, rebuildable projections, idempotency result, and immutable outbox row in one local transaction. A small relay polls unpublished outbox rows with transaction-safe row claiming and backoff; no distributed transaction, synchronous broker publish, CDC/logical decoding, or cache participates in financial commits.
- Use one SNS FIFO topic per producing boundary (`ums-events.fifo`, `ledger-events.fifo`) with a dedicated SQS FIFO queue per consumer and DLQ. Publish stable event IDs as deduplication IDs and aggregate IDs as message groups; consumers record processed event IDs and remain idempotent. This gives required per-aggregate ordering without operating Kafka.
- Use ElastiCache Serverless for Valkey from launch, but only for disposable, cross-task security state: rate limits and short-lived OTP/claim-abuse counters. It stores no balances, sessions, idempotency outcomes, Member truth, or outbox state.
- Use a private S3 bucket with blocked public access, TLS-only policy, SSE-KMS, lifecycle deletion, and application-authorized one-time grants for expiring Privacy Exports. S3 holds no financial source data. Use Secrets Manager plus KMS for service/provider/database secrets, injected through task roles rather than images, source, or client bundles.
- CloudWatch logs, metrics, traces, and alarms cover queue age/DLQ depth, outbox lag, failed exports, database connection exhaustion, authentication/abuse signals, and service health. Start with automated backups and point-in-time recovery; set final retention, RPO/RTO, and primary region through launch operations policy before production provisioning.
- Explicitly excluded from MVP: shared schemas or direct database reads; self-managed PostgreSQL, Redis, Kafka, Kubernetes, or service mesh; cache- or broker-backed financial truth; global active-active Ledger writes; and receipt/object upload beyond Privacy Exports.
