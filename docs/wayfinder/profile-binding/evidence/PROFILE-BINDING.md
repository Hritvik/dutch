# PROFILE-BINDING evidence

Task: `dutch/PROFILE-BINDING` — wire the Dutch profile and Member projection to
canonical UMS.
Agent: `opencode/dutch-agent`
Date: 2026-11-08
Revision: after independent review (`opencode/profile-binding-reviewer`), findings closed.

## What was built

### 1. Pinned UMS association-report contract

`contracts/imported/ums/` holds a read-only copy of the UMS-owned schema and
fixtures, with the source commit and per-file sha256 in `PINNED.md`:

- `association-report.json` and `common.json` (schemas)
- `fixtures/association-report.json` (accepted)
- `fixtures/association-report-invalid-display.json` (display value in payload)
- `fixtures/association-report-invalid-environment.json` (`local` environment)
- `fixtures/association-report-invalid-tombstone.json` (erasure carrying live fields)

`contracts/validate-contracts.mjs` validates the accepted fixture, requires each
negative to be rejected **for its own reason** (payload `additionalProperties`,
`environment` enum, tombstone shape), and scans every fixture for sensitive values.
`contracts/test/validate-contracts.test.mjs` also verifies the pinned files still
match their recorded digests. That is a drift tripwire inside this repository, not
a provenance check against UMS: a commit that changes a fixture and its hash row
would still pass, so the `PINNED.md` diff is review material.

### 2. Ledger readiness projection

- `V17__create_ums_profile_binding.sql` adds `ums_profile_binding`, keyed by app
  subject, with the binding revision as the only ordering key, a tombstone-shape
  constraint, the UMS registration and environment for scope checks, and a partial
  index for grantable bindings.
- `MemberAssociationReport` enforces the pinned wire invariants, including that
  `aggregate.id` equals `payload.appSubject` and that the report names this app
  registration and environment.
- `JdbcMemberAssociationReportConsumer` applies reports idempotently by `eventId`,
  serialises concurrent consumption with `SELECT … FOR UPDATE`, repeats the
  revision predicate in the `UPDATE`, resolves an insert race through
  `ON CONFLICT DO NOTHING`, refuses a live report that reuses a retired generation,
  keeps the erasure watermark monotonic, and records `profileRevision` so a reused
  event id with a different revision is a conflict.
- `JdbcMemberResolver` fails closed: financial access requires an `ACTIVE` mapping
  **and** a binding whose profile is `PROFILE_READY` and lifecycle `ACTIVE`.
- `UnreconciledBindingMetrics` exposes `dutch.ledger.association.unreconciled` and
  `dutch.ledger.association.unmapped`, so "held visibly" is an observed gauge rather
  than a claim.

### 3. Dutch `/me` contract preserved

`contracts/http/gateway/openapi.yaml` is unchanged; `/api/v1/me` and
`/api/v1/me/profile-state` fixtures still validate, and the gateway still routes
`/api/v1/me*` to UMS. Readiness stays UMS-owned; Dutch only gates Ledger access on
the reported binding.

### 4. Data-subject export deliberately not narrowed

`PrivacyExportService` uses `MemberResolver.resolveForDataSubjectRights`, which
requires only an active mapping. A person exercising a legal export right must not
be denied because UMS reports the profile as not-ready, closed, or pending deletion.
Financial access stays gated; the export path does not.

## Acceptance

| Requirement | Evidence |
| --- | --- |
| Pin the UMS-owned report schema and test accepted/rejected fixtures | `contracts/test/validate-contracts.test.mjs` — 19/19, negatives rejected for their own reasons, digest drift guard |
| Preserve the Dutch `/me` contract | gateway OpenAPI and `profile-*` fixtures unchanged and validated |
| Correlate exactly one mapping across replay and restart | `replaying_the_mapping_fact_keeps_exactly_one_mapping` (mapping), `a_duplicate_report_creates_one_binding_across_restart` (report, fresh consumer instance) |
| Readiness regression denies Ledger access | `a_readiness_regression_denies_access` |
| Provider outage denies Ledger access | `a_missing_binding_denies_access_even_with_an_active_mapping` |
| `PROFILE_READY` with the mapping absent denies Ledger access | `a_ready_report_grants_access_only_when_the_mapping_exists` |
| Erasure cannot be associated | `an_erased_binding_denies_access`, `a_live_report_reusing_a_retired_generation_is_refused` |
| A report without a member is held visibly | `a_report_without_a_member_is_held_unreconciled` plus the two gauges |
| Duplicate event id with another payload is a conflict | `a_reused_event_id_with_another_payload_is_a_conflict`, `a_reused_event_id_with_another_profile_revision_is_a_conflict` |
| Stale revisions never regress readiness | `a_stale_report_does_not_regress_readiness` |
| A slow lower revision cannot overwrite a newer fact | `a_slow_lower_revision_cannot_overwrite_a_newer_restrictive_fact` |
| Concurrent duplicate delivery applies once | `two_concurrent_deliveries_of_one_report_apply_once` |
| Erasure watermark never moves backwards | `an_erasure_watermark_never_moves_backwards`, `an_erasure_below_the_live_generation_is_refused` |
| Closure and deletion-pending deny financial access | `closure_and_pending_deletion_deny_access` |
| A report for another app registration is refused | `a_report_for_another_app_registration_is_refused`, `a_report_for_another_environment_is_refused` |
| A new generation is accepted; a late erasure below it is refused | `a_new_generation_is_accepted_and_a_late_erasure_below_it_is_refused` |
| Data-subject rights survive readiness regression and outage | `data_subject_rights_survive_a_readiness_regression_and_outage` |
| The outage direction is countable | `the_outage_direction_is_countable` |

## Verification

```bash
./gradlew :services:ledger:test        # 219 tests, 0 failures
cd contracts && npm test               # 19/19
./gradlew verifyServiceBoundaries verifyContracts
```

## Not built here, and known limits

- **Transport binding.** The consumer is the domain entry point; no queue listener
  or HTTP ingestion route exists in Dutch yet. Delivering the report is joint work
  with UMS in `ums/W-18`; choosing a transport unilaterally would invent a contract.
- **Event-id durability.** `source_event_id` holds only the latest event per
  subject, so an old event id is recognised by revision rather than by id after it
  is superseded. A journal (as `member_sync_journal` does for mapping facts) would
  make redelivery detection exact; deferred because the revision guard already
  prevents regression.
- **Scope configuration.** `dutch.ums.app-registration-id` and `dutch.ums.environment`
  default to `dutch-development` / `development`. Every non-development deployment
  must set `DUTCH_UMS_APP_REGISTRATION_ID` and `DUTCH_UMS_ENVIRONMENT`; leaving the
  defaults in staging or production fails closed by rejecting every report, which
  denies all Ledger access. The properties are declared in the ledger
  `application.yaml` with those environment variable placeholders.
- **Death signal for a refused generation.** `RetiredGenerationException` is not
  surfaced as a metric; a subject blocked by a renumbered generation would only be
  visible in logs.
