# ADR 0002: Use serializable append-only Ledger writes with ordered stream locks

## Status

Accepted

## Context

Dutch records immutable, multi-payer financial obligations and uncapped settlements. It must preserve exact reversals, idempotency, and current balance correctness under concurrent mobile commands, while events and mobile caches remain asynchronous/non-authoritative.

## Decision

Use short PostgreSQL `SERIALIZABLE` transactions for all accepted Ledger financial commands. Lock every affected durable financial stream in a global order, then source roots; persist immutable source/entry/activity/outbox/idempotency facts and synchronous derived projections in that one transaction. Retry serialization/deadlock failures a bounded number of times and expose exhaustion as temporary availability.

## Consequences

The service must use explicit transaction-aware SQL, a robust retry wrapper, deferred aggregate-invariant checks, and concurrency integration tests. Commands may wait or retry under contention, but no financial write silently overwrites, partially applies, or relies on event delivery for correctness.
