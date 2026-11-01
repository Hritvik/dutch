# ADR 0003: Use a recipient-scoped Ledger sync journal

## Status

Accepted

## Context

Dutch mobile clients cache authorized read projections, but must receive membership/access changes honestly and never observe other Members' financial or contact information. A scope-filtered global feed complicates revocation/tombstones and risks authorization side channels.

## Decision

In the same local transaction that changes a Ledger projection, append compact change records to a per-Member ordered journal for its authorized recipients. Issue opaque, signed, recipient/authorization-epoch-bound cursors over that journal. Use 90-day retention and a recipient-bound snapshot plus `asOfSequence` full-sync recovery path.

## Consequences

Writes fan out to up to the Group membership cap, and journal growth must be monitored. In return, incremental sync is simple to authorize, changes converge by resource version, and true revocations have explicit tombstones without relying on the mobile cache as authority.
