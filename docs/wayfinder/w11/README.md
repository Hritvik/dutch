# W-11 — UMS onboarding consumer

**Status: complete and independently accepted.**

Gate: `./gradlew :services:ums:test` (JDK 21, Testcontainers PostgreSQL 16) — 18 tests, 0 failures,
0 skipped.

## Accepted behaviour

- V3 replaces the prototype identity-keyed tables with app-scoped state: `member_profile` unique on
  `(app_registration_id, app_subject)`, `mem_` ids, closed profile/lifecycle vocabularies, a
  producer-keyed inbox with a payload fingerprint, `inbox_quarantine`, a checkpoint and a
  contract-shaped outbox. The destructive upgrade is a recorded decision (no live UMS population) and
  is proven by a V2→V3 test.
- `JdbcMemberProfileConsumer` consumes `iam.app-subject.onboarding-ready.v1` in one transaction:
  duplicate delivery is a no-op; a distinct event id for one subject returns the existing mapping;
  conflicting reuse of an event id writes a durable `PAYLOAD_CONFLICT` quarantine row and fails closed.
- The emitted `dutch.member-profile.mapping-changed.v1` event carries the pinned six-field payload in
  the IAM envelope and is validated against the Dutch schema in tests.
- `/api/v1/me` no longer exposes a global identity; it serves `profile_required` or the Dutch profile.

## Acceptance criteria

- [x] Consumes the IAM onboarding event idempotently in its own database.
- [x] Exactly one Member Profile and App Subject mapping per onboarding.
- [x] Duplicate, conflicting and restart delivery converge or quarantine safely.
- [x] The Dutch mapping event matches the pinned contract and contains no PII.
- [x] UMS never reads IAM persistence (database-role negative test).
- [x] The legacy identity shape is gone from the HTTP response.

## Residual lows

- Quarantine records via a second pooled connection; `detected_at` uses source time.
- Reorder/gap handling, checkpoint monotonicity and `onboardingVersion` storage remain for W-13.
- `dutch.environment` defaults to `development`; deployment must set it.
- Testcontainers classes skip when Docker is absent; CI must provide Docker.
