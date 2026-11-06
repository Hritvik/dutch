# W-12 — Dutch honest profile states

**Status: complete and independently accepted (W12-R, 2026-11-05).**

Gate: `./gradlew :services:ums:test --rerun-tasks --no-daemon` with JDK 21 and the Colima
Testcontainers environment (set `DOCKER_HOST` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`).
On 2026-11-06 the full `:services:ums` suite passed: **34 tests, 0 failures, 0 errors, 0 skipped**
(PostgreSQL 16 via Testcontainers).

## Accepted surface

- `GET /api/v1/me/profile-state` — `{data:{state}}` with only `PROFILE_REQUIRED`,
  `PROFILE_PROVISIONING`, or `PROFILE_READY`. An unknown subject is `PROFILE_REQUIRED`, not an
  error; `retryAfterSeconds` stays omitted because Dutch has no authoritative ETA.
- `GET /api/v1/me` — `409 profile_required` with the pinned message and correlation until the
  Dutch-owned profile is ready and the Member is active, then a closed four-key body
  (`memberId`, `displayName`, `profileState`, `lifecycleState`).
- `X-Correlation-ID` (emitted literally as `X-Correlation-Id`, see F1) is echoed when it matches
  `^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$` and regenerated as `cor_<token>` otherwise, on success and
  rejection paths; the filter runs at highest precedence.
- `401 unauthorized` and `403 forbidden` carry the pinned error envelope from both the
  resource-server and exception-handling paths.
- No response exposes the app subject (`aps_`), a global identity (`hid_`), a member identifier
  the service does not own, a credential, a token, or an email.

## Acceptance criteria

- [x] `/profile-state` returns only the three allowed values and never errors for an unknown subject.
- [x] `/me` keeps the `profile_required` split and, once ready, returns only the Dutch-owned fields.
- [x] Correlation conforms to the pinned pattern, is regenerated when invalid, and appears on the
      409/401/403 bodies.
- [x] The security envelope is pinned for both paths; the 401 path is test-verified and the 403
      path by code reading (no test exercises 403).
- [x] The end-to-end journey covers required → provisioning → ready, plus suspended and departed
      Members answering `PROFILE_REQUIRED` and 409.
- [x] The slice is validated against the gateway OpenAPI and introduces no second schema.
- [x] Privacy absence is asserted case-insensitively on the ready, 409, and state bodies.

## Independent review (W12-R)

No critical, high, or medium finding. Six low and informational residuals are carried forward
unchanged because W12-F may only touch this README:

- **F1 (low, open)** — the code constant is `X-Correlation-Id`; the contract spells
  `X-Correlation-ID`. HTTP names are case-insensitive, but the literal is inconsistent. One-line
  follow-up in `CorrelationIdFilter`.
- **F2 (low, open)** — `UmsSecurityConfiguration.envelope` falls back to `correlationId: ""`,
  which violates the contract pattern, while `ProfileErrorHandler` uses `cor_unknown`. The
  correlation filter precedes the chain, so the fallback is currently unreachable.
- **F3 (low, documented)** — without `DOCKER_HOST`, `@Testcontainers(disabledWithoutDocker = true)`
  skips every integration test and still reports BUILD SUCCESSFUL. Use the gate above or CI must
  fail on skips.
- **F4 (info)** — a database fault bypasses the scoped advice and returns Spring Boot's default
  error body. Ledger and notification share the pattern; portfolio-wide hardening, not a W-12
  regression.
- **F5 (info)** — `ProfileReadyResponse.lifecycleState` advertises `SUSPENDED` and `DEPARTED`,
  which a ready (200) body never returns.
- **F6 (info, open)** — `ProfileErrorHandler.invalidRequest` emits `internal_error` (500); the
  method name no longer matches what it returns.

## Limits and follow-through

This is the local reference adapter: it does not exercise a live IAM issuer or the Dutch mobile
client. The readiness transition is driven by the Dutch-owned completion write, because UMS
readiness is consumed rather than authored here. Real issuer, TLS, and the end-to-end journey with
the runnable IAM runtime are `ums/W-18`; controlled launch is `dutch/S7` and stays blocked on
`ums/W-18` and `iam/W-13`.
