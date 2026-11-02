import fs from "node:fs";
import path from "node:path";

const requiredFiles = [
  "infrastructure/local/compose.yaml",
  "infrastructure/local/.env.example",
  "infrastructure/local/bin/up.sh",
  "infrastructure/local/bin/reset.sh",
  "infrastructure/local/bin/test-backend.sh",
  "infrastructure/local/bin/rehearse-restore.sh",
  "apps/mobile/eas.json",
  "tools/release-record.mjs",
  "tools/check-accessibility.mjs",
  "infrastructure/local/init/01-create-service-databases.sh",
  "infrastructure/local/README.md",
  "infrastructure/aws/modules/foundation/main.tf",
  "infrastructure/aws/modules/foundation/variables.tf",
  "infrastructure/aws/modules/foundation/outputs.tf",
  "infrastructure/aws/environments/development/main.tf",
  "infrastructure/aws/environments/staging/main.tf",
  "infrastructure/aws/environments/production/main.tf",
  "infrastructure/provenance/release-record.schema.json",
  ".github/workflows/quality.yml",
  "tools/scan-secrets.mjs",
];

export function scanTextForForbiddenSecrets(text) {
  const findings = [];
  if (/\bAKIA[0-9A-Z]{16}\b/.test(text)) findings.push("AWS access key");
  if (/\b(?:sk|rk|pk)_[A-Za-z0-9]{20,}\b/.test(text)) findings.push("API token");
  if (/-----BEGIN(?: [A-Z]+)? PRIVATE KEY-----/.test(text)) findings.push("private key");
  return findings;
}

export function inspectFoundation(repositoryRoot) {
  const issues = requiredFiles
    .filter((relativePath) => !fs.existsSync(path.join(repositoryRoot, relativePath)))
    .map((relativePath) => `missing ${relativePath}`);

  if (issues.length > 0) return issues;

  const compose = fs.readFileSync(
    path.join(repositoryRoot, "infrastructure/local/compose.yaml"),
    "utf8",
  );
  const initializer = fs.readFileSync(
    path.join(repositoryRoot, "infrastructure/local/init/01-create-service-databases.sh"),
    "utf8",
  );
  const workflow = fs.readFileSync(
    path.join(repositoryRoot, ".github/workflows/quality.yml"),
    "utf8",
  );
  const provenance = JSON.parse(
    fs.readFileSync(
      path.join(repositoryRoot, "infrastructure/provenance/release-record.schema.json"),
      "utf8",
    ),
  );

  for (const expected of ["postgres:16", "127.0.0.1", "01-create-service-databases.sh"]) {
    if (!compose.includes(expected)) issues.push(`local compose lacks ${expected}`);
  }
  for (const service of ["iam", "ums", "ledger", "notification"]) {
    if (!initializer.includes(`create_service_database ${service}`)) {
      issues.push(`local initializer lacks ${service} database isolation`);
    }
  }
  const rehearsal = fs.readFileSync(
    path.join(repositoryRoot, "infrastructure/local/bin/rehearse-restore.sh"),
    "utf8",
  );
  for (const expected of ["pg_dump", "pg_restore", "flyway_schema_history", "projection"]) {
    if (!rehearsal.includes(expected)) issues.push(`restore rehearsal lacks ${expected}`);
  }
  const eas = JSON.parse(fs.readFileSync(path.join(repositoryRoot, "apps/mobile/eas.json"), "utf8"));
  for (const profile of ["development", "preview", "production"]) {
    if (!eas.build?.[profile]) issues.push(`EAS build profile ${profile} is missing`);
  }
  for (const expected of [
    "verifyServiceBoundaries",
    "verifyContracts",
    "npm ci",
    " validate",
    "scan-secrets.mjs",
  ]) {
    if (!workflow.includes(expected)) issues.push(`quality workflow lacks ${expected}`);
  }
  if (provenance.properties?.schemaVersion?.const !== 1) {
    issues.push("release provenance schema version must be 1");
  }
  return issues;
}
