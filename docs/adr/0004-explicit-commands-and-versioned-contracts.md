# ADR 0004: Use explicit financial commands and versioned HTTP/event contracts

## Status

Accepted

## Context

Dutch financial history is append-only: corrections, voids, restores, and `Settle all` allocations are meaningful actions, not ordinary mutable rows. Mobile clients need reliable retries and honest stale-state recovery, while downstream consumers must tolerate at-least-once delivery.

## Decision

Expose commands as intent-specific REST `POST` routes protected by idempotency keys and resource-version preconditions. Publish OpenAPI, JSON Schema, and AsyncAPI sources as the cross-service contract truth. Use immutable event envelopes with event IDs, aggregate versions, schema versions, correlation, and causation metadata.

## Consequences

The API has more endpoints than a generic CRUD surface, but intent and correction lifecycle remain legible. Compatibility fixtures, contract tests, consumer deduplication, and schema evolution reviews become mandatory CI work.
