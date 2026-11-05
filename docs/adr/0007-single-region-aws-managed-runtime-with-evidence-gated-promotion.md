# ADR 0007: Use a single-region managed AWS runtime with evidence-gated promotion

## Status

Accepted. Amended by ADR 0010: the runtime shape below is the Dutch requirement,
but provisioning is delegated to a platform capability rather than owned here.

## Context

Dutch needs financial correctness and privacy controls at MVP scale without operating Kubernetes, Kafka, a multi-region write protocol, or a separate database fleet per service from day one.

## Decision

Run independently deployed Fargate services in `ap-south-1`, private networking, RDS PostgreSQL Multi-AZ with logical per-service isolation, SNS/SQS FIFO, and managed secrets/KMS/S3. Provision through OpenTofu and promote immutable image/mobile artifacts only with migration, device, recovery, observability, and security evidence.

## Consequences

The shared physical RDS instance is an accepted MVP blast-radius/cost trade-off with measured split triggers. Single-region outages remain possible, but backups, runbooks, and restore/reconciliation drills establish a bounded recovery posture. Release work is more deliberate because an accepted finance change cannot be casually rolled back at the database layer.
