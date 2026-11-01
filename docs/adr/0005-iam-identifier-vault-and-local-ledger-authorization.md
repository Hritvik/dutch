# ADR 0005: Make IAM the identifier vault and authorize Ledger locally

## Status

Accepted

## Context

Dutch must support email/phone registration and Placeholder claims while maintaining separate IAM/UMS/Ledger ownership. Letting UMS and IAM both hold raw contacts, or requiring Ledger to synchronously call IAM/UMS for a financial command, would expand PII exposure and turn upstream availability into financial-write availability.

## Decision

IAM owns raw identifier/capability data and exposes opaque Identifier References for Placeholder matching. UMS owns Claim Offers and canonical Member decisions. Ledger receives only PII-free, versioned mapping/display projections and authorizes commands locally from those plus Group Membership. IAM uses OAuth/OIDC authorization code + S256 PKCE and rotating refresh-token families.

## Consequences

Identifier matching requires a narrowly scoped IAM internal service operation and lifecycle retry/reconciliation. Ledger may be temporarily behind UMS but stays available under the documented projection/lock rules; claims truthfully remain pending until Ledger consumes the event.
