# Profile edge wiring

Dutch's `/api/v1/me` surfaces are Dutch-owned contracts, but readiness and display
belong to canonical UMS and the member mapping belongs to Ledger. This document
records how the Gateway composes them and how UMS delivers association reports.

## Report ingress (UMS → Ledger)

`POST /internal/ums/association-reports`

- Authentication: `X-Service-Token` compared with `MessageDigest.isEqual` against
  `DUTCH_UMS_SERVICE_TOKEN` before the body is parsed, so a bad token cannot force a
  parse. The comparison leaks only the secret's length. A blank configuration
  generates a random token, so an unconfigured instance accepts no producer.
- The same token also guards `GET /internal/members/by-subject`; separate tokens per
  direction would be tighter and are a follow-up, not a current claim.
- Body: the pinned envelope in `contracts/imported/ums/` (`ums.profile.association-report.v1`).
  UMS owns the schema; Dutch validates the fields it applies.
- The request is applied inside one transaction. The response is written only after
  the consumer returns, so an acknowledgement always follows a durable outcome.

| Condition | Status | Meaning for the relay |
| --- | --- | --- |
| Applied | `202` | Durable. Never needs redelivery. |
| Duplicate or stale revision | `200` | Already reflected; safe to acknowledge. |
| Reused event id with another payload | `409` | Do not retry unchanged; the producer sent conflicting facts. |
| Live report reusing a retired generation | `422` | Permanent; retrying cannot succeed. |
| Report for another app registration or environment | `403` | Misrouted; fix the producer's scope. |
| Unknown or malformed body | `400` | Fix the producer; retrying unchanged cannot succeed. |
| Bad or missing token | `401` | Configure the shared secret. |

## Edge translation (Gateway)

`GET /api/v1/me/profile-state`

1. Gateway calls UMS `GET /api/v1/profile/status` with the caller's bearer token.
2. It maps readiness to the Dutch contract: `PROFILE_READY` + `ACTIVE` → `PROFILE_READY`,
   anything else → `PROFILE_REQUIRED`. UMS's readiness enum carries only
   `PROFILE_REQUIRED`/`PROFILE_READY`, so Dutch's `PROFILE_PROVISIONING` is not
   produced by this path; a missing lifecycle is treated as restrictive, never ACTIVE.
3. UMS unavailable → `503` with the Dutch error envelope and a correlation id.
   Ledger 404 → mapping absent; Ledger 401/5xx → `503`, never a permanent `403`.

`GET /api/v1/me`

1. Same status call; not ready → `409 profile_required`.
2. Ready → Ledger `GET /internal/members/by-subject?appSubject=…` (service token).
   No active mapping → `403`: a ready profile is not Dutch membership.
3. Gateway reads UMS `GET /api/v1/profile` for `fields.displayName`. Absent name →
   `409 profile_required` rather than an invented name.

The Gateway never invents a member, a readiness state, or a display name, and never
grants Ledger access: Ledger still authorizes every financial route itself.
