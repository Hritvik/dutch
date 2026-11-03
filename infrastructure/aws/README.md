# AWS deployment

The environment stacks are validation-only until an operator supplies account access.
This directory now contains the bootstrap and FIFO messaging modules; add the network,
data, runtime, and observability modules before the first real apply.

## 1. Bootstrap state (once per account)

```bash
cd infrastructure/aws/bootstrap
tofu init
tofu apply \
  -var aws_region=ap-south-1 \
  -var state_bucket_name=dutch-tofu-state-<account> \
  -var lock_table_name=dutch-tofu-locks
```

Copy `environments/staging/backend.tf.example` to `backend.tf` in each environment and
replace the bucket/key. Never commit state or plan files.

## 2. Modules and apply order

1. `modules/network` — VPC, private subnets, security groups, endpoints.
2. `modules/data` — RDS PostgreSQL, parameter group, backup retention, deletion
   protection; one database and migration/runtime roles per service.
3. `modules/secrets` — DB passwords, `DUTCH_OUTBOX_SERVICE_TOKEN`,
   `DUTCH_NOTIFICATION_SERVICE_TOKEN`, `DUTCH_NOTIFICATION_RECEIPT_TOKEN`,
   `DUTCH_SYNC_CURSOR_SECRET`, `DUTCH_PUSH_WEBHOOK_TOKEN`.
4. `modules/messaging` — already defined: one SNS FIFO topic per producer and one SQS
   FIFO consumer queue plus DLQ; instantiate it per environment/consumer.
5. `modules/runtime` — ECR, ECS cluster/services, ALB/TLS, task roles, log groups.
6. `modules/observability` — alarms for `outbox_oldest_unpublished_seconds`,
   `outbox_dead_lettered_total`, `notification_deliveries_total{status="failed"}`, and
   the rate-limited share; dashboard from `observability/grafana-dutch.json`.

Apply development first, then staging, then production, with a reviewed plan each time.

## 3. First deploy

1. Push a `v*` tag; the Release workflow publishes images to GHCR.
2. Run Flyway as a one-off ECS task per service with the `*_migration` role.
3. Deploy gateway, then ledger/notification/ums/iam by image **digest**.
4. Smoke: `/actuator/health`, `/actuator/prometheus` (monitoring network only),
   unauthenticated gateway rejection, authenticated profile/group/expense journey.
5. Record the digests and plan output in the release ticket; generate the provenance
   record with `node tools/release-record.mjs build ...`.

## 4. Rollback

Re-point the ECS service to the previous task-definition revision and digest; never
rebuild. Database repair is forward-only; restore rehearsals use a separate instance.
