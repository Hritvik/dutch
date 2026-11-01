Type: grilling
Status: resolved
Assignee: Codex

# Design the architecture verification and build sequence

## Question

What ADRs, diagrams, executable architecture/contract tests, quality gates, and vertical-slice implementation order will prove the design before and during delivery, while producing feedback suitable for incrementally improving the future SDLC orchestration skill?

## Answer

Dutch will build from a short repository/contract/test foundation through eight vertical slices: identity, Groups, Expense/balance, corrections/Settlements/Activity, direct/recurring/sync, events/privacy/resilience, and controlled production launch. Every slice is gated by red-first tests and the relevant invariant, contract, device, recovery, and release evidence; finance, authorization, and recovery cannot be deferred behind UI or push delivery.

The continuous-improvement loop captures one reusable friction/evidence gap per slice. A thin SDLC orchestration skill may promote only recurring lessons or high-severity safeguards, while delegating detailed work to existing specialized skills.

See [verification and build sequence](../../../docs/architecture/verification-build-sequence.md) and [ADR 0008](../../../docs/adr/0008-vertical-slices-with-invariant-first-verification.md).

## Workflow observations

- The requirements and architecture maps form a useful traceability spine; the future SDLC skill should carry only phase handoffs, gates, and retrospective promotion criteria.
- The external lifecycle workflow was evaluated but intentionally not installed because it would overwrite/configure project workflows. Its useful ideas—phase tracking and allowed backward loops—are absorbed here without its dependency/tooling footprint.
