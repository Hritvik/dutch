Type: grilling
Status: resolved
Assignee: Codex

# Design the mobile client architecture

## Question

What React Native/Expo application structure, navigation/state/data layers, secure-session and encrypted/local-storage boundaries, command/draft/sync state machines, accessibility approach, and test seams implement Dutch's interaction model consistently on iOS and Android?

## Answer

The Expo app uses route-only Expo Router files, native Home/Groups/Activity tabs, feature modules, a SQLite repository for authorized projections/drafts, SecureStore only for session material, ephemeral query transport state, and minimal UI state. A single-flight SessionController/SyncCoordinator governs system-browser PKCE, cursor application/full resync, and account/revocation wipe.

Financial drafts follow an explicit review/submission state machine: no auto-queue or optimistic balance update; a lost response is recovered with the same key, stale state becomes reviewable, and `Settle all` confirms fresh scope allocations. Virtualized lists, accessibility semantics, and device acceptance tests apply consistently across iOS and Android.

See [mobile client architecture](../../../docs/architecture/mobile-client-architecture.md) and [ADR 0006](../../../docs/adr/0006-sqlite-projections-and-explicit-online-drafts.md).

## Workflow observations

- The useful separation was durable projection vs ephemeral request/UI state; the SDLC skill should demand a data-classification table for every client architecture.
- Interaction requirements became implementation-ready when expressed as session/sync/draft state machines and device journeys, rather than screen lists alone.
