# W-10 — Dutch mapping and profile contracts

**Status: complete and independently accepted.**

Gate: `cd contracts && node validate-contracts.mjs && npm test` — 0 issues, 10/10 tests.

## Accepted contracts

- `contracts/events/dutch/json-schema/dutch.member-profile.mapping-changed.v1.schema.json` — closed
  payload `appRegistrationId, appSubject, memberId, mappingVersion, profileState, lifecycleState` in the
  pinned IAM envelope (`contracts/imported/iam/event-envelope.schema.json`).
- `contracts/events/asyncapi.yaml` — one channel; the legacy `iam.identity.verified.v1` and
  `ums.member.mapping.changed.v1` contracts are removed.
- `contracts/http/gateway/openapi.yaml` — `GET /api/v1/me/profile-state` (state + bounded retry) and
  `GET /api/v1/me` (409 `profile_required` via `ProfileRequiredResponse`, then a closed Dutch profile).
- Fixtures and harness cover valid shapes, the forbidden global-identity event, envelope/payload
  mismatches, App Subject/identity/token leakage, and the pinned 409.
- `contracts/imported/iam/PINNED.md` records bundle `iam-contracts-1.0.0.zip` sha256
  `7bb76067a0e2e5ea0efd1e4aca981c3ef3fead2abb7f9e467fadca8dadc81195`.

## Acceptance criteria

- [x] Mapping payload is exactly the six allowlisted fields inside the shared envelope.
- [x] Profile state returns only the permitted state and safe retry guidance.
- [x] `/api/v1/me` returns `profile_required` until ready and a Dutch-owned profile afterward.
- [x] No HTTP contract exposes App Subject, Human Identity ID, identifier, credential, code or token.
- [x] Valid fixtures pass; every forbidden/undeclared-field fixture fails for the asserted reason.
- [x] No alternate local schema remains for the legacy channels.

## Residual low findings

- Correlation/causation values are not scanned for `hid_`/`aps_` in event fixtures.
- Negative-fixture coverage is thinner than the work list (no `PROFILE_PROVISIONING` fixture).
- `PINNED.md` describes the error-envelope shape as imported although only the event envelope is copied;
  `details.field` is wider than the IAM enum.
- `docs/architecture/api-event-contracts.md` still describes the retired layout.

## Mandatory follow-through (not W-10)

- W-11/W-12 must move the UMS consumer/producer and the `/api/v1/me` controller off the legacy
  `identityId` shape.
- The contract's `memberId` (`mem_`) and `lifecycleState` (`ACTIVE|SUSPENDED|DEPARTED`) vocabulary must
  be reconciled with the current UMS UUID member ids and `ACTIVE|INACTIVE|ANONYMIZED` states.
