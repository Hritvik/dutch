Type: grilling
Status: resolved
Assignee: Codex

# Design runtime topology, observability, and delivery

## Question

What AWS infrastructure boundaries, configuration/secrets model, delivery pipeline, telemetry, alerting, backup/restore tests, and release safeguards concretely meet Dutch's India-first operational and mobile release requirements?

## Answer

Dutch will run in `ap-south-1` with development/staging/production AWS accounts, OpenTofu provisioning, private ECS/Fargate services, ALB/WAF Gateway, logically isolated RDS Multi-AZ service databases, SNS/SQS FIFO, KMS/Secrets Manager, and private expiring S3 exports. Artifacts are immutable digests, migrations are one-off compatible tasks, and EAS build/update channels carry complete provenance.

Observability separates Ledger correctness from asynchronous delivery. Production promotion requires staging device evidence, API/event/migration compatibility, restore/projection/outbox reconciliation, queue/DLQ recovery, security/redaction checks, and runbook/alert validation. RDS retains 35 days of backups against the settled RPO 15-minute/RTO 4-hour objectives.

See [runtime, observability, and delivery](../../../docs/architecture/runtime-observability-delivery.md) and [ADR 0007](../../../docs/adr/0007-single-region-aws-managed-runtime-with-evidence-gated-promotion.md).

## Workflow observations

- The prior topology decision named services; the architecture phase made promotion evidence and restore/reconciliation operationally testable. A future SDLC skill should require recovery evidence before declaring a design release-ready.
- Environment/account isolation, artifact provenance, and least-privilege delivery rules are reusable orchestration gates rather than application-specific workflow detail.
