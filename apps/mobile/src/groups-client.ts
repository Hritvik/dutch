export type GroupRole = 'ADMIN' | 'MEMBER';
export type MembershipState = 'INVITED' | 'PLACEHOLDER' | 'ACTIVE' | 'INACTIVE';
export type GroupState = 'ACTIVE' | 'ARCHIVED';

export type MembershipView = {
  memberId: string;
  role: GroupRole;
  state: MembershipState;
};

export type GroupView = {
  id: string;
  name: string;
  currency: string;
  state: GroupState;
  version: number;
  memberships: MembershipView[];
};

export class GroupContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'GroupContractError';
  }
}

export type ApiFetch = (input: string, init?: RequestInit) => Promise<Response>;

const GROUP_ID = /^grp_[A-Za-z0-9_-]{16,128}$/;
const MEMBER_ID = /^mem_[A-Za-z0-9_-]{16,128}$/;
const CURRENCY = /^[A-Z]{3}$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseMembership(value: unknown): MembershipView {
  if (!isRecord(value)
      || typeof value.memberId !== 'string' || !MEMBER_ID.test(value.memberId)
      || (value.role !== 'ADMIN' && value.role !== 'MEMBER')
      || !['INVITED', 'PLACEHOLDER', 'ACTIVE', 'INACTIVE'].includes(String(value.state))) {
    throw new GroupContractError('Membership shape is invalid.');
  }
  return {
    memberId: value.memberId,
    role: value.role,
    state: value.state as MembershipState
  };
}

export function parseGroupView(value: unknown): GroupView {
  if (!isRecord(value)
      || typeof value.id !== 'string' || !GROUP_ID.test(value.id)
      || typeof value.name !== 'string' || value.name.length === 0 || value.name.length > 120
      || typeof value.currency !== 'string' || !CURRENCY.test(value.currency)
      || (value.state !== 'ACTIVE' && value.state !== 'ARCHIVED')
      || typeof value.version !== 'number' || !Number.isInteger(value.version) || value.version < 0
      || !Array.isArray(value.memberships)) {
    throw new GroupContractError('Group shape is invalid.');
  }
  return {
    id: value.id,
    name: value.name,
    currency: value.currency,
    state: value.state,
    version: value.version,
    memberships: value.memberships.map(parseMembership)
  };
}

export function parseGroupList(payload: unknown): GroupView[] {
  if (!isRecord(payload) || !Array.isArray(payload.data)) {
    throw new GroupContractError('Group list response is missing data.');
  }
  return payload.data.map(parseGroupView);
}

function apiUrl(baseUrl: string, path: string): string {
  return `${baseUrl.replace(/\/+$/, '')}${path}`;
}

export async function fetchGroups(
  baseUrl: string,
  accessToken: string,
  fetchImpl: ApiFetch = fetch
): Promise<GroupView[]> {
  const response = await fetchImpl(apiUrl(baseUrl, '/api/v1/groups'), {
    method: 'GET',
    headers: { accept: 'application/json', authorization: `Bearer ${accessToken}` }
  });
  if (!response.ok) {
    throw new GroupContractError(`Group list request failed with ${response.status}.`);
  }
  return parseGroupList(await response.json());
}

export async function createGroup(
  baseUrl: string,
  accessToken: string,
  input: { name: string; currency: string },
  fetchImpl: ApiFetch = fetch
): Promise<GroupView> {
  const response = await fetchImpl(apiUrl(baseUrl, '/api/v1/groups'), {
    method: 'POST',
    headers: {
      accept: 'application/json',
      authorization: `Bearer ${accessToken}`,
      'content-type': 'application/json'
    },
    body: JSON.stringify(input)
  });
  if (!response.ok) {
    throw new GroupContractError(`Group create request failed with ${response.status}.`);
  }
  const payload = await response.json();
  if (!isRecord(payload)) {
    throw new GroupContractError('Group response is missing data.');
  }
  return parseGroupView(payload.data);
}

export class GroupHttpError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = 'GroupHttpError';
  }
}

async function send(
  baseUrl: string,
  accessToken: string,
  method: 'GET' | 'POST' | 'PATCH' | 'DELETE',
  path: string,
  fetchImpl: ApiFetch,
  body?: unknown,
  expectedVersion?: number
): Promise<GroupView> {
  const headers: Record<string, string> = {
    accept: 'application/json',
    authorization: `Bearer ${accessToken}`
  };
  if (body !== undefined) {
    headers['content-type'] = 'application/json';
  }
  if (expectedVersion !== undefined) {
    headers['if-match'] = `"${expectedVersion}"`;
  }
  const response = await fetchImpl(apiUrl(baseUrl, path), {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  if (!response.ok) {
    throw new GroupHttpError(response.status, `Group request failed with ${response.status}.`);
  }
  const payload = await response.json();
  if (!isRecord(payload)) {
    throw new GroupContractError('Group response is missing data.');
  }
  return parseGroupView(payload.data);
}

export async function fetchGroup(
  baseUrl: string,
  accessToken: string,
  groupId: string,
  fetchImpl: ApiFetch = fetch
): Promise<GroupView> {
  const response = await fetchImpl(apiUrl(baseUrl, `/api/v1/groups/${groupId}`), {
    method: 'GET',
    headers: { accept: 'application/json', authorization: `Bearer ${accessToken}` }
  });
  if (!response.ok) {
    throw new GroupHttpError(response.status, `Group request failed with ${response.status}.`);
  }
  const payload = await response.json();
  if (!isRecord(payload)) {
    throw new GroupContractError('Group response is missing data.');
  }
  return parseGroupView(payload.data);
}

export function inviteMember(
  baseUrl: string,
  accessToken: string,
  groupId: string,
  memberId: string,
  role: GroupRole,
  expectedVersion: number,
  fetchImpl: ApiFetch = fetch
): Promise<GroupView> {
  return send(baseUrl, accessToken, 'POST', `/api/v1/groups/${groupId}/members`, fetchImpl,
    { memberId, role, placeholder: false }, expectedVersion);
}

export function activateMember(
  baseUrl: string,
  accessToken: string,
  groupId: string,
  memberId: string,
  expectedVersion: number,
  fetchImpl: ApiFetch = fetch
): Promise<GroupView> {
  return send(baseUrl, accessToken, 'POST',
    `/api/v1/groups/${groupId}/members/${memberId}/activate`, fetchImpl, undefined, expectedVersion);
}

export function deactivateMember(
  baseUrl: string,
  accessToken: string,
  groupId: string,
  memberId: string,
  expectedVersion: number,
  fetchImpl: ApiFetch = fetch
): Promise<GroupView> {
  return send(baseUrl, accessToken, 'DELETE',
    `/api/v1/groups/${groupId}/members/${memberId}`, fetchImpl, undefined, expectedVersion);
}

export function changeRole(
  baseUrl: string,
  accessToken: string,
  groupId: string,
  memberId: string,
  role: GroupRole,
  expectedVersion: number,
  fetchImpl: ApiFetch = fetch
): Promise<GroupView> {
  return send(baseUrl, accessToken, 'PATCH',
    `/api/v1/groups/${groupId}/members/${memberId}`, fetchImpl, { role }, expectedVersion);
}

export function archiveGroup(
  baseUrl: string,
  accessToken: string,
  groupId: string,
  expectedVersion: number,
  fetchImpl: ApiFetch = fetch
): Promise<GroupView> {
  return send(baseUrl, accessToken, 'POST', `/api/v1/groups/${groupId}/archive`, fetchImpl,
    undefined, expectedVersion);
}

export function reopenGroup(
  baseUrl: string,
  accessToken: string,
  groupId: string,
  expectedVersion: number,
  fetchImpl: ApiFetch = fetch
): Promise<GroupView> {
  return send(baseUrl, accessToken, 'POST', `/api/v1/groups/${groupId}/reopen`, fetchImpl,
    undefined, expectedVersion);
}
