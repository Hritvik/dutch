export type ActivityAction =
  | 'EXPENSE_CREATED'
  | 'EXPENSE_CORRECTED'
  | 'EXPENSE_DELETED'
  | 'EXPENSE_RESTORED'
  | 'SETTLEMENT_RECORDED'
  | 'SETTLEMENT_VOIDED'
  | 'MEMBERSHIP_CHANGED';

export type ActivityEntry = {
  activityId: string;
  scopeId: string;
  actor?: string;
  action: ActivityAction;
  targetId: string;
  targetRevision: number;
  occurredAt: string;
  details: Record<string, string>;
};

export class ActivityContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ActivityContractError';
  }
}

export class ActivityHttpError extends Error {
  constructor(readonly status: number, message: string, readonly code: string = 'error') {
    super(message);
    this.name = 'ActivityHttpError';
  }
}

export type ApiFetch = (input: string, init?: RequestInit) => Promise<Response>;

const ACTIVITY_ID = /^act_[A-Za-z0-9_-]{16,128}$/;
const MEMBER_ID = /^mem_[A-Za-z0-9_-]{16,128}$/;
const ACTIONS: ActivityAction[] = [
  'EXPENSE_CREATED', 'EXPENSE_CORRECTED', 'EXPENSE_DELETED', 'EXPENSE_RESTORED',
  'SETTLEMENT_RECORDED', 'SETTLEMENT_VOIDED', 'MEMBERSHIP_CHANGED'
];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function parseActivityEntry(value: unknown): ActivityEntry {
  if (!isRecord(value)
      || typeof value.activityId !== 'string' || !ACTIVITY_ID.test(value.activityId)
      || typeof value.scopeId !== 'string' || value.scopeId.length === 0
      || (value.actor !== undefined && value.actor !== null
        && (typeof value.actor !== 'string' || !MEMBER_ID.test(value.actor)))
      || !ACTIONS.includes(value.action as ActivityAction)
      || typeof value.targetId !== 'string' || value.targetId.length === 0
      || typeof value.targetRevision !== 'number' || !Number.isInteger(value.targetRevision)
      || value.targetRevision < 0
      || typeof value.occurredAt !== 'string'
      || !isRecord(value.details)) {
    throw new ActivityContractError('Activity entry shape is invalid.');
  }
  const details: Record<string, string> = {};
  for (const [key, detail] of Object.entries(value.details)) {
    if (typeof detail !== 'string') {
      throw new ActivityContractError('Activity details must be strings.');
    }
    details[key] = detail;
  }
  return {
    activityId: value.activityId,
    scopeId: value.scopeId,
    ...(value.actor === undefined || value.actor === null
      ? {}
      : { actor: value.actor as string }),
    action: value.action as ActivityAction,
    targetId: value.targetId,
    targetRevision: value.targetRevision,
    occurredAt: value.occurredAt,
    details
  };
}

export async function fetchActivity(
  baseUrl: string,
  accessToken: string,
  groupId: string,
  fetchImpl: ApiFetch = fetch
): Promise<ActivityEntry[]> {
  const response = await fetchImpl(
    `${baseUrl.replace(/\/+$/, '')}/api/v1/groups/${groupId}/activity`,
    { method: 'GET', headers: { accept: 'application/json', authorization: `Bearer ${accessToken}` } }
  );
  if (!response.ok) {
    let code = 'error';
    try {
      const payload = await response.json();
      if (isRecord(payload) && isRecord(payload.error) && typeof payload.error.code === 'string') {
        code = payload.error.code;
      }
    } catch {
      // Keep the generic code when the body is not JSON.
    }
    throw new ActivityHttpError(
      response.status, `Activity request failed with ${response.status}.`, code);
  }
  const payload = await response.json();
  if (!isRecord(payload) || !Array.isArray(payload.data)) {
    throw new ActivityContractError('Activity response is missing data.');
  }
  return payload.data.map(parseActivityEntry);
}
