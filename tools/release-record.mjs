import { readFile } from "node:fs/promises";
import process from "node:process";

const DIGEST = /^sha256:[a-f0-9]{64}$/;
const REVISION = /^[A-Za-z0-9._-]{7,200}$/;

/**
 * Validates a release provenance record against the repository schema rules.
 * It deliberately ignores unknown fields instead of inventing defaults.
 */
export function validateReleaseRecord(record) {
  const issues = [];
  if (!record || typeof record !== "object" || Array.isArray(record)) {
    return { valid: false, issues: ["record must be an object"] };
  }
  if (record.schemaVersion !== 1) issues.push("schemaVersion must be 1");
  if (typeof record.artifactDigest !== "string" || !DIGEST.test(record.artifactDigest)) {
    issues.push("artifactDigest must be sha256:<64 hex>");
  }
  if (typeof record.sourceRevision !== "string" || !REVISION.test(record.sourceRevision)) {
    issues.push("sourceRevision must be at least 7 safe characters");
  }
  if (typeof record.builtAt !== "string" || Number.isNaN(Date.parse(record.builtAt))) {
    issues.push("builtAt must be an ISO date-time");
  }
  if (record.workflowRunUrl !== undefined) {
    try {
      const url = new URL(record.workflowRunUrl);
      if (url.protocol !== "https:") issues.push("workflowRunUrl must use https");
    } catch {
      issues.push("workflowRunUrl must be a URL");
    }
  }
  return { valid: issues.length === 0, issues };
}

export function buildReleaseRecord({
  artifactDigest,
  sourceRevision,
  builtAt = new Date().toISOString(),
  workflowRunUrl
}) {
  const record = {
    schemaVersion: 1,
    artifactDigest,
    sourceRevision,
    builtAt,
    ...(workflowRunUrl ? { workflowRunUrl } : {})
  };
  const result = validateReleaseRecord(record);
  if (!result.valid) {
    throw new Error(result.issues.join("; "));
  }
  return record;
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replaceAll("\\", "/"))) {
  const [command, file] = process.argv.slice(2);
  if (command === "validate" && file) {
    const record = JSON.parse(await readFile(file, "utf8"));
    const result = validateReleaseRecord(record);
    if (!result.valid) {
      console.error(result.issues.join("\n"));
      process.exitCode = 1;
    }
  } else {
    console.error("usage: node tools/release-record.mjs validate <record.json>");
    process.exitCode = 2;
  }
}
