Type: grilling
Status: resolved
Assignee: Codex

# Design identity and membership integration

## Question

What concrete IAM, UMS, Gateway, and Ledger integrations—including token claims, verified-identifier claim flows, local member projections, service authorization, event recovery, and deletion/anonymization boundaries—implement the agreed identity/membership model safely?

## Answer

IAM is the OAuth/OIDC authorization server and raw identifier vault; UMS owns profiles, Placeholder Members, Claim Offers, and canonicalization; Ledger owns Group Membership and local financial authorization. IAM exposes opaque Identifier References for Placeholder contact matching, so UMS can drive seven-day explicit Claim Offers without storing raw email/phone or receiving them in events/tokens.

The mobile app uses external-browser authorization code + S256 PKCE, 15-minute access tokens with immutable authentication claims only, and rotating refresh-token families. Ledger uses PII-free UMS mapping/display projections plus local membership and IAM deny-list events, so financial commands do not synchronously depend on IAM/UMS. Claims are visibly pending until Ledger consumes the event; final deletion anonymizes presentation without deleting financial evidence.

See [identity and membership integration](../../../docs/architecture/identity-membership-integration.md) and [ADR 0005](../../../docs/adr/0005-iam-identifier-vault-and-local-ledger-authorization.md). `CONTEXT.md` now defines Identifier Reference.

## Workflow observations

- A clear raw-data ownership rule exposed the missing bridge between Placeholder contact input and exact match: opaque Identifier References. The SDLC skill should surface data-minimization gaps whenever two services need the same sensitive input.
- This decision required a primary-source check because framework behavior around native public-client refresh support is a security-sensitive implementation constraint.
