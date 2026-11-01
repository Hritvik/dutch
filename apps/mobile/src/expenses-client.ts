export type SplitMethod = 'EQUAL' | 'EXACT' | 'PERCENTAGE';

export type PayerContribution = {
  memberId: string;
  minorUnits: number;
};

export type ParticipantShare = PayerContribution;

export type ObligationEntry = {
  expenseId: string;
  revision: number;
  debtor: string;
  creditor: string;
  minorUnits: number;
  currency: string;
  algorithmVersion: string;
};

export type ExpenseView = {
  id: string;
  groupId: string;
  description: string;
  totalMinorUnits: number;
  currency: string;
  splitMethod: SplitMethod;
  payers: PayerContribution[];
  shares: ParticipantShare[];
  entries: ObligationEntry[];
  occurredAt: string;
  revision: number;
};

export type CreateExpenseRequest = {
  description: string;
  totalMinorUnits: number;
  currency: string;
  splitMethod: SplitMethod;
  payers: PayerContribution[];
  participants: string[];
  occurredAt: string;
};

export class ExpenseContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ExpenseContractError';
  }
}

export class ExpenseHttpError extends Error {
  constructor(readonly status: number, message: string, readonly code: string = 'error') {
    super(message);
    this.name = 'ExpenseHttpError';
  }
}

export type ApiFetch = (input: string, init?: RequestInit) => Promise<Response>;

const GROUP_ID = /^grp_[A-Za-z0-9_-]{16,128}$/;
const EXPENSE_ID = /^exp_[A-Za-z0-9_-]{16,128}$/;
const MEMBER_ID = /^mem_[A-Za-z0-9_-]{16,128}$/;
const CURRENCY = /^[A-Z]{3}$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseAmount(value: unknown): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value <= 0) {
    throw new ExpenseContractError('An amount must be a positive integer.');
  }
  return value;
}

function parseMemberAmount(value: unknown): PayerContribution {
  if (!isRecord(value)
      || typeof value.memberId !== 'string' || !MEMBER_ID.test(value.memberId)
      || typeof value.minorUnits !== 'number' || !Number.isInteger(value.minorUnits)
      || value.minorUnits <= 0) {
    throw new ExpenseContractError('A member amount is invalid.');
  }
  return { memberId: value.memberId, minorUnits: value.minorUnits };
}

function parseEntry(value: unknown): ObligationEntry {
  if (!isRecord(value)
      || typeof value.expenseId !== 'string' || !EXPENSE_ID.test(value.expenseId)
      || typeof value.revision !== 'number' || !Number.isInteger(value.revision)
      || typeof value.debtor !== 'string' || !MEMBER_ID.test(value.debtor)
      || typeof value.creditor !== 'string' || !MEMBER_ID.test(value.creditor)
      || typeof value.minorUnits !== 'number' || !Number.isInteger(value.minorUnits)
      || value.minorUnits <= 0
      || typeof value.currency !== 'string' || !CURRENCY.test(value.currency)
      || typeof value.algorithmVersion !== 'string') {
    throw new ExpenseContractError('An obligation entry is invalid.');
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

export function parseExpenseView(payload: unknown): ExpenseView {
  if (!isRecord(payload)
      || typeof payload.id !== 'string' || !EXPENSE_ID.test(payload.id)
      || typeof payload.groupId !== 'string' || !GROUP_ID.test(payload.groupId)
      || typeof payload.description !== 'string' || payload.description.length === 0
      || typeof payload.totalMinorUnits !== 'number' || !Number.isInteger(payload.totalMinorUnits)
      || payload.totalMinorUnits <= 0
      || typeof payload.currency !== 'string' || !CURRENCY.test(payload.currency)
      || !['EQUAL', 'EXACT', 'PERCENTAGE'].includes(String(payload.splitMethod))
      || !Array.isArray(payload.payers)
      || !Array.isArray(payload.shares)
      || !Array.isArray(payload.entries)
      || typeof payload.occurredAt !== 'string'
      || typeof payload.revision !== 'number' || !Number.isInteger(payload.revision)) {
    throw new ExpenseContractError('Expense shape is invalid.');
  }
  const shares = payload.shares.map(parseMemberAmount);
  const sharedTotal = shares.reduce((total, share) => total + share.minorUnits, 0);
  if (sharedTotal !== payload.totalMinorUnits) {
    throw new ExpenseContractError('Expense shares must sum to the total.');
  }
  return {
    id: payload.id,
    groupId: payload.groupId,
    description: payload.description,
    totalMinorUnits: payload.totalMinorUnits,
    currency: payload.currency,
    splitMethod: payload.splitMethod as SplitMethod,
    payers: payload.payers.map(parseMemberAmount),
    shares,
    entries: payload.entries.map(parseEntry),
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
  return new ExpenseHttpError(response.status, `Expense request failed with ${response.status}.`, code);
}

export async function createExpense(
  baseUrl: string,
  accessToken: string,
  groupId: string,
  idempotencyKey: string,
  request: CreateExpenseRequest,
  fetchImpl: ApiFetch = fetch
): Promise<ExpenseView> {
  const response = await fetchImpl(apiUrl(baseUrl, `/api/v1/groups/${groupId}/expenses`), {
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
    throw new ExpenseContractError('Expense response is missing data.');
  }
  return parseExpenseView(payload.data);
}

export function parseMajorToMinor(value: string): number {
  const trimmed = value.trim();
  if (!/^\d{1,12}(\.\d{1,2})?$/.test(trimmed)) {
    throw new ExpenseContractError('Enter an amount with at most two decimal places.');
  }
  const [whole, fraction = ''] = trimmed.split('.');
  const minor = Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
  return parseAmount(minor);
}

export function formatMinor(minorUnits: number, currency: string): string {
  return `${(minorUnits / 100).toFixed(2)} ${currency}`;
}
