import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

import { buildReleaseRecord, validateReleaseRecord } from "../../tools/release-record.mjs";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
);

test("the example release record validates", async () => {
  const record = JSON.parse(await readFile(
    path.join(repositoryRoot, "infrastructure/provenance/release-record.example.json"),
    "utf8",
  ));
  assert.deepEqual(validateReleaseRecord(record), { valid: true, issues: [] });
});

test("a release record rejects a short revision and a malformed digest", () => {
  const result = validateReleaseRecord({
    schemaVersion: 1,
    artifactDigest: "sha256:abc",
    sourceRevision: "abc",
    builtAt: "not-a-date",
  });
  assert.equal(result.valid, false);
  assert.equal(result.issues.length, 3);
});

test("a built record keeps the workflow provenance", () => {
  const record = buildReleaseRecord({
    artifactDigest: `sha256:${"a".repeat(64)}`,
    sourceRevision: "abcdef1",
    workflowRunUrl: "https://github.com/example/dutch/actions/runs/2",
  });
  assert.equal(record.schemaVersion, 1);
  assert.match(record.workflowRunUrl, /^https:/);
});
