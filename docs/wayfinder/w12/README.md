# W-12 — Dutch honest profile states

**Reserved for the UMS agent.** This board is ready and unclaimed; the IAM session must not claim it.

Scope: `GET /api/v1/me/profile-state`, the profile-required/ready split, pinned `cor_` correlation and the
IAM error envelope. Write paths are disjoint between W12-A (controller) and W12-B (filter/error/security).

Gate: `./gradlew :services:ums:test` with JDK 21 and the colima Testcontainers environment.
