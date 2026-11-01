# W11-C review 01

Decision: reject

Findings: MEDIUM conflicting reuse was thrown away with no durable quarantine record; no database-role test proved UMS cannot connect to the IAM database; the emitted mapping event was never validated against the pinned Dutch schema despite the packet claiming it. LOW reorder/version not stored, checkpoint not monotonic, correlation format unvalidated, environment default, Testcontainers skip posture, unused legacy record.
