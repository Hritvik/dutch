import type { CreateExpenseRequest } from './expenses-client';
import { ExpenseContractError, ExpenseHttpError } from './expenses-client';
import { parseActivityEntry, type ActivityEntry } from './activity-client';

export type DirectEntry = {
  expenseId: string;
  revision: number;
  debtor: string;
  creditor: string;
  minorUnits: number;
  currency: string;
  algorithmVersion: string;
};

export type DirectExpenseView = {
  id: string;
  description: string;
  totalMinorUnits: number;
  currency: string;
  splitMethod: string;
  payers: { memberId: string; minorUnits: number }[];
  shares: { memberId: string; minorUnits: number }[];
  entries: DirectEntry[];
  streamIds: string[];
  occurredAt: string;
  revision: number;
};

export type ApiFetch = (input: string, init?: RequestInit) => Promise<Response>;

const EXPENSE_ID = /^exp_[A-Za-z0-9_-]{16,128}$/;
const MEMBER_ID = /^mem_[A-Za-z0-9_-]{16,128}$/;
const CURRENCY = /^[A-Z]{3}$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseMemberAmount(value: unknown): { memberId: string; minorUnits: number } {
  if (!isRecord(value)
      || typeof value.memberId !== 'string' || !MEMBER_ID.test(value.memberId)
      || typeof value.minorUnits !== 'number' || !Number.isInteger(value.minorUnits)
      || value.minorUnits < 0) {
    throw new ExpenseContractError('A direct member amount is invalid.');
  }
  return { memberId: value.memberId, minorUnits: value.minorUnits };
}

function parseEntry(value: unknown): DirectEntry {
  if (!isRecord(value)
      || typeof value.expenseId !== 'string' || !EXPENSE_ID.test(value.expenseId)
      || typeof value.revision !== 'number' || !Number.isInteger(value.revision)
      || typeof value.debtor !== 'string' || !MEMBER_ID.test(value.debtor)
      || typeof value.creditor !== 'string' || !MEMBER_ID.test(value.creditor)
      || typeof value.minorUnits !== 'number' || !Number.isInteger(value.minorUnits)
      || value.minorUnits <= 0
      || typeof value.currency !== 'string' || !CURRENCY.test(value.currency)
      || typeof value.algorithmVersion !== 'string') {
    throw new ExpenseContractError('A direct obligation entry is invalid.');
  }
  return {
    expenseId: value.expenseId,
    revision: value.revision,
    debtor: value.debtor,
    creditor: value.creditor,
    minorUnits: value.minorUnits,
    currency: value.currency,
    algorithmVersion: value.algorithmVersion
  };
}

export function parseDirectExpenseView(payload: unknown): DirectExpenseView {
  if (!isRecord(payload)
      || typeof payload.id !== 'string' || !EXPENSE_ID.test(payload.id)
      || typeof payload.description !== 'string' || payload.description.length === 0
      || typeof payload.totalMinorUnits !== 'number' || !Number.isInteger(payload.totalMinorUnits)
      || payload.totalMinorUnits <= 0
      || typeof payload.currency !== 'string' || !CURRENCY.test(payload.currency)
      || typeof payload.splitMethod !== 'string'
      || !Array.isArray(payload.payers)
      || !Array.isArray(payload.shares)
      || !Array.isArray(payload.entries)
      || !Array.isArray(payload.streamIds)
      || typeof payload.occurredAt !== 'string'
      || typeof payload.revision !== 'number' || !Number.isInteger(payload.revision)) {
    throw new ExpenseContractError('Direct Expense shape is invalid.');
  }
  const shares = payload.shares.map(parseMemberAmount);
  const shared = shares.reduce((total, share) => total + share.minorUnits, 0);
  if (shared !== payload.totalMinorUnits) {
    throw new ExpenseContractError('Direct Expense shares must sum to the total.');
  }
  if (payload.streamIds.some((stream) => typeof stream !== 'string'
      || !/^direct:mem_[A-Za-z0-9_-]{16,128}:mem_[A-Za-z0-9_-]{16,128}:[A-Z]{3}$/.test(stream))) {
    throw new ExpenseContractError('A direct stream id is invalid.');
  }
  return {
    id: payload.id,
    description: payload.description,
    totalMinorUnits: payload.totalMinorUnits,
    currency: payload.currency,
    splitMethod: payload.splitMethod,
    payers: payload.payers.map(parseMemberAmount),
    shares,
    entries: payload.entries.map(parseEntry),
    streamIds: payload.streamIds as string[],
    occurredAt: payload.occurredAt,
    revision: payload.revision
  };
}

function apiUrl(baseUrl: string, path: string): string {
  return `${baseUrl.replace(/\/+$/, '')}${path}`;
}

async function toError(response: Response): Promise<ExpenseHttpError> {
  let code = 'error';
  try {
    const payload = await response.json();
    if (isRecord(payload) && isRecord(payload.error) && typeof payload.error.code === 'string') {
      code = payload.error.code;
    }
  } catch {
    // Keep the generic code when the body is not JSON.
  }
  return new ExpenseHttpError(response.status, `Direct request failed with ${response.status}.`, code);
}

export async function createDirectExpense(
  baseUrl: string,
  accessToken: string,
  idempotencyKey: string,
  request: CreateExpenseRequest,
  fetchImpl: ApiFetch = fetch
): Promise<DirectExpenseView> {
  const response = await fetchImpl(apiUrl(baseUrl, '/api/v1/direct-expenses'), {
    method: 'POST',
    headers: {
      accept: 'application/json',
      authorization: `Bearer ${accessToken}`,
      'content-type': 'application/json',
      'idempotency-key': idempotencyKey
    },
    body: JSON.stringify(request)
  });
  if (!response.ok) {
    throw await toError(response);
  }
  const payload = await response.json();
  if (!isRecord(payload)) {
    throw new ExpenseContractError('Direct Expense response is missing data.');
  }
  return parseDirectExpenseView(payload.data);
}

export async function fetchDirectExpense(
  baseUrl: string,
  accessToken: string,
  expenseId: string,
  fetchImpl: ApiFetch = fetch
): Promise<DirectExpenseView> {
  const response = await fetchImpl(apiUrl(baseUrl, `/api/v1/direct-expenses/${expenseId}`), {
    method: 'GET',
    headers: { accept: 'application/json', authorization: `Bearer ${accessToken}` }
  });
  if (!response.ok) {
    throw await toError(response);
  }
  const payload = await response.json();
  if (!isRecord(payload)) {
    throw new ExpenseContractError('Direct Expense response is missing data.');
  }
  return parseDirectExpenseView(payload.data);
}

export async function fetchDirectActivity(
  baseUrl: string,
  accessToken: string,
  expenseId: string,
  fetchImpl: ApiFetch = fetch
): Promise<ActivityEntry[]> {
  const response = await fetchImpl(
    apiUrl(baseUrl, `/api/v1/direct-expenses/${expenseId}/activity`),
    { method: 'GET', headers: { accept: 'application/json', authorization: `Bearer ${accessToken}` } }
  );
  if (!response.ok) {
    throw await toError(response);
  }
  const payload = await response.json();
  if (!isRecord(payload) || !Array.isArray(payload.data)) {
    throw new ExpenseContractError('Direct Activity response is missing data.');
  }
  return payload.data.map(parseActivityEntry);
}
