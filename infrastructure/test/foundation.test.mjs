import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  inspectFoundation,
  scanTextForForbiddenSecrets,
} from "../verify-foundation.mjs";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
);

test("the local environment, CI, and validation-only AWS topology are complete", () => {
  assert.deepEqual(inspectFoundation(repositoryRoot), []);
});

test("the secret policy catches credential-shaped values while permitting placeholders", () => {
  assert.deepEqual(
    scanTextForForbiddenSecrets(`aws_access_key_id = ${"AKIA"}IOSFODNN7EXAMPLE`),
    ["AWS access key"],
  );
  assert.deepEqual(scanTextForForbiddenSecrets("API_KEY=<set-in-local-env>"), []);
});
