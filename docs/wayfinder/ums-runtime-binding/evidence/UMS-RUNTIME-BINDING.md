# UMS-RUNTIME-BINDING evidence

Task: `dutch/UMS-RUNTIME-BINDING` — connect canonical UMS delivery to the Dutch runtime.
Agent: `opencode/dutch-agent`
Date: 2026-11-08

## What was built

### Authenticated association-report ingress

`POST /internal/ums/association-reports` in Ledger:

- service-token auth (`X-Service-Token`, constant-time compare,
  `DUTCH_UMS_SERVICE_TOKEN`, blank configuration accepts nobody);
- the pinned UMS envelope is parsed into `MemberAssociationReport` and applied through
  the reviewed consumer (revision ordering, erasure watermark, duplicate handling);
- acknowledgement is written after the consumer returns, so `202` means durable and
  `200` means already reflected.

### Edge translation

`documents/../docs/architecture/profile-edge-wiring.md` records the routes:

- `/api/v1/me/profile-state` → UMS `/api/v1/profile/status`, translated to the Dutch
  state contract; `503` when UMS is unavailable.
- `/api/v1/me` → UMS status first (`409 profile_required`), then Ledger
  `/internal/members/by-subject` (`403` when the mapping is absent), then UMS display.
  A ready profile without a mapping is forbidden, and a missing display name is
  `profile_required` rather than invented.

The transparent `/api/v1/me*` forward was removed: those paths are translated, not
proxied, and the routing tests now prove the remaining surfaces still forward.

## Acceptance

| Requirement | Evidence |
| --- | --- |
| Authenticated delivery into the existing consumer | `AssociationReportControllerTest` — 7 tests: 202 applied, 200 duplicate, 401 wrong/missing token (consumer untouched), 409 conflict, 422 retired generation, 403 foreign scope, 400 malformed |
| Wrong authority and scope denied | token tests and `ForeignAssociationReportException` → 403 |
| Duplicate, stale and restrictive facts | controller test plus the PROFILE-BINDING integration suite (revision ordering, erasure, readiness regression) |
| Replay after restart | `a_duplicate_report_creates_one_binding_across_restart` (PROFILE-BINDING) |
| Acknowledgement only after a durable outcome | the controller responds after `consume` returns inside its transaction |
| `/me` and profile states through Gateway | `MeControllerTest` — ready, required, closed, missing mapping, missing display name, UMS unavailable |
| A ready profile without mapping cannot grant Ledger access | `MeControllerTest.a_ready_profile_without_a_mapping_is_forbidden` plus the Ledger resolver tests |
| Endpoint and fixture evidence for UMS | `docs/architecture/profile-edge-wiring.md` plus the pinned fixtures |

## Verification

```bash
./gradlew :services:ledger:test :services:gateway:test   # 228 + 35
cd contracts && npm test                                 # 19/19
./gradlew verifyServiceBoundaries verifyContracts
```

## Not proven here

- **Delivery semantics with the real UMS relay (W-18).** The endpoint's ack/retry
  matrix is published and tested on the Dutch side; the UMS relay must confirm it
  treats `200/202` as acknowledged, retries `5xx`, and does not retry `409/422/403/400`.
  Question filed with the UMS agent.
- **Live canonical UMS on staging.** Proven here against a stand-in HTTP server and
  the pinned schema; no deployed UMS instance exists yet.
