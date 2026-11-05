# ADR 0011: Launch inside the Super App before shipping a standalone app

## Status

Accepted.

## Context

The mobile client has been built as a standalone Expo application: it has its own
bundle identifier, EAS build profiles, and release-checklist items for store
metadata and submission. At the same time the capability map describes Dutch as a
module hosted by the Portfolio Super App. Those two statements cannot both be
true, and they differ in who signs, publishes, and takes store review.

The product decision is to launch the portfolio's products inside one Super App
shell first, and to add standalone apps later when time and capacity permit.

## Decision

Dutch ships as an experience module inside the Portfolio Super App at launch.

- The Super App owns the published binary, signing credentials, store records,
  review, staged rollout, and store-level data declarations.
- Dutch owns its module, screens, navigation contribution, offline command queue,
  session presentation, and the clients that talk to Dutch-owned or contracted
  services.
- Device acceptance runs against a host build containing the Dutch module, not
  against a Dutch-owned artifact.
- A standalone Dutch app is a later phase with no date and is not a launch gate.
  Dutch's EAS profiles and store checklist items therefore stay as phase-2
  scaffolding and must not be reported as satisfied launch evidence.
- Because the standalone phase is expected eventually, the module boundary must be
  portable now: no Dutch screen, route, offline transition, or session state may
  depend on a host-only API. Dutch must render against a minimal test shell that
  provides only the declared host interface.
- Dutch publishes these requirements to the host as consumer expectations
  (`dutch-super-app-exp-001`..`004` in the central capability home) rather than
  assuming them.

## Consequences

- Dutch's release gate no longer includes store submission, signing, or review.
  It includes the module, offline behavior, accessibility, device acceptance on a
  host build, and the runtime/serving contract.
- Dutch cannot ship a defect fix on its own cadence. The host's release cadence and
  any per-module update path become a required expectation, not a convenience.
- Store data declarations covering Dutch's financial and contact data, and the
  account-deletion entry point required by both stores, become a shared
  responsibility that must be assigned explicitly: the host publishes, Dutch
  supplies and approves the description of its own data.
- The standalone phase is only cheap if the module boundary is real today, so
  portability is reviewed as a delivery constraint rather than a preference.
- `apps/mobile/eas.json` and the mobile store items in the release checklist remain
  in the repository for that later phase; leaving them unexercised is correct, not
  incomplete.
