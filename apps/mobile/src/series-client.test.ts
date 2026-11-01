import assert from 'node:assert/strict';
import test from 'node:test';

import {
  SeriesContractError,
  createSeries,
  generateOccurrence,
  parseOccurrenceView,
  parseSeriesView,
  submitOccurrence
} from './series-client';

const GROUP = 'grp_000000000000000001';
const SERIES = 'ser_000000000000000001';
const OCCURRENCE = 'occ_000000000000000001';
const EXPENSE = 'exp_000000000000000001';
const A = 'mem_000000000000000001';
const B = 'mem_000000000000000002';

const series = {
  id: SERIES,
  scope: { type: 'GROUP', groupId: GROUP, memberIds: [] },
  description: 'Rent',
  totalMinorUnits: 100,
  currency: 'INR',
  splitMethod: 'EQUAL',
  payers: [{ memberId: A, minorUnits: 100 }],
  participants: [{ memberId: A }, { memberId: B }],
  cadence: 'MONTHLY',
  dueDay: 5,
  timezone: 'Asia/Kolkata',
  status: 'ACTIVE',
  nextPeriodStart: '2026-11-01',
  version: 1
};

const occurrence = {
  id: OCCURRENCE,
  seriesId: SERIES,
  templateVersion: 1,
  periodStart: '2026-11-01',
  periodEnd: '2026-11-30',
  dueAt: '2026-11-05T00:00:00Z',
  status: 'PENDING',
  expenseId: null
};

test('parses a Series and its occurrences', () => {
  assert.deepEqual(parseSeriesView(series), series);
  assert.deepEqual(parseOccurrenceView(occurrence), occurrence);
  assert.throws(() => parseSeriesView({ ...series, cadence: 'DAILY' }), SeriesContractError);
  assert.throws(() => parseOccurrenceView({ ...occurrence, status: 'DONE' }),
    SeriesContractError);
});

test('creates a Series and generates the next occurrence with If-Match', async () => {
  let createRequest: RequestInit | undefined;
  const created = await createSeries('https://api.example.test', 'token', 'key-1', {
    scope: { type: 'GROUP', groupId: GROUP },
    description: 'Rent',
    totalMinorUnits: 100,
    currency: 'INR',
    splitMethod: 'EQUAL',
    payers: [{ memberId: A, minorUnits: 100 }],
    participants: [{ memberId: A }, { memberId: B }],
    cadence: 'MONTHLY',
    dueDay: 5,
    timezone: 'Asia/Kolkata',
    nextPeriodStart: '2026-11-01'
  }, async (url, init) => {
    createRequest = init;
    assert.equal(url, 'https://api.example.test/api/v1/series');
    return new Response(JSON.stringify({ data: series }), { status: 200 });
  });
  assert.equal(created.id, SERIES);
  assert.equal(new Headers(createRequest?.headers).get('idempotency-key'), 'key-1');

  const generated = await generateOccurrence('https://api.example.test', 'token', SERIES,
    'generate-1', 1, async (url, init) => {
      assert.match(String(url), /series\/ser_.*\/occurrences$/);
      assert.equal(new Headers(init?.headers).get('if-match'), '"1"');
      return new Response(JSON.stringify({ data: occurrence }), { status: 200 });
    });
  assert.equal(generated.status, 'PENDING');
});

test('submits an occurrence and returns the created expense', async () => {
  const submitted = await submitOccurrence('https://api.example.test', 'token', OCCURRENCE,
    'submit-1', async (url, init) => {
      assert.match(String(url), /occurrences\/occ_.*\/submit$/);
      assert.equal(new Headers(init?.headers).get('idempotency-key'), 'submit-1');
      return new Response(JSON.stringify({
        data: {
          occurrence: { ...occurrence, status: 'SUBMITTED', expenseId: EXPENSE },
          expenseId: EXPENSE
        }
      }), { status: 200 });
    });
  assert.equal(submitted.occurrence.status, 'SUBMITTED');
  assert.equal(submitted.expenseId, EXPENSE);
});
