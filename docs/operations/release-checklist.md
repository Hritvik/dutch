# Release checklist

## Record

- Generate `infrastructure/provenance/release-record.json` with
  `node tools/release-record.mjs build <artifactDigest> <sourceRevision> <out.json>
  [workflowRunUrl]`, then validate it with
  `node tools/release-record.mjs validate <out.json>`.
- Validate it: `node tools/release-record.mjs validate <record.json>`.
- Approver, rollback owner, and forward-repair owner must be named in the release
  ticket.

## Gates

- [ ] `./gradlew test jacocoTestReport verifyServiceBoundaries verifyContracts`
- [ ] `cd contracts && npm test && npm audit --omit=dev --audit-level=critical`
- [ ] `cd apps/mobile && npm test && npm run lint && npm run typecheck && npm audit`
- [ ] `node --test infrastructure/test/*.test.mjs`
- [ ] Provider runtime evidence referenced for development/staging/production:
      reviewed plan/apply, private networking, isolated and restorable storage,
      digest-pinned deployment, and alert delivery.
- [ ] Migration rehearsal: the version sequence is contiguous and each service's
      Flyway history is clean.
- [ ] Restore rehearsal: `infrastructure/local/bin/rehearse-restore.sh` passes;
      Ledger projection reconciliation drift is zero and outbox counts match.
- [ ] Outbox/DLQ rehearsal recorded; oldest-unpublished and DLQ alerts configured.
- [ ] `ums/W-18` and `iam/W-13` are accepted; no adapter evidence is presented as
      provider acceptance.
- [ ] Privacy export/deletion and safety-report paths exercised; no raw contact or
      token data in logs, contracts, or fixtures.
- [ ] Accessibility (labels, focus order, contrast, reduced motion), performance
      (cold start, list scroll, sync p95), and security review evidence attached.
- [ ] Dutch module accepted on a host build (ADR 0011): signed-out, profile-required,
      mapping-unavailable, pending-command, and active states behave in the shell.
- [ ] Module portability check: the Dutch module renders in a minimal test shell that
      provides only the declared host interface.
- [ ] Host release cadence and the path for shipping a Dutch defect fix are known and
      recorded; the host build identity containing each Dutch module version is noted.
- [ ] Store data declaration covering Dutch's financial and contact data approved, and
      the store-level account-deletion entry reaches Dutch's own deletion flow.
- [ ] Support/legal pages approved. Store metadata, signing, and submission are the
      host's at launch and are not Dutch gates (ADR 0011).
- [ ] Phase 2 only: standalone Dutch bundle, EAS development/preview/production
      profiles, and Dutch store submission.
- [ ] Rollback/forward-repair plan and on-call routing confirmed.

## Promotion

Build once; promote the recorded digest from preview to production. Do not rebuild
between environments. Record the final store rollout percentage and the decision to
expand it in the release ticket.
