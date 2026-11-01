import fs from "node:fs";
import path from "node:path";

import { scanTextForForbiddenSecrets } from "../infrastructure/verify-foundation.mjs";

const ignoredDirectories = new Set([
  ".git",
  ".gradle",
  ".gradle-user-home",
  "build",
  "node_modules",
]);
const root = process.cwd();
const findings = [];

function visit(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!ignoredDirectories.has(entry.name)) visit(path.join(directory, entry.name));
      continue;
    }
    if (!entry.isFile()) continue;
    const filename = path.join(directory, entry.name);
    const relative = path.relative(root, filename);
    if (relative === "infrastructure/local/.env") continue;
    const buffer = fs.readFileSync(filename);
    if (buffer.includes(0)) continue;
    for (const type of scanTextForForbiddenSecrets(buffer.toString("utf8"))) {
      findings.push(`${relative}: ${type}`);
    }
  }
}

visit(root);
if (findings.length > 0) {
  console.error(`Secret policy violation:\n${findings.join("\n")}`);
  process.exitCode = 1;
}
