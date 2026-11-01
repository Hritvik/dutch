import type { ActivityEntry } from './activity-client';
import { parseActivityEntry } from './activity-client';

export type SeriesScope = { type: 'GROUP' | 'DIRECT'; groupId?: string; memberIds?: string[] };
export type SeriesParticipant = { memberId: string; basisPoints?: number | null; minorUnits?: number | null };
export type SeriesView = {
  id: string;
  scope: SeriesScope;
  description: string;
  totalMinorUnits: number;
  currency: string;
  splitMethod: string;
  payers: { memberId: string; minorUnits: number }[];
  participants: SeriesParticipant[];
  cadence: 'WEEKLY' | 'MONTHLY';
  dueDay: number;
  timezone: string;
  status: 'ACTIVE' | 'PAUSED' | 'ARCHIVED';
  nextPeriodStart: string;
  version: number;
};
export type OccurrenceView = {
  id: string;
  seriesId: string;
  templateVersion: number;
  periodStart: string;
  periodEnd: string;
  dueAt: string;
  status: 'PENDING' | 'SUBMITTED' | 'SKIPPED' | 'MISSED';
  expenseId?: string | null;
};
export type SeriesSubmitResult = { occurrence: OccurrenceView; expenseId: string };
export type CreateSeriesRequest = {
  scope: SeriesScope;
  description: string;
  totalMinorUnits: number;
  currency: string;
  splitMethod: string;
  payers: { memberId: string; minorUnits: number }[];
  participants: SeriesParticipant[];
  cadence: 'WEEKLY' | 'MONTHLY';
  dueDay: number;
  timezone: string;
  nextPeriodStart: string;
};

export class SeriesContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SeriesContractError';
  }
}

export class SeriesHttpError extends Error {
  constructor(readonly status: number, message: string, readonly code: string = 'error') {
    super(message);
    this.name = 'SeriesHttpError';
  }
}

export type ApiFetch = (input: string, init?: RequestInit) => Promise<Response>;

const SERIES_ID = /^ser_[A-Za-z0-9_-]{16,128}$/;
const OCCURRENCE_ID = /^occ_[A-Za-z0-9_-]{16,128}$/;
const MEMBER_ID = /^mem_[A-Za-z0-9_-]{16,128}$/;
const EXPENSE_ID = /^exp_[A-Za-z0-9_-]{16,128}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseScope(value: unknown): SeriesScope {
  if (!isRecord(value) || (value.type !== 'GROUP' && value.type !== 'DIRECT')) {
    throw new SeriesContractError('Series scope is invalid.');
  }
  return {
    type: value.type,
    ...(typeof value.groupId === 'string' ? { groupId: value.groupId } : {}),
    ...(Array.isArray(value.memberIds)
      ? { memberIds: value.memberIds.map(String) }
      : {})
  };
}

function parseParticipant(value: unknown): SeriesParticipant {
  if (!isRecord(value) || typeof value.memberId !== 'string' || !MEMBER_ID.test(value.memberId)) {
    throw new SeriesContractError('Series participant is invalid.');
  }
  return {
    memberId: value.memberId,
    ...(value.basisPoints === null || value.basisPoints === undefined
      ? {} : { basisPoints: Number(value.basisPoints) }),
    ...(value.minorUnits === null || value.minorUnits === undefined
      ? {} : { minorUnits: Number(value.minorUnits) })
  };
}

export function parseSeriesView(payload: unknown): SeriesView {
  if (!isRecord(payload)
      || typeof payload.id !== 'string' || !SERIES_ID.test(payload.id)
      || typeof payload.description !== 'string'
      || typeof payload.totalMinorUnits !== 'number' || !Number.isInteger(payload.totalMinorUnits)
      || typeof payload.currency !== 'string'
      || typeof payload.splitMethod !== 'string'
      || !Array.isArray(payload.payers) || !Array.isArray(payload.participants)
      || (payload.cadence !== 'WEEKLY' && payload.cadence !== 'MONTHLY')
      || typeof payload.dueDay !== 'number'
      || typeof payload.timezone !== 'string'
      || (payload.status !== 'ACTIVE' && payload.status !== 'PAUSED'
        && payload.status !== 'ARCHIVED')
      || typeof payload.nextPeriodStart !== 'string' || !DATE.test(payload.nextPeriodStart)
      || typeof payload.version !== 'number' || !Number.isInteger(payload.version)) {
    throw new SeriesContractError('Series shape is invalid.');
  }
  return {
    id: payload.id,
    scope: parseScope(payload.scope),
    description: payload.description,
    totalMinorUnits: payload.totalMinorUnits,
    currency: payload.currency,
    splitMethod: payload.splitMethod,
    payers: payload.payers.map((payer) => {
      if (!isRecord(payer) || typeof payer.memberId !== 'string'
          || typeof payer.minorUnits !== 'number') {
        throw new SeriesContractError('Series payer is invalid.');
      }
      return { memberId: payer.memberId, minorUnits: payer.minorUnits };
    }),
    participants: payload.participants.map(parseParticipant),
    cadence: payload.cadence,
    dueDay: payload.dueDay,
    timezone: payload.timezone,
    status: payload.status,
    nextPeriodStart: payload.nextPeriodStart,
    version: payload.version
  };
}

export function parseOccurrenceView(payload: unknown): OccurrenceView {
  if (!isRecord(payload)
      || typeof payload.id !== 'string' || !OCCURRENCE_ID.test(payload.id)
      || typeof payload.seriesId !== 'string' || !SERIES_ID.test(payload.seriesId)
      || typeof payload.templateVersion !== 'number'
      || typeof payload.periodStart !== 'string'
      || typeof payload.periodEnd !== 'string'
      || typeof payload.dueAt !== 'string'
      || !['PENDING', 'SUBMITTED', 'SKIPPED', 'MISSED'].includes(String(payload.status))) {
    throw new SeriesContractError('Occurrence shape is invalid.');
  }
  return {
    id: payload.id,
    seriesId: payload.seriesId,
    templateVersion: payload.templateVersion,
    periodStart: payload.periodStart,
    periodEnd: payload.periodEnd,
    dueAt: payload.dueAt,
    status: payload.status as OccurrenceView['status'],
    ...(typeof payload.expenseId === 'string' && EXPENSE_ID.test(payload.expenseId)
      ? { expenseId: payload.expenseId }
      : { expenseId: null })
  };
}

function apiUrl(baseUrl: string, path: string): string {
  return `${baseUrl.replace(/\/+$/, '')}${path}`;
}

function isRecordPayload(value: unknown): value is Record<string, unknown> {
  return isRecord(value) && isRecord(value.data);
}

async function toError(response: Response): Promise<SeriesHttpError> {
  let code = 'error';
  try {
    const payload = await response.json();
    if (isRecord(payload) && isRecord(payload.error) && typeof payload.error.code === 'string') {
      code = payload.error.code;
    }
  } catch {
    // Keep the generic code when the body is not JSON.
  }
  return new SeriesHttpError(response.status, `Series request failed with ${response.status}.`, code);
}

async function request<T>(
  baseUrl: string,
  accessToken: string,
  method: 'GET' | 'POST',
  path: string,
  parse: (value: unknown) => T,
  body?: unknown,
  headers: Record<string, string> = {},
  fetchImpl: ApiFetch = fetch
): Promise<T> {
  const response = await fetchImpl(apiUrl(baseUrl, path), {
    method,
    headers: {
      accept: 'application/json',
      authorization: `Bearer ${accessToken}`,
      ...(body === undefined ? {} : { 'content-type': 'application/json' }),
      ...headers
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  if (!response.ok) {
    throw await toError(response);
  }
  const payload = await response.json();
  if (!isRecordPayload(payload)) {
    throw new SeriesContractError('Series response is missing data.');
  }
  return parse(payload.data);
}

export async function createSeries(baseUrl: string, accessToken: string, key: string,
                                    input: CreateSeriesRequest, fetchImpl: ApiFetch = fetch) {
  return request(baseUrl, accessToken, 'POST', '/api/v1/series', parseSeriesView, input,
    { 'idempotency-key': key }, fetchImpl);
}

export async function listGroupSeries(baseUrl: string, accessToken: string, groupId: string,
                                      fetchImpl: ApiFetch = fetch): Promise<SeriesView[]> {
  return request(baseUrl, accessToken, 'GET', `/api/v1/groups/${groupId}/series`,
    (data) => (Array.isArray(data) ? data.map(parseSeriesView) : []), undefined, {}, fetchImpl);
}

export async function listDirectSeries(baseUrl: string, accessToken: string,
                                       fetchImpl: ApiFetch = fetch): Promise<SeriesView[]> {
  return request(baseUrl, accessToken, 'GET', '/api/v1/series',
    (data) => (Array.isArray(data) ? data.map(parseSeriesView) : []), undefined, {}, fetchImpl);
}

export async function fetchSeries(baseUrl: string, accessToken: string, seriesId: string,
                                  fetchImpl: ApiFetch = fetch): Promise<SeriesView> {
  return request(baseUrl, accessToken, 'GET', `/api/v1/series/${seriesId}`, parseSeriesView,
    undefined, {}, fetchImpl);
}

export async function fetchOccurrences(baseUrl: string, accessToken: string, seriesId: string,
                                       fetchImpl: ApiFetch = fetch): Promise<OccurrenceView[]> {
  return request(baseUrl, accessToken, 'GET', `/api/v1/series/${seriesId}/occurrences`,
    (data) => (Array.isArray(data) ? data.map(parseOccurrenceView) : []), undefined, {}, fetchImpl);
}

export async function generateOccurrence(baseUrl: string, accessToken: string, seriesId: string,
                                         key: string, expectedVersion: number,
                                         fetchImpl: ApiFetch = fetch): Promise<OccurrenceView> {
  return request(baseUrl, accessToken, 'POST', `/api/v1/series/${seriesId}/occurrences`,
    parseOccurrenceView, undefined,
    { 'idempotency-key': key, 'if-match': `"${expectedVersion}"` }, fetchImpl);
}

export async function skipOccurrence(baseUrl: string, accessToken: string, occurrenceId: string,
                                     key: string, fetchImpl: ApiFetch = fetch): Promise<OccurrenceView> {
  return request(baseUrl, accessToken, 'POST',
    `/api/v1/series/occurrences/${occurrenceId}/skip`, parseOccurrenceView, undefined,
    { 'idempotency-key': key }, fetchImpl);
}

export async function submitOccurrence(baseUrl: string, accessToken: string,
                                       occurrenceId: string, key: string,
                                       fetchImpl: ApiFetch = fetch): Promise<SeriesSubmitResult> {
  return request(baseUrl, accessToken, 'POST',
    `/api/v1/series/occurrences/${occurrenceId}/submit`,
    (data) => {
      if (!isRecord(data) || typeof data.expenseId !== 'string'
          || !EXPENSE_ID.test(data.expenseId)) {
        throw new SeriesContractError('Series submit result is invalid.');
      }
      return { occurrence: parseOccurrenceView(data.occurrence), expenseId: data.expenseId };
    },
    undefined, { 'idempotency-key': key }, fetchImpl);
}

export async function changeSeriesStatus(baseUrl: string, accessToken: string, seriesId: string,
                                         key: string, action: 'PAUSE' | 'RESUME' | 'ARCHIVE',
                                         expectedVersion: number,
                                         fetchImpl: ApiFetch = fetch): Promise<SeriesView> {
  return request(baseUrl, accessToken, 'POST', `/api/v1/series/${seriesId}/status`,
    parseSeriesView, { action },
    { 'idempotency-key': key, 'if-match': `"${expectedVersion}"` }, fetchImpl);
}

export function parseSeriesActivity(value: unknown): ActivityEntry[] {
  if (!Array.isArray(value)) {
    throw new SeriesContractError('Series Activity must be an array.');
  }
  return value.map(parseActivityEntry);
}
