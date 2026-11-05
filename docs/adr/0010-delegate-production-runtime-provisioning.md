# ADR 0010: Delegate production runtime provisioning to a platform capability

## Status

Accepted. Amends ADR 0007 for provisioning ownership; ADR 0007's runtime shape
remains the Dutch requirement.

## Context

ADR 0007 decided the *shape* Dutch needs: a single-region managed runtime with
private networking, managed PostgreSQL, FIFO messaging, managed secrets, and
immutable artifact promotion. It also implicitly gave Dutch the job of
provisioning that shape, and `infrastructure/aws` grew account bootstrap,
environment wrappers, and resource modules inside this repository.

Those are two different decisions. Dutch owns the observable properties its
services need and the artifacts that express them (container image, health and
metrics endpoints, migration order, alert rules, dashboards, release record).
It does not own the AWS account, the network, the database fleet, the secret
store, or the on-call rotation for them. Keeping provisioning here means Dutch
holds credentials and blast radius it cannot responsibly steward, and every
product change drags account-level infrastructure with it.

## Decision

Dutch declares its production runtime requirements as a capability expectation
and consumes a managed runtime from a platform provider.

- The authoritative expectation set is the `dutch-managed-production-runtime`
  capability contract in the central capability home
  (`~/repositories/portfolio-planning/capabilities/contracts/`), covering
  private-by-default networking, isolated restorable storage, ordered durable
  event delivery with dead letters, runtime secret injection, digest-pinned
  deployment and rollback, metrics/logs/alert delivery, and migration jobs with
  separate credentials.
- Dutch keeps only artifacts that describe its own services: `services/Dockerfile`,
  the release workflow and provenance record, the local development environment
  under `infrastructure/local`, and Dutch-authored signal definitions under
  `infrastructure/observability`.
- Dutch does not keep AWS accounts, OpenTofu environments, or resource modules.
  `infrastructure/aws` is removed; its provisional modules are recoverable from
  history but are not a Dutch deliverable.
- The provider is the `distracted-dev-infra` effort, assigned by the portfolio
  owner on 2026-11-05 (`dutch-runtime-provider-decision-001`). The assignment is
  not a commitment: the effort's own capability map excludes production
  deployment and real-environment secret management, and its direction is
  free-tier multi-cloud rather than the managed AWS shape in ADR 0007. Both
  conflicts are recorded against the contract and must be answered.
- The provider has responded (2026-11-05): **3 explore, 4 defer, 0 commitments**.
  The deferred set is exactly Dutch's correctness surface — restorable storage,
  ordered delivery with dead letters, alert delivery, and a separate migration
  identity. The provider states plainly that a free-tier pilot does not yet
  supply those semantics and that managed AWS is neither mandated nor authorized.
- Region/account model, cost envelope, and drill ownership remain open
  (dutch-runtime-q-002, q-003). S7's production gate remains unproven rather than
  satisfied, and Dutch records the deferrals as honest reporting rather than as
  acceptance of the gap.

## Consequences

- Infrastructure ownership leaves the Dutch release path. A release now depends
  on a provider commitment that does not exist yet; that gap is explicit in the
  contract's open questions instead of being absorbed as Dutch IaC.
- Dutch CI no longer validates OpenTofu. It validates what Dutch authors:
  container builds, provenance, local topology, secret scanning, accessibility,
  and the Dutch signal definitions.
- Promotion evidence shrinks to the Dutch-owned portion: image digest, SBOM,
  attestation, migration rehearsal, and observability assets. Provider-side
  plan/apply, restore drills, and account controls become provider evidence that
  Dutch cites rather than produces.
- If no provider accepts the expectations, Dutch can still run locally and in a
  single operator-managed staging environment, but it cannot claim production
  readiness.
