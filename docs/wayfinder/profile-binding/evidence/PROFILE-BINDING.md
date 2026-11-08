# PROFILE-BINDING evidence

Task: `dutch/PROFILE-BINDING` — wire the Dutch profile and Member projection to
canonical UMS.
Agent: `opencode/dutch-agent`
Date: 2026-11-08

## What was built

### 1. Pinned UMS association-report contract

`contracts/imported/ums/` holds a read-only copy of the UMS-owned schema and
fixtures, with the source commit and per-file sha256 in `PINNED.md`:

- `association-report.json` and `common.json` (schemas)
- `fixtures/association-report.json` (accepted)
- `fixtures/association-report-invalid-display.json` (display value smuggled into payload)
- `fixtures/association-report-invalid-environment.json` (`local` environment)
- `fixtures/association-report-invalid-tombstone.json` (erasure carrying live fields)

`contracts/validate-contracts.mjs` validates the accepted fixture, requires the
three negatives to be rejected, and scans every fixture for sensitive values.
`contracts/test/validate-contracts.test.mjs` additionally verifies each pinned
file still matches its recorded digest, so an edit in place fails CI.

### 2. Ledger readiness projection

- `V17__create_ums_profile_binding.sql` adds `ums_profile_binding`, keyed by app
  subject, with the binding revision as the only ordering key, a tombstone-shape
  constraint (erasure requires `retired_generation`, live state requires
  `generation`), and a partial index for grantable bindings.
- `MemberAssociationReport` enforces the pinned wire invariants in the compact
  constructor and exposes `grantsProfileAccess()`.
- `JdbcMemberAssociationReportConsumer` applies reports idempotently by `eventId`,
  ignores stale binding revisions, refuses a live report that reuses a retired
  generation (`RetiredGenerationException`), and exposes
  `unreconciledSubjects()` for reports with no Dutch mapping.
- `JdbcMemberResolver` now fails closed: access requires an `ACTIVE` mapping **and**
  a binding whose profile is `PROFILE_READY` and lifecycle `ACTIVE`.

### 3. Dutch `/me` contract preserved

`contracts/http/gateway/openapi.yaml` is unchanged; the `/api/v1/me` and
`/api/v1/me/profile-state` fixtures still validate, and the gateway still routes
`/api/v1/me*` to UMS. Readiness stays UMS-owned; Dutch only gates Ledger access on
the reported binding.

## Acceptance

| Requirement | Evidence |
| --- | --- |
| Pin the UMS-owned report schema and test accepted/rejected fixtures | `contracts/test/validate-contracts.test.mjs` — 19/19, including the three negatives and the digest pin |
| Preserve the Dutch `/me` contract | gateway OpenAPI and `profile-*` fixtures unchanged and validated |
| Correlate exactly one mapping across replay and restart | `replays_the_mapping_fact_keeps_exactly_one_mapping`, `a_duplicate_report_creates_one_binding_across_restart` |
| Readiness regression denies Ledger access | `a_readiness_regression_denies_access` |
| Provider outage denies Ledger access | `a_missing_binding_denies_access_even_with_an_active_mapping` |
| `PROFILE_READY` with the mapping absent denies Ledger access | `a_ready_report_grants_access_only_when_the_mapping_exists` |
| Erasure cannot be associated | `an_erased_binding_denies_access`, `a_live_report_reusing_a_retired_generation_is_refused` |
| A report without a member is held visibly | `a_report_without_a_member_is_held_unreconciled` |
| Duplicate event id with another payload is a conflict | `a_reused_event_id_with_another_payload_is_a_conflict` |
| Stale revisions never regress readiness | `a_stale_report_does_not_regress_readiness` |

## Verification

```bash
./gradlew :services:ledger:test        # 206 tests, 0 failures
cd contracts && npm test               # 19/19
./gradlew verifyServiceBoundaries verifyContracts
```

## Not built here

- **Transport binding.** The consumer is the domain entry point; no queue listener
  or HTTP ingestion route exists in Dutch yet. Delivering the report is the joint
  transport work that `ums/W-18` joins, and choosing it unilaterally would invent a
  contract. Until then reports can only be applied through the service in tests.
- **Snapshot repair.** A restrictive fact older than the UMS outbox retention is an
  accepted loss window (contract decision 3); Dutch holds the subject unreconciled
  rather than assuming readiness.
- **Gateway-side translation.** None is needed: `/me` is proxied to UMS and Dutch
  owns the contract only.
