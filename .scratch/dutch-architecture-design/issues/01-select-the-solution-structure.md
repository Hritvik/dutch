Type: grilling
Status: resolved
Assignee: Codex

# Select the solution structure

## Question

What repository/module structure, service-to-service interaction boundaries, and architectural documentation set will turn Dutch's already-settled service topology into a buildable solution without duplicating domain logic or creating accidental distributed transactions?

## Answer

Dutch will use a modular monorepo with independently deployable Gateway, IAM, UMS, Obligation Ledger, and Notification services plus an independently built React Native/Expo mobile app. Each service owns its runtime, migrations, database role/schema, APIs, and outbox; service interaction is only versioned HTTP or immutable events. The Gateway owns no domain data and no domain orchestration.

The root Gradle build will become a multi-project backend build; the Expo app retains its own JavaScript tooling. `contracts` holds versioned HTTP and event schemas, while a narrow foundation library may provide only cross-cutting technical primitives—never domain entities, ledger algorithms, authorization, repositories, or migrations. This prevents the initial Spring Boot scaffold from becoming a shared application kernel or a distributed monolith.

The full structure, allowed dependencies, runtime interaction diagram, and consequences are in [solution structure](../../../docs/architecture/solution-structure.md). The hard-to-reverse repository/deployment choice is captured in [ADR 0001](../../../docs/adr/0001-modular-monorepo-with-independent-service-delivery.md).

## Workflow observations

- The architecture map could reuse the completed requirements and handoff directly; an orchestration skill should make that artifact handoff explicit instead of copying requirements into a new phase.
- The useful gate was a single ownership rule: do not share domain code or databases for convenience. This is a candidate entry criterion for a future SDLC skill's architecture phase.
