# Pinned IAM contract input

- Bundle: `iam-contracts-1.0.0.zip`
- sha256: `7bb76067a0e2e5ea0efd1e4aca981c3ef3fead2abb7f9e467fadca8dadc81195`
- Imported for W-10: `event-envelope.schema.json` (event envelope) and the error-envelope shape
  (`code`/`message`/`correlationId` plus optional allowlisted `details`) reflected in the Dutch `ErrorResponse`.
- These files are immutable consumer input. Dutch-owned contracts must not edit them; a new IAM
  bundle version requires an explicit pin change.
