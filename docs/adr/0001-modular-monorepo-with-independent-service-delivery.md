# ADR 0001: Use a modular monorepo with independent service delivery

## Status

Accepted

## Context

Dutch requires separate IAM, UMS, Obligation Ledger, Notification, and Gateway boundaries, each with service-owned data and no distributed financial transaction. The repository currently contains one Spring Boot scaffold, while the mobile client uses React Native/Expo and a separate JavaScript toolchain.

## Decision

Use one repository with Gradle service subprojects, a separately managed Expo app, contract sources, narrowly limited cross-cutting libraries, and infrastructure code. Each service is independently deployable and owns its migration history and database role/schema. No shared domain library and no cross-service database access are permitted.

## Consequences

This preserves coordinated delivery and simple repository navigation without weakening data ownership. It adds contract compatibility, local-topology, and service-specific CI work, which Phase 0 must establish before feature delivery.
