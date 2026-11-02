import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { parse } from 'yaml';

import {
  findProtectedHttpValues,
  findSensitiveValues,
  validateContracts,
  validateEventFixture,
  validateHttpFixture
} from '../validate-contracts.mjs';

const contractsRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

test('validates the published Dutch mapping and profile fixtures', async () => {
  const result = await validateContracts(contractsRoot);
  assert.deepEqual(result, { valid: true, issues: [] });
});

test('rejects a fixture that no longer matches the published success envelope', async () => {
  const fixture = JSON.parse(await readFile(
    path.join(contractsRoot, 'http/fixtures/success/health-ready.json'), 'utf8'));
  delete fixture.data.status;
  const result = await validateHttpFixture(contractsRoot, 'HealthResponse', fixture);
  assert.equal(result.valid, false);
  assert.match(result.issues[0], /status/);
});

test('rejects a mapping event carrying a global identity', async () => {
  const fixture = JSON.parse(await readFile(
    path.join(contractsRoot, 'events/fixtures/dutch/forbidden-identity.json'), 'utf8'));
  const result = await validateEventFixture(
    contractsRoot, 'dutch.member-profile.mapping-changed.v1', fixture);
  assert.equal(result.valid, false);
  assert.match(result.issues.join('\n'), /additional properties/i);
});

test('rejects a mapping event whose envelope disagrees with its payload', async () => {
  const fixture = JSON.parse(await readFile(
    path.join(contractsRoot, 'events/fixtures/dutch/member-profile-mapping-changed.json'), 'utf8'));
  fixture.destination.appRegistrationId = 'another-app';
  const result = await validateEventFixture(
    contractsRoot, 'dutch.member-profile.mapping-changed.v1', fixture);
  assert.equal(result.valid, false);
  assert.match(result.issues.join('\n'), /destination\.appRegistrationId/);
});

test('validates profile-state and profile-ready responses', async () => {
  for (const [file, schema] of [
    ['http/fixtures/success/profile-state-required.json', 'ProfileStateResponse'],
    ['http/fixtures/success/profile-state-ready.json', 'ProfileStateResponse'],
    ['http/fixtures/success/profile-ready.json', 'ProfileReadyResponse']
  ]) {
    const fixture = JSON.parse(await readFile(path.join(contractsRoot, file), 'utf8'));
    assert.deepEqual(await validateHttpFixture(contractsRoot, schema, fixture),
      { valid: true, issues: [] });
  }
});

test('rejects token fields and token-shaped values in any published fixture', () => {
  const violations = findSensitiveValues({
    access_token: 'header.payload.signature',
    nested: { authorization: 'Bearer eyJhbGciOiJSUzI1NiJ9.payload.signature' }
  });
  assert.equal(violations.length, 3);
  assert.match(violations.join('\n'), /access_token.*forbidden field name/i);
  assert.match(violations.join('\n'), /authorization.*forbidden field name/i);
  assert.match(violations.join('\n'), /token-like value/i);
});

test('rejects an App Subject or global identity in an HTTP response', () => {
  const violations = findProtectedHttpValues({
    data: { appSubject: 'aps_0123456789012345678901ab', identityId: 'hid_0123456789012345678901' }
  });
  assert.equal(violations.length, 2);
  assert.match(violations.join('\n'), /App Subject/);
  assert.match(violations.join('\n'), /Human Identity ID/);
});

test('the 409 body is pinned to profile_required', async () => {
  const required = JSON.parse(await readFile(
    path.join(contractsRoot, 'http/fixtures/error/profile-required.json'), 'utf8'));
  assert.deepEqual(
    await validateHttpFixture(contractsRoot, 'ProfileRequiredResponse', required),
    { valid: true, issues: [] });
  const other = structuredClone(required);
  other.error.code = 'not_found';
  other.error.message = 'Resource was not found';
  const result = await validateHttpFixture(contractsRoot, 'ProfileRequiredResponse', other);
  assert.equal(result.valid, false);
});

test('publishes only the Dutch mapping channel', async () => {
  const asyncapi = parse(await readFile(
    path.join(contractsRoot, 'events/asyncapi.yaml'), 'utf8'));
  assert.deepEqual(Object.keys(asyncapi.channels), ['dutch.member-profile.mapping-changed.v1']);
  assert.equal(asyncapi.info.version, '2.0.0');
});

test('requires bearer JWT authentication on both profile routes', async () => {
  const openApi = parse(await readFile(
    path.join(contractsRoot, 'http/gateway/openapi.yaml'), 'utf8'));
  assert.deepEqual(openApi.paths['/api/v1/me'].get.security, [{ bearerAuth: [] }]);
  assert.deepEqual(openApi.paths['/api/v1/me/profile-state'].get.security, [{ bearerAuth: [] }]);
  assert.equal(openApi.paths['/api/v1/me'].get.responses['409'].content['application/json']
    .schema.$ref, '#/components/schemas/ProfileRequiredResponse');
});

test('rejects an unknown group role in the Ledger contract', async () => {
  const fixture = JSON.parse(await readFile(
    path.join(contractsRoot, 'http/fixtures/ledger/group-view.json'), 'utf8'));
  fixture.data.memberships[0].role = 'OWNER';
  const result = await validateHttpFixture(
    contractsRoot, 'GroupResponse', fixture, 'http/ledger/openapi.yaml');
  assert.equal(result.valid, false);
  assert.match(result.issues.join('\n'), /role/);
});

test('requires bearer JWT authentication on every group route', async () => {
  const openApi = parse(await readFile(
    path.join(contractsRoot, 'http/ledger/openapi.yaml'), 'utf8'));
  for (const [route, operations] of Object.entries(openApi.paths)) {
    for (const [method, operation] of Object.entries(operations)) {
      assert.deepEqual(operation.security, [{ bearerAuth: [] }], `${method} ${route}`);
    }
  }
});

test('publishes the Direct Expense and Series command surface', async () => {
  const openApi = parse(await readFile(
    path.join(contractsRoot, 'http/ledger/openapi.yaml'), 'utf8'));
  assert.deepEqual(openApi.paths['/api/v1/direct-expenses'].post.security, [{ bearerAuth: [] }]);
  assert.deepEqual(openApi.paths['/api/v1/series'].post.security, [{ bearerAuth: [] }]);
  assert.equal(
    openApi.paths['/api/v1/series/occurrences/{occurrenceId}/submit'].post.responses['200']
      .content['application/json'].schema.$ref,
    '#/components/schemas/SeriesSubmitResponse');
  assert.equal(
    openApi.paths['/api/v1/direct-expenses'].post.responses['200']
      .content['application/json'].schema.$ref,
    '#/components/schemas/DirectExpenseResponse');
});

test('validates the S5 Direct Expense, Series, occurrence, and sync fixtures', async () => {
  const cases = [
    ['DirectExpenseResponse', 'direct-expense-response.json'],
    ['SeriesResponse', 'series-response-direct.json'],
    ['OccurrenceResponse', 'occurrence-response.json'],
    ['SyncPageResponse', 'sync-page-response.json']
  ];
  for (const [schema, file] of cases) {
    const fixture = JSON.parse(await readFile(
      path.join(contractsRoot, 'http/fixtures/ledger', file), 'utf8'));
    const result = await validateHttpFixture(
      contractsRoot, schema, fixture, 'http/ledger/openapi.yaml');
    assert.equal(result.valid, true, `${schema}: ${result.issues.join('; ')}`);
  }
});

test('requires the split-specific input in the expense contract', async () => {
  const fixture = JSON.parse(await readFile(
    path.join(contractsRoot, 'http/fixtures/ledger/create-expense-exact.json'), 'utf8'));
  delete fixture.exactShares;
  const result = await validateHttpFixture(
    contractsRoot, 'CreateExpenseRequest', fixture, 'http/ledger/openapi.yaml');
  assert.equal(result.valid, false);
  assert.match(result.issues.join('\n'), /exactShares/);
});
