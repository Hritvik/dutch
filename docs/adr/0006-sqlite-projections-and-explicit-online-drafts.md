# ADR 0006: Use SQLite projections and explicit online Local Drafts

## Status

Accepted

## Context

Dutch must offer useful offline reading while preserving financial truth, stale-write recovery, idempotency, and privacy on a cross-platform app.

## Decision

Persist only authorized read projections, tombstones, cursor, and Local Drafts in SQLite; persist session material only in SecureStore. Use a single-flight sync coordinator and explicit online review/submission with durable idempotency keys. Never queue or apply financial writes locally.

## Consequences

Offline creation remains useful but cannot promise acceptance until a fresh server review succeeds. The app gains an honest recovery model for stale state, lost responses, cursor expiry, revocation, and account switching instead of an opaque offline mutation queue.
