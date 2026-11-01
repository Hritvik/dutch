# ADR 0008: Deliver vertical slices with invariant-first verification

## Status

Accepted

## Context

Dutch spans services, immutable financial data, a mobile client, asynchronous events, and managed infrastructure. A layer-by-layer build would postpone integration, privacy, and recovery risks until late in delivery.

## Decision

Build a short shared foundation then complete vertical capability slices. Require red-first tests and proportionate invariant, contract, device, recovery, and release evidence at the slice that creates the risk.

## Consequences

Early slices carry more setup and test work, but they expose cross-boundary failures before the product surface grows. Notification and visual polish cannot mask financial, authorization, or offline correctness gaps.
