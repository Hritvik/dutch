# Runbook — outbox and notifications

## Signals

- `outbox_event` rows with `published_at IS NULL`, oldest `occurred_at` age.
- `publish_attempts` rising for one event type or aggregate.
- `outbox_dead_letter` rows (alert on any new row).
- Notification `inbox_event`/`notification_delivery` growth and `FAILED` deliveries.
- Gateway `rate_limited` responses and `429` share.

## Outbox relay

1. Check the oldest unpublished row and `last_error`.
2. Confirm the notification service `/internal/events` is healthy and the service token
   matches on both sides.
3. If the notification service is down, leave events unpublished; the relay retries with
   exponential backoff and finance is unaffected.
4. After the consumer is healthy, the relay drains automatically. Duplicate delivery is
   expected and deduplicated by `inbox_event.event_id`.
5. If an event reached the dead-letter table, fix the schema/payload defect, delete its
   `outbox_dead_letter` row, reset `published_at = NULL, claimed_at = NULL,
   publish_attempts = 0`, and let the relay retry. Never edit financial source rows.

## Push webhook

Set `DUTCH_PUSH_WEBHOOK_URL` and `DUTCH_PUSH_WEBHOOK_TOKEN` on Notification to send
pushes to a provider adapter. The adapter receives the delivery id and must call
`POST /internal/receipts` with `X-Service-Token` and
`{"deliveryId","status":"DELIVERED|FAILED","providerMessageId"}`. Without a webhook the
logging adapter is used and no real push is sent.

## Notification consumer

1. `409`/`500` from `/internal/events` leaves the outbox row unpublished; investigate the
   consumer log and event shape first.
2. `FAILED` deliveries mean the push provider rejected the token; the device row remains
   so the member can re-register. Push failure never changes financial state.
3. If the provider is unavailable, deliveries are retried by the scheduled sweep
   (`FAILED`, and `QUEUED` older than two minutes) up to three attempts; Activity
   refresh on the client remains authoritative.
4. Privacy deletion removes devices, preferences, and deliveries for the caller; the inbox
   dedupe rows remain event-id-only and contain no recipient data.

## Rate limit

A sustained `429` share indicates abuse or a broken client retry loop. Inspect the
client-IP buckets; the gateway returns `Retry-After` equal to the window. Do not raise the
capacity without checking upstream write capacity and the 20 RPS Ledger budget.

## Recovery

Replay is safe at any point because consumers deduplicate by event id. Do not delete
`outbox_event` rows to force a resend; use the dead-letter reset procedure above.
