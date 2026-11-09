# Pinned IAM contract input

- Bundle: `iam-contracts-1.1.0.zip`
- Bundle sha256: `f54829318161688314e26c4feb3f0f10faeaab9669926393cf769028dbbd74ff`
- Provider commit: `b831ea5` (IAM)
- Snapshot contract artifact (IAM → UMS internal route):
  `contracts/http/iam-onboarding-snapshot.openapi.yaml`,
  sha256 `9f87d53aa9f795747f32787778ddcedfa0a120a02edc59ff0f5d39ce24554179`
- Central pin: `portfolio-contracts/registry.yaml`, contract
  `iam.onboarding-snapshot.v1`, consumer `dutch` (moved 2026-11-09)

## What Dutch actually consumes

Only `events/event-envelope.schema.json` plus the error-envelope shape
(`code`/`message`/`correlationId` with allowlisted `details`) reflected in the Dutch
`ErrorResponse`. The 1.1.0 envelope is byte-identical to the previously pinned copy,
so the re-pin changes provenance, not Dutch input.

## Review of the additive security alternative (2026-11-09)

Bundle 1.1.0 adds `registeredWorkloadToken` (`workload_access` v1 bearer tokens) as an
**alternative** to `registeredWorkloadMtls` on
`GET /internal/v1/apps/{appRegistrationId}/onboarding`.

- Additive only: `registeredWorkloadMtls` is unchanged, and the response schema and
  fixtures are unchanged.
- Dutch neither serves nor calls this route. It is IAM → UMS repair traffic, so no
  Dutch code path verifies or issues workload tokens.
- Nothing in the alternative widens a Dutch surface. Its requirements (exact claim set,
  single audience, app binding, scope, 60–900 s lifetime, ES256 via the IAM JWKS,
  non-enumerating 404 on mismatch) are stricter than an unauthenticated path and
  equivalent in strength to mTLS for this purpose.
- Residual risk accepted by the owner, not by Dutch: two credential mechanisms exist
  for one route, so IAM must keep both verification paths fail-closed together.

These files are immutable consumer input. Dutch-owned contracts must not edit them; a
new IAM bundle version requires an explicit pin change.
