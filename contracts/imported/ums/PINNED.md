# Pinned UMS association-report contract input

- Source: `ums` repository, commit `c17395a2b5e71ab3c8fc361d79999abf772b94d4`
- Files: `contracts/v1/schemas/association-report.json`,
  `contracts/v1/schemas/common.json`, and the four `association-report*.json` fixtures
- Decision: `portfolio-planning/capabilities/work/decisions/2026-11-05-ums-contract-decisions.md`
- Accepted specification: `ums/docs/contracts/proposed/association-report-v1.md`

sha256:

| File | sha256 |
| --- | --- |
| `association-report.json` (schema) | `8734706d103fa1996477bb8da7f22486b440e4f88af5a8c621a5f3c9459b2e03` |
| `common.json` (schema) | `97800a04dd6c31b104ad88ecad5d8b10fcd1e331484ac565d06c17a180fa8f29` |
| `fixtures/association-report.json` | `797e94ffff770c371602cf12084ce8689bb9f9b86fd4a1b7a75dd71e77c0ed05` |
| `fixtures/association-report-invalid-display.json` | `b9547806c282884e1baa3d83bc11d3e0c9fdcdbb080e767d3c99c6bcfb65f77a` |
| `fixtures/association-report-invalid-environment.json` | `3eb1ce4bf58683dfbcc8e8d6c7c32a821eebddfba039a48ddea2c438a24bf4a3` |
| `fixtures/association-report-invalid-tombstone.json` | `6240019129107e7e06e6170efda920c03253f914d178669ded914c7fc9dafac4` |

These files are immutable consumer input. UMS owns the schema and fixtures; Dutch pins a
read-only copy and validates them in CI. A new UMS version requires an explicit pin change,
never an edit in place.

Dutch expectations pinned by this copy:

1. `association-report.json` validates against the schema.
2. The display, environment, and tombstone negatives are rejected.
3. The tombstone shape forbids live fields (`generation`, `profileRevision`) and requires
   `retiredGeneration`, so a stale `ACTIVE` cannot be mistaken for a new life.
4. `aggregate.version` is the only ordering key; timestamps are never used for ordering.
