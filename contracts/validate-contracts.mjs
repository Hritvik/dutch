import { readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import SwaggerParser from '@apidevtools/swagger-parser';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { parse } from 'yaml';

const forbiddenFieldNames = new Set([
  'email', 'phone', 'password', 'credential', 'accesstoken', 'refreshtoken', 'authorization', 'secret',
  'identityid', 'humanidentityid', 'appsubject', 'subjecttoken', 'verifier'
]);

const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const phonePattern = /^\+[1-9]\d{6,14}$/;
const tokenPattern = /^(?:Bearer\s+)?eyJ[A-Za-z0-9_-]*\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/i;
const protectedValuePatterns = [
  [/^aps_[A-Za-z0-9_-]{22,128}$/, 'App Subject'],
  [/^hid_[A-Za-z0-9_-]{22,128}$/, 'Human Identity ID'],
  [/^(?:acd|vid|fac|rcv|arq|evt|aud)_/, 'IAM artifact identifier'],
  [/^Bearer\s+/i, 'bearer credential']
];

function normalizeFieldName(fieldName) {
  return fieldName.replaceAll(/[-_]/g, '').toLowerCase();
}

async function readJson(filePath) { return JSON.parse(await readFile(filePath, 'utf8')); }
async function readYaml(filePath) { return parse(await readFile(filePath, 'utf8')); }

function createValidator() {
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  return ajv;
}

function formatErrors(errors = []) {
  return errors.map((error) => `${error.instancePath || '/'} ${error.message}`);
}

export function findSensitiveValues(value, location = '$', options = {}) {
  const allowProtected = options.allowProtected === true;
  if (Array.isArray(value)) {
    return value.flatMap((entry, index) => findSensitiveValues(entry, `${location}[${index}]`, options));
  }
  if (value && typeof value === 'object') {
    return Object.entries(value).flatMap(([key, entry]) => {
      const nestedLocation = `${location}.${key}`;
      const violations = forbiddenFieldNames.has(normalizeFieldName(key)) && !(allowProtected && normalizeFieldName(key) === 'appsubject')
        ? [`${nestedLocation} contains a forbidden field name`]
        : [];
      return violations.concat(findSensitiveValues(entry, nestedLocation, options));
    });
  }
  if (typeof value === 'string') {
    if (emailPattern.test(value) || phonePattern.test(value)) {
      return [`${location} contains a raw identifier-like value`];
    }
    if (tokenPattern.test(value)) {
      return [`${location} contains a token-like value`];
    }
  }
  return [];
}

export function findProtectedHttpValues(value, location = '$') {
  if (Array.isArray(value)) {
    return value.flatMap((entry, index) => findProtectedHttpValues(entry, `${location}[${index}]`));
  }
  if (value && typeof value === 'object') {
    return Object.entries(value).flatMap(([key, entry]) => findProtectedHttpValues(entry, `${location}.${key}`));
  }
  if (typeof value === 'string') {
    return protectedValuePatterns
      .filter(([pattern]) => pattern.test(value))
      .map(([, label]) => `${location} exposes a ${label}`);
  }
  return [];
}

async function loadOpenApi(contractsRoot, relative = 'http/gateway/openapi.yaml') {
  const openApiPath = path.join(contractsRoot, relative);
  await SwaggerParser.validate(openApiPath);
  return SwaggerParser.dereference(openApiPath);
}

export async function validateHttpFixture(
  contractsRoot, schemaName, fixture, relative = 'http/gateway/openapi.yaml') {
  const openApi = await loadOpenApi(contractsRoot, relative);
  const schema = openApi.components?.schemas?.[schemaName];
  if (!schema) {
    return { valid: false, issues: [`Published schema ${schemaName} was not found.`] };
  }
  const validate = createValidator().compile(schema);
  return validate(fixture) ? { valid: true, issues: [] } : { valid: false, issues: formatErrors(validate.errors) };
}

const eventSchemas = {
  'dutch.member-profile.mapping-changed.v1':
    'events/dutch/json-schema/dutch.member-profile.mapping-changed.v1.schema.json',
  'ledger.expense.committed.v1':
    'events/ledger/json-schema/ledger.expense.committed.v1.schema.json',
  'ledger.settlement.committed.v1':
    'events/ledger/json-schema/ledger.settlement.committed.v1.schema.json'
};

function eventInvariantIssues(fixture, eventType) {
  const issues = [];
  const payload = fixture?.payload ?? {};
  if (eventType === 'dutch.member-profile.mapping-changed.v1') {
    if (fixture?.aggregate?.id !== payload.memberId) {
      issues.push('aggregate.id must equal payload.memberId');
    }
    if (fixture?.aggregate?.version !== payload.mappingVersion) {
      issues.push('aggregate.version must equal payload.mappingVersion');
    }
    if (fixture?.destination?.appRegistrationId !== payload.appRegistrationId) {
      issues.push('destination.appRegistrationId must equal payload.appRegistrationId');
    }
  }
  if (eventType === 'ledger.expense.committed.v1'
      && fixture?.aggregate?.id !== payload.expenseId) {
    issues.push('aggregate.id must equal payload.expenseId');
  }
  if (eventType === 'ledger.settlement.committed.v1'
      && fixture?.aggregate?.id !== payload.settlementId) {
    issues.push('aggregate.id must equal payload.settlementId');
  }
  return issues;
}

export async function validateEventFixture(contractsRoot, eventType, fixture) {
  const schemaPath = eventSchemas[eventType];
  if (!schemaPath) {
    return { valid: false, issues: [`Published event schema ${eventType} was not found.`] };
  }
  const schema = await readJson(path.join(contractsRoot, schemaPath));
  const sensitiveValueIssues = findSensitiveValues(fixture, '$', { allowProtected: true });
  const issues = [...sensitiveValueIssues, ...eventInvariantIssues(fixture, eventType)];
  const validate = createValidator().compile(schema);
  if (!validate(fixture)) issues.push(...formatErrors(validate.errors));
  return { valid: issues.length === 0, issues };
}

const httpFixtures = [
  ['health-ready', 'HealthResponse', 'http/fixtures/success/health-ready.json'],
  ['profile-required', 'ErrorResponse', 'http/fixtures/error/profile-required.json'],
  ['unauthorized', 'ErrorResponse', 'http/fixtures/error/unauthorized.json'],
  ['forbidden', 'ErrorResponse', 'http/fixtures/error/forbidden.json'],
  ['invalid-request', 'ErrorResponse', 'http/fixtures/error/invalid-request.json'],
  ['service-unavailable', 'ErrorResponse', 'http/fixtures/error/service-unavailable.json'],
  ['profile-state-required', 'ProfileStateResponse', 'http/fixtures/success/profile-state-required.json'],
  ['profile-state-ready', 'ProfileStateResponse', 'http/fixtures/success/profile-state-ready.json'],
  ['profile-ready', 'ProfileReadyResponse', 'http/fixtures/success/profile-ready.json'],
  ['ledger-group-view', 'GroupResponse', 'http/fixtures/ledger/group-view.json', 'http/ledger/openapi.yaml'],
  ['ledger-group-list', 'GroupListResponse', 'http/fixtures/ledger/group-list.json', 'http/ledger/openapi.yaml'],
  ['ledger-create-group', 'CreateGroupRequest', 'http/fixtures/ledger/create-group-request.json', 'http/ledger/openapi.yaml'],
  ['ledger-membership', 'MembershipRequest', 'http/fixtures/ledger/membership-request.json', 'http/ledger/openapi.yaml'],
  ['ledger-role', 'RoleRequest', 'http/fixtures/ledger/role-request.json', 'http/ledger/openapi.yaml'],
  ['ledger-conflict', 'ErrorResponse', 'http/fixtures/ledger/error-conflict.json', 'http/ledger/openapi.yaml'],
  ['ledger-stale-version', 'ErrorResponse', 'http/fixtures/ledger/error-stale-version.json', 'http/ledger/openapi.yaml'],
  ['ledger-last-admin', 'ErrorResponse', 'http/fixtures/ledger/error-last-admin.json', 'http/ledger/openapi.yaml'],
  ['ledger-expense', 'ExpenseResponse', 'http/fixtures/ledger/expense-view.json', 'http/ledger/openapi.yaml'],
  ['ledger-create-expense', 'CreateExpenseRequest', 'http/fixtures/ledger/create-expense-request.json', 'http/ledger/openapi.yaml'],
  ['ledger-create-expense-exact', 'CreateExpenseRequest', 'http/fixtures/ledger/create-expense-exact.json', 'http/ledger/openapi.yaml'],
  ['ledger-create-expense-percentage', 'CreateExpenseRequest', 'http/fixtures/ledger/create-expense-percentage.json', 'http/ledger/openapi.yaml'],
  ['ledger-idempotency-conflict', 'ErrorResponse', 'http/fixtures/ledger/error-idempotency-conflict.json', 'http/ledger/openapi.yaml'],
  ['notification-device', 'DeviceResponse', 'http/fixtures/notification/device.json', 'http/notification/openapi.yaml'],
  ['notification-devices', 'DeviceListResponse', 'http/fixtures/notification/devices.json', 'http/notification/openapi.yaml'],
  ['ledger-sync-snapshot-page', 'SyncSnapshotPageResponse', 'http/fixtures/ledger/sync-snapshot-page.json', 'http/ledger/openapi.yaml']
];

export async function validateContracts(contractsRoot, overrides = {}) {
  const issues = [];
  for (const [label, schemaName, relative, contract] of httpFixtures) {
    const fixture = await readJson(path.join(contractsRoot, relative));
    const result = await validateHttpFixture(contractsRoot, schemaName, fixture, contract);
    issues.push(...result.issues.map((issue) => `HTTP fixture ${label}: ${issue}`));
    issues.push(...findSensitiveValues(fixture).map((issue) => `HTTP fixture ${label}: ${issue}`));
    issues.push(...findProtectedHttpValues(fixture).map((issue) => `HTTP fixture ${label}: ${issue}`));
  }

  const validEvent = overrides.mappingEventFixture ?? await readJson(path.join(
    contractsRoot, 'events/fixtures/dutch/member-profile-mapping-changed.json'));
  const validResult = await validateEventFixture(
    contractsRoot, 'dutch.member-profile.mapping-changed.v1', validEvent);
  issues.push(...validResult.issues.map((issue) => `mapping event valid fixture: ${issue}`));

  const forbiddenEvent = await readJson(path.join(
    contractsRoot, 'events/fixtures/dutch/forbidden-identity.json'));
  const forbiddenResult = await validateEventFixture(
    contractsRoot, 'dutch.member-profile.mapping-changed.v1', forbiddenEvent);
  if (forbiddenResult.valid) {
    issues.push('mapping event forbidden fixture must be rejected');
  }

  return { valid: issues.length === 0, issues };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const contractsRoot = path.dirname(fileURLToPath(import.meta.url));
  const result = await validateContracts(contractsRoot);
  if (!result.valid) {
    console.error(result.issues.join('\n'));
    process.exitCode = 1;
  }
}
