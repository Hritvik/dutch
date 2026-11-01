Type: grilling
Status: resolved
Blocked by: 11

# Define operational resilience policy

## Question

What launch-region, availability, backup-retention, restore-testing, RPO/RTO, observability, incident-response, and cost-guardrail policies make the selected managed AWS topology operationally credible for the MVP?

## Answer

### Launch baseline

- Planning assumption: the initial launch region is AWS `ap-south-1` (Mumbai), with all MVP writes in that one region. This is a deployment parameter, not a data-model assumption; if the launch market or residency requirement differs before provisioning, select the appropriate single AWS region and rerun the operational readiness review.
- Production runs the selected Fargate/RDS Multi-AZ topology across two Availability Zones. Gateway and Ledger have at least two tasks; other services scale from measured demand. Development and staging explicitly accept lower availability and smaller capacity.
- The production availability objective for authenticated, authoritative financial reads and commands is 99.5% per calendar month. Notifications, exports, and noncritical background work have lower objectives and must never affect financial availability.

### Recovery and data protection

- RDS automated backups and point-in-time recovery retain 30 days. All backups, snapshots, and exports are encrypted with KMS; restoration access is restricted and audited. A restored environment is isolated, uses non-production credentials, and preserves the same PII/anonymization controls before any access.
- The MVP recovery objective is RPO of 15 minutes and RTO of 4 hours for a regional service/database recovery. Multi-AZ failover normally improves this, but the stated objective covers diagnosis, application recovery, and validation rather than promising a vendor failover duration.
- Run and document a production-like restore test monthly, including Ledger reconstruction from immutable entries, outbox/idempotency consistency, least-privilege access, and a controlled client read check. Test full regional recovery and incident communications quarterly. A failed exercise blocks the next production promotion until remediated or explicitly risk-accepted.
- Event transport outages, outbox lag, and Notification failure are recoverable asynchronous conditions. They do not roll back accepted Ledger commits; alerts and replay procedures cover them.

### Observability and incident response

- Emit structured, redacted logs; traces with correlation IDs; and metrics for API latency/error rate, authentication failures, rate-limit/claim abuse, database connections/CPU/storage/replication health, outbox lag, queue age/DLQ depth, consumer failure, sync cursor reset, export failure, and notification-provider result.
- Alert on customer-impacting SLO burn, Ledger write failure, failed backup/restore, sustained outbox or queue lag, DLQ entry, database storage/connection exhaustion, and anomalous security signals. Dashboards separate authoritative financial health from best-effort delivery health.
- Maintain a lightweight on-call rotation, severity classification, incident channel/template, customer-status update cadence, and runbooks for Ledger outage, database restore, queue/DLQ replay, token/signing-key incident, PII exposure, and abusive verification/claim traffic. Financial correction after an incident is a normal attributable Ledger command, never a database edit.

### Cost and capacity guardrails

- Tag every AWS resource by environment, service, owner, and cost centre. Set monthly budget alerts at 50%, 80%, and 100%; review cost and capacity weekly during launch. Set explicit Fargate task min/max, RDS storage/connection alarms, queue-retention/DLQ limits, and log retention suitable for incident investigation without retaining request bodies.
- Keep one-region deployment, managed services, short-lived Privacy Export objects, and scoped Valkey usage until measured demand justifies a change. Cross-region writes, Kubernetes, Kafka, permanent export archives, and speculative autoscaling are out of scope for the MVP.
