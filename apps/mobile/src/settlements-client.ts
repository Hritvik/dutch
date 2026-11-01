export type SettlementStatus = 'ACTIVE' | 'VOIDED';

export type SettlementAllocation = {
  scopeId: string;
  currency: string;
  minorUnits: number;
  payer: string;
  payee: string;
};

export type SettlementView = {
  id: string;
  groupId: string;
  payer: string;
  payee: string;
  totalMinorUnits: number;
  currency: string;
  status: SettlementStatus;
  allocations: SettlementAllocation[];
  occurredAt: string;
};

export type CreateSettlementRequest = {
  payerMemberId: string;
  payeeMemberId: string;
  totalMinorUnits: number;
  currency: string;
  occurredAt: string;
  allocations?: { scopeId: string; minorUnits: number }[];
};

export class SettlementContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SettlementContractError';
  }
}

export class SettlementHttpError extends Error {
  constructor(readonly status: number, message: string, readonly code: string = 'error') {
    super(message);
    this.name = 'SettlementHttpError';
  }
}

export type ApiFetch = (input: string, init?: RequestInit) => Promise<Response>;

const SETTLEMENT_ID = /^stl_[A-Za-z0-9_-]{16,128}$/;
const GROUP_ID = /^grp_[A-Za-z0-9_-]{16,128}$/;
const MEMBER_ID = /^mem_[A-Za-z0-9_-]{16,128}$/;
const CURRENCY = /^[A-Z]{3}$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseAllocation(value: unknown): SettlementAllocation {
  if (!isRecord(value)
      || typeof value.scopeId !== 'string' || value.scopeId.length === 0
      || typeof value.currency !== 'string' || !CURRENCY.test(value.currency)
      || typeof value.minorUnits !== 'number' || !Number.isInteger(value.minorUnits)
      || value.minorUnits <= 0
      || typeof value.payer !== 'string' || !MEMBER_ID.test(value.payer)
      || typeof value.payee !== 'string' || !MEMBER_ID.test(value.payee)) {
    throw new SettlementContractError('Settlement allocation shape is invalid.');
  }
  return {
    scopeId: value.scopeId,
    currency: value.currency,
    minorUnits: value.minorUnits,
    payer: value.payer,
    payee: value.payee
  };
}

export function parseSettlementView(payload: unknown): SettlementView {
  if (!isRecord(payload)
      || typeof payload.id !== 'string' || !SETTLEMENT_ID.test(payload.id)
      || typeof payload.groupId !== 'string' || !GROUP_ID.test(payload.groupId)
      || typeof payload.payer !== 'string' || !MEMBER_ID.test(payload.payer)
      || typeof payload.payee !== 'string' || !MEMBER_ID.test(payload.payee)
      || typeof payload.totalMinorUnits !== 'number' || !Number.isInteger(payload.totalMinorUnits)
      || payload.totalMinorUnits <= 0
      || typeof payload.currency !== 'string' || !CURRENCY.test(payload.currency)
      || (payload.status !== 'ACTIVE' && payload.status !== 'VOIDED')
      || !Array.isArray(payload.allocations)
      || typeof payload.occurredAt !== 'string') {
    throw new SettlementContractError('Settlement shape is invalid.');
  }
  const allocations = payload.allocations.map(parseAllocation);
  const allocated = allocations.reduce((total, allocation) => total + allocation.minorUnits, 0);
  if (allocations.length === 0 || allocated !== payload.totalMinorUnits) {
    throw new SettlementContractError('Settlement allocations must sum to the total.');
  }
  return {
    id: payload.id,
    groupId: payload.groupId,
    payer: payload.payer,
    payee: payload.payee,
    totalMinorUnits: payload.totalMinorUnits,
    currency: payload.currency,
    status: payload.status,
    allocations,
    occurredAt: payload.occurredAt
  };
}

function apiUrl(baseUrl: string, path: string): string {
  return `${baseUrl.replace(/\/+$/, '')}${path}`;
}

async function toError(response: Response): Promise<SettlementHttpError> {
  let code = 'error';
  try {
    const payload = await response.json();
    if (isRecord(payload) && isRecord(payload.error) && typeof payload.error.code === 'string') {
      code = payload.error.code;
    }
  } catch {
    // Keep the generic code when the body is not JSON.
  }
  return new SettlementHttpError(
    response.status, `Settlement request failed with ${response.status}.`, code);
}

async function readSettlement(response: Response): Promise<SettlementView> {
  if (!response.ok) {
    throw await toError(response);
  }
  const payload = await response.json();
  if (!isRecord(payload)) {
    throw new SettlementContractError('Settlement response is missing data.');
  }
  return parseSettlementView(payload.data);
}

export async function recordSettlement(
  baseUrl: string,
  accessToken: string,
  groupId: string,
  idempotencyKey: string,
  request: CreateSettlementRequest,
  fetchImpl: ApiFetch = fetch
): Promise<SettlementView> {
  const response = await fetchImpl(apiUrl(baseUrl, `/api/v1/groups/${groupId}/settlements`), {
    method: 'POST',
    headers: {
      accept: 'application/json',
      authorization: `Bearer ${accessToken}`,
      'content-type': 'application/json',
      'idempotency-key': idempotencyKey
    },
    body: JSON.stringify(request)
  });
  return readSettlement(response);
}

export async function voidSettlement(
  baseUrl: string,
  accessToken: string,
  groupId: string,
  settlementId: string,
  idempotencyKey: string,
  fetchImpl: ApiFetch = fetch
): Promise<SettlementView> {
  const response = await fetchImpl(
    apiUrl(baseUrl, `/api/v1/groups/${groupId}/settlements/${settlementId}/void`),
    {
      method: 'POST',
      headers: {
        accept: 'application/json',
        authorization: `Bearer ${accessToken}`,
        'idempotency-key': idempotencyKey
      }
    }
  );
  return readSettlement(response);
}
