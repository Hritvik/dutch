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
| Authenticated delivery into the existing consumer | `AssociationReportControllerTest` — 8 tests: 202 applied, 200 duplicate, 401 wrong/missing token (consumer untouched), 409 conflict, 422 retired generation, 403 foreign scope, 400 malformed |
| Wrong authority and scope denied | token tests and `ForeignAssociationReportException` → 403 |
| Duplicate, stale and restrictive facts | controller test plus the PROFILE-BINDING integration suite (revision ordering, erasure, readiness regression) |
| Replay after restart | `a_duplicate_report_creates_one_binding_across_restart` (PROFILE-BINDING) |
| Acknowledgement only after a durable outcome | the controller responds after `consume` returns inside its transaction |
| `/me` and profile states through Gateway | `MeControllerTest` — ready, required, closed, missing lifecycle, missing mapping, missing display name, UMS unavailable; `MeRouteTest` — end-to-end against canonical UMS and Ledger stubs |
| A ready profile without mapping cannot grant Ledger access | `MeControllerTest.a_ready_profile_without_a_mapping_is_forbidden` plus the Ledger resolver tests |
| Endpoint and fixture evidence for UMS | `docs/architecture/profile-edge-wiring.md` plus the pinned fixtures |

## Verification

```bash
./gradlew :services:ledger:test :services:gateway:test   # 229 + 38 (75 ledger tests are Testcontainers-gated)
cd contracts && npm test                                 # 19/19
./gradlew verifyServiceBoundaries verifyContracts
```

## Review rounds

Round 1 (event 774) requested changes: the `/me` 403 and 503 bodies used messages the
published `ErrorResponse` schema rejects. Both now use the pinned strings
(`Access is denied`, `Service is temporarily unavailable`) and the tests assert the
message, not just the code. A UMS 200 with an empty body now maps to `503` rather than
a `NullPointerException`.

## Not proven here

- **Delivery semantics with the real UMS relay (W-18).** The endpoint's ack/retry
  matrix is published and tested on the Dutch side; the UMS relay must confirm it
  treats `200/202` as acknowledged, retries `5xx`, and does not retry `409/422/403/400`.
  Question filed with the UMS agent.
- **`PROFILE_PROVISIONING`.** UMS's readiness enum has no provisioning value, so the
  Gateway never emits it. If UMS adds one, the mapping is a one-line change.
- **Ack durability is structural, not unit-proven.** The proxy commits before the
  controller responds, and the controller catches only post-rollback outcomes; no
  test fails if `@Transactional` were removed.
- **Testcontainers skips.** Without Docker, 75 ledger tests skip silently, including the
  association-consumer integration suite, so a local run can look green with the
  delivery guarantees unexercised.
- **Live canonical UMS on staging.** Proven here against a stand-in HTTP server and
  the pinned schema; no deployed UMS instance exists yet.
