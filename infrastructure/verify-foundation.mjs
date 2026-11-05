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
  "infrastructure/observability/dutch-alerts.yaml",
  "infrastructure/observability/prometheus-scrape.example.yaml",
  "infrastructure/observability/grafana-dutch.json",
  "services/Dockerfile",
  ".dockerignore",
  ".github/workflows/release.yml",
  ".github/dependabot.yml",
  "infrastructure/local/init/01-create-service-databases.sh",
  "infrastructure/local/README.md",
  "docs/adr/0010-delegate-production-runtime-provisioning.md",
  "docs/adr/0011-ship-inside-super-app-before-standalone.md",
  "infrastructure/provenance/release-record.schema.json",
  ".github/workflows/quality.yml",
  "tools/scan-secrets.mjs",
];

export function scanTextForForbiddenSecrets(text) {
  const findings = [];
  if (/\bAKIA[0-9A-Z]{16}\b/.test(text)) findings.push("AWS access key");
  if (/\b(?:sk|rk|pk)_[A-Za-z0-9]{20,}\b/.test(text)) findings.push("API token");
  if (/\bsk_live_[A-Za-z0-9]{16,}\b/.test(text)) findings.push("Stripe live key");
  if (/\bghp_[A-Za-z0-9]{30,}\b/.test(text)) findings.push("GitHub token");
  if (/\bgithub_pat_[A-Za-z0-9_]{20,}\b/.test(text)) findings.push("GitHub fine-grained token");
  if (/\bxox[baprs]-[A-Za-z0-9-]{10,}\b/.test(text)) findings.push("Slack token");
  if (/\bAIza[0-9A-Za-z_-]{35}\b/.test(text)) findings.push("Google API key");
  if (/\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/.test(text)) {
    findings.push("JWT credential");
  }
  if (/postgres(?:ql)?:\/\/[^\s:/]+:[^\s@]+@/.test(text)) {
    findings.push("Postgres URL with password");
  }
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
  const scrape = fs.readFileSync(
    path.join(repositoryRoot, "infrastructure/observability/prometheus-scrape.example.yaml"),
    "utf8",
  );
  for (const job of ["ledger", "notification", "gateway"]) {
    if (!scrape.includes(`job_name: ${job}`)) issues.push(`scrape config lacks ${job}`);
  }
  const release = fs.readFileSync(
    path.join(repositoryRoot, ".github/workflows/release.yml"),
    "utf8",
  );
  for (const expected of ["ghcr.io", "provenance:", "sbom:", "attest-build-provenance"]) {
    if (!release.includes(expected)) issues.push(`release workflow lacks ${expected}`);
  }
  const dependabot = fs.readFileSync(
    path.join(repositoryRoot, ".github/dependabot.yml"),
    "utf8",
  );
  for (const ecosystem of ["gradle", "npm", "github-actions", "docker"]) {
    if (!dependabot.includes(`package-ecosystem: ${ecosystem}`)) {
      issues.push(`dependabot lacks ${ecosystem}`);
    }
  }
  const dashboard = JSON.parse(fs.readFileSync(
    path.join(repositoryRoot, "infrastructure/observability/grafana-dutch.json"),
    "utf8",
  ));
  for (const metric of ["outbox_oldest_unpublished_seconds", "outbox_unpublished",
    "outbox_dead_lettered_total", "notification_deliveries_total",
    "gateway_rate_limited_total"]) {
    if (!JSON.stringify(dashboard).includes(metric)) {
      issues.push(`Grafana dashboard lacks ${metric}`);
    }
  }
  const alerts = fs.readFileSync(
    path.join(repositoryRoot, "infrastructure/observability/dutch-alerts.yaml"),
    "utf8",
  );
  for (const alert of ["OutboxUnpublishedLag", "OutboxDeadLetter",
    "NotificationDeliveryFailures", "GatewayRateLimitShare"]) {
    if (!alerts.includes(alert)) issues.push(`alert rules lack ${alert}`);
  }
  const checklist = fs.readFileSync(
    path.join(repositoryRoot, "docs/operations/release-checklist.md"),
    "utf8",
  );
  for (const expected of ["host build", "minimal test shell", "Phase 2 only"]) {
    if (!checklist.includes(expected)) {
      issues.push(`release checklist lacks the ${expected} gate`);
    }
  }
  const eas = JSON.parse(fs.readFileSync(path.join(repositoryRoot, "apps/mobile/eas.json"), "utf8"));
  for (const profile of ["development", "preview", "production"]) {
    if (!eas.build?.[profile]) issues.push(`EAS build profile ${profile} is missing`);
  }
  for (const expected of [
    "verifyServiceBoundaries",
    "verifyContracts",
    "npm ci",
    "node --test",
    "scan-secrets.mjs",
    "check-accessibility.mjs",
    "release-record.mjs",
  ]) {
    if (!workflow.includes(expected)) issues.push(`quality workflow lacks ${expected}`);
  }
  if (provenance.properties?.schemaVersion?.const !== 1) {
    issues.push("release provenance schema version must be 1");
  }
  const releasesRoot = path.join(repositoryRoot, "infrastructure/provenance/releases");
  if (!fs.existsSync(releasesRoot)) {
    issues.push("missing infrastructure/provenance/releases");
  } else {
    const records = fs
      .readdirSync(releasesRoot, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .flatMap((version) =>
        fs
          .readdirSync(path.join(releasesRoot, version.name))
          .filter((file) => file.endsWith(".json"))
          .map((file) => path.join(releasesRoot, version.name, file)),
      );
    for (const recordPath of records) {
      const record = JSON.parse(fs.readFileSync(recordPath, "utf8"));
      if (record.schemaVersion !== 1) {
        issues.push(`${path.relative(repositoryRoot, recordPath)} schemaVersion must be 1`);
      }
      if (!/^sha256:[a-f0-9]{64}$/.test(record.artifactDigest ?? "")) {
        issues.push(`${path.relative(repositoryRoot, recordPath)} needs an image digest`);
      }
      if (!record.sourceRevision) {
        issues.push(`${path.relative(repositoryRoot, recordPath)} needs a source revision`);
      }
    }
  }
  return issues;
}
