# Persistence, messaging, and deployment topology research

## Recommendation

Use a single AWS-region managed-services deployment for the MVP: **Amazon RDS for PostgreSQL**, **SNS FIFO topics plus per-consumer SQS FIFO queues**, **ECS on Fargate**, **S3**, **Secrets Manager/KMS**, and **CloudWatch**. Introduce **ElastiCache Serverless (Valkey)** only for globally coordinated, non-authoritative rate limits and short-lived anti-abuse state.

This preserves the settled service boundaries without prematurely operating Kubernetes, self-managed Kafka, Redis, or database clusters. It deliberately treats the Ledger database and its transactional projections as the financial source of truth; a broker or cache is never part of a financial commit.

## Persistence and ownership

| Service | Authoritative store | Owns | MVP rule |
| --- | --- | --- | --- |
| IAM | PostgreSQL database and role | Identities, credentials, verified identifiers, sessions and refresh-token state | No other service queries it. |
| UMS | PostgreSQL database and role | Member profiles, placeholders, claim/merge state | Publishes minimum-reference lifecycle facts. |
| Obligation Ledger | PostgreSQL database and role | Groups, memberships, expenses/revisions, settlements, immutable entries, balance/activity projections, idempotency records and outbox | The only financial source of truth. |
| Notification | PostgreSQL database and role | Device tokens, notification preferences, delivery attempts and deduplication | Delivery cannot block a Ledger commit. |
| Gateway | None | Route/rate-limit configuration only | No domain persistence. |

Start with **one RDS for PostgreSQL Multi-AZ DB instance** and one separate PostgreSQL database, login role, migration stream, backup-restore test, and network policy for each owning service. This is *logical* database isolation, not a shared application schema: services have no cross-database credentials and never join/read another service's tables. It limits the MVP’s managed-database surface while retaining an extraction path to one RDS instance/cluster per service when noisy-neighbour, compliance, or independent recovery needs justify it.

RDS automated backups can be retained for a configured period and support point-in-time recovery during that period; enabling a non-zero retention period is required. In Multi-AZ RDS, AWS automatically fails over to the standby if infrastructure fails, typically in 60–120 seconds. Those properties remove database host, backup, and failover operation from the MVP team, but they do not eliminate application retry and recovery design. [RDS backups](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_WorkingWithAutomatedBackups.html) [RDS retention](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_WorkingWithAutomatedBackups.BackupRetention.html) [RDS Multi-AZ failover](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.MultiAZ.Failover.html)

Each owned database uses ordinary relational constraints, serializable/appropriately locked Ledger writes, an append-only financial model, and a transactional `outbox_event` table. In the *same local transaction* that accepts a source change, the service writes the source data/projection, idempotency outcome where applicable, and an immutable outbox row. A relay publishes committed rows and marks them published only after broker acknowledgement. This is an application pattern rather than a managed cross-service transaction; it closes the database-commit/publish crash window while preserving the existing at-least-once/idempotent-consumer contract.

Implement the first relay by polling unpublished outbox rows in small batches using transaction-safe row claiming (for example, `FOR UPDATE SKIP LOCKED`) and backoff. Do not adopt PostgreSQL logical-decoding/CDC for this MVP: it adds custom RDS configuration and a restart, and a lagging replication slot can retain WAL and consume database storage. PostgreSQL logical decoding is a valid later choice for a separate analytical/CDC requirement, not a prerequisite for a robust transactional outbox. [PostgreSQL logical decoding](https://www.postgresql.org/docs/current/logicaldecoding.html) [RDS logical-decoding setup](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_MultiAZDBCluster_LogicalRepl.html) [RDS WAL-retention warning](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_PostgreSQL.Replication.ReadReplicas.LogicalDecoding.html)

## Event transport

Use one **SNS FIFO topic per producing boundary** (initially `ums-events.fifo` and `ledger-events.fifo`) and a dedicated **SQS FIFO queue per consumer**. The outbox relay publishes the stable event ID as the deduplication ID and an aggregate-specific `MessageGroupId` (for example, Member ID for UMS facts and Group/Direct-scope ID for Ledger facts). Each consumer stores processed event IDs transactionally before/with its effect and has a DLQ, alert, and controlled replay procedure.

This choice directly supports the settled requirement that ordering is only meaningful per aggregate:

- SNS FIFO delivers to subscribed SQS FIFO queues in the order published and without duplicates under normal operation; standard queue subscribers can be reordered and duplicated. [AWS SNS FIFO ordering](https://docs.aws.amazon.com/sns/latest/dg/fifo-topic-message-ordering.html)
- Message groups make ordering serial only within a group, while allowing unrelated aggregates to progress concurrently. [AWS SNS FIFO message grouping](https://docs.aws.amazon.com/sns/latest/dg/fifo-message-grouping.html)
- SQS FIFO preserves send/receive ordering and supports message groups; consumers must still be idempotent because the application’s own side effect may commit before an acknowledgement, then be retried. [AWS SQS FIFO](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-fifo-queues.html)

Do **not** use a synchronous broker publish in the request transaction, distributed transactions, or broker delivery as proof that the consumer applied a fact. Do **not** use a standard SQS queue for the UMS-to-Ledger membership projection or other paths where per-aggregate ordering matters: standard queues are at-least-once and can redeliver. [AWS SQS standard delivery](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/standard-queues-at-least-once-delivery.html)

Trade-off: SNS/SQS FIFO imposes explicit message-group and topic/queue configuration and is less suited to broad ad-hoc event analytics than Kafka. It is the smaller operational choice for the current two producer/consumer relationships. If the event estate becomes many-to-many or needs long broker-native replay/stream processing, reassess EventBridge/Kinesis/Kafka; do not introduce them solely for the MVP outbox.

## Cache and object storage

- **ElastiCache Serverless for Valkey:** use only for distributed rate-limit counters, temporary OTP/claim-attempt throttling, and short-lived replay/abuse state. It must not hold financial balances, idempotency outcomes, Member truth, session truth, or an outbox. ElastiCache Serverless avoids capacity planning, hardware management, and cluster design, which makes it appropriate only when the security controls require cross-task coordination. [AWS ElastiCache](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/WhatIs.corecomponents.html)
- **S3:** a private, blocked-public-access bucket for expiring Privacy Export archives (and, only if later added, receipt blobs). Store financial metadata and authorization in the owning database; issue a short-lived, single-use download only after authorization. Configure SSE-KMS, lifecycle expiry/deletion, versioning only where recovery policy needs it, and an explicit bucket policy denying non-TLS access. S3 encrypts uploads at rest by default, while SSE-KMS supplies finer key-policy control. [AWS S3 encryption](https://docs.aws.amazon.com/AmazonS3/latest/userguide/bucket-encryption.html)
- **Secrets Manager + KMS:** store database credentials, OAuth signing/verification material, provider credentials, and service configuration secrets; inject them through ECS task roles rather than images or source. Secrets Manager encrypts secret values at rest with KMS and supports customer-managed KMS keys when finer authorization is needed. [AWS Secrets Manager data protection](https://docs.aws.amazon.com/secretsmanager/latest/userguide/data-protection.html)

## Deployment topology

```text
React Native client
       |
    WAF + public ALB
       |
Gateway service (ECS/Fargate, 2 tasks in production)
       | private service-to-service TLS/mTLS
       +-- IAM service (ECS/Fargate) ------ IAM PostgreSQL DB
       +-- UMS service (ECS/Fargate) ------ UMS PostgreSQL DB -- outbox --> SNS FIFO
       +-- Ledger service (ECS/Fargate) --- Ledger PostgreSQL DB -- outbox --> SNS FIFO
       |
       +-- Notification worker (ECS/Fargate) <- SQS FIFO subscriptions --> Notification DB

private subnets: RDS, ECS services, SQS/SNS VPC endpoints, Secrets Manager
supporting: S3 export bucket, optional ElastiCache Serverless, CloudWatch logs/metrics/alarms
```

Deploy each logical service as its own immutable container image in ECR and ECS/Fargate service. Run the UMS/Ledger outbox relay in the owning service process or a separately scaled worker using the same service task role; it reads only that service’s database. Run Notification as a queue-driven worker. Keep databases and services in private subnets; only the ALB is public. Use least-privilege task roles, separate security groups, TLS externally and internally, health checks, alarms for queue age/DLQ depth/outbox lag/database connection exhaustion, and infrastructure-as-code.

Fargate runs containers without operators provisioning, configuring, or scaling EC2 clusters, while tasks supply the requested CPU/memory, networking, and IAM configuration. [AWS Fargate](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/AWS_Fargate.html)

For a production MVP, deploy services across two Availability Zones with at least two Gateway and Ledger tasks; size IAM/UMS/Notification from observed load but avoid a single point of failure for the public path. A budget-constrained pre-production environment may use one task per service and a single-AZ database, explicitly accepting outage risk. Use one region initially; cross-region active-active financial writes would complicate the Ledger’s ordering and is out of scope.

## Explicit non-recommendations for MVP

- No shared service schema, direct database reads, or cross-service database transaction.
- No cache-backed financial projection or broker-backed source of truth.
- No self-managed PostgreSQL/Redis/Kafka, Kubernetes, or service mesh.
- No global active-active Ledger or cross-region write replication.
- No receipt upload subsystem unless it is added to the product scope; S3 is initially for privacy exports.

## Decisions required from product/architecture

1. Accept the one physical RDS instance with per-service logical databases as the MVP cost/operations trade-off, or require separate physical RDS instances from day one for stronger blast-radius isolation.
2. Accept SNS FIFO + SQS FIFO for durable, per-aggregate ordered event delivery, with application-level outbox/consumer idempotency.
3. Decide whether globally consistent abuse/rate limits justify ElastiCache Serverless at launch; it can be deferred if the first implementation has one API task/environment, but should be introduced before horizontal scale.
4. Set the production region, RDS backup-retention/RPO/RTO targets, and privacy-export retention period; these are policy decisions, not infrastructure defaults.
