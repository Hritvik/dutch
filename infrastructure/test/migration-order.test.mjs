import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";
import { readdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
);

test("each service has contiguous, unique Flyway versions", async () => {
  for (const service of ["iam", "ums", "ledger", "notification"]) {
    const directory = path.join(repositoryRoot, "services", service,
      "src/main/resources/db/migration");
    const files = await readdir(directory);
    const versions = files
      .map((file) => /^V(\d+)__.+\.sql$/.exec(file))
      .filter(Boolean)
      .map((match) => Number(match[1]))
      .sort((left, right) => left - right);
    assert.ok(versions.length > 0, `${service} has no migrations`);
    assert.equal(new Set(versions).size, versions.length, `${service} has duplicate versions`);
    for (let index = 0; index < versions.length; index += 1) {
      assert.equal(versions[index], index + 1,
        `${service} migration versions are not contiguous at ${versions[index]}`);
    }
  }
});
