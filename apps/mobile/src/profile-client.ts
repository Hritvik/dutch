export const PROFILE_STATES = ['PROFILE_REQUIRED', 'PROFILE_PROVISIONING', 'PROFILE_READY'] as const;
export type ProfileState = (typeof PROFILE_STATES)[number];

export type ProfileStateResult = {
  state: ProfileState;
  retryAfterSeconds?: number;
};

export type MemberProfile = {
  memberId: string;
  displayName: string | null;
  profileState: ProfileState;
  lifecycleState: string;
};

export class ProfileContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ProfileContractError';
  }
}

export class ProfileHttpError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = 'ProfileHttpError';
  }
}

export type ApiFetch = (input: string, init?: RequestInit) => Promise<Response>;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseState(value: unknown): ProfileState {
  if (typeof value !== 'string' || !PROFILE_STATES.includes(value as ProfileState)) {
    throw new ProfileContractError('Unknown profile state.');
  }
  return value as ProfileState;
}

export function parseProfileState(payload: unknown): ProfileStateResult {
  if (!isRecord(payload) || !isRecord(payload.data)) {
    throw new ProfileContractError('Profile state response is missing data.');
  }
  const state = parseState(payload.data.state);
  const retry = payload.data.retryAfterSeconds;
  if (retry === undefined) {
    return { state };
  }
  if (typeof retry !== 'number' || !Number.isInteger(retry) || retry <= 0 || retry > 3600) {
    throw new ProfileContractError('retryAfterSeconds must be a positive integer no greater than 3600.');
  }
  return { state, retryAfterSeconds: retry };
}

export function parseMemberProfile(payload: unknown): MemberProfile {
  if (!isRecord(payload) || !isRecord(payload.data)) {
    throw new ProfileContractError('Member profile response is missing data.');
  }
  const { memberId, displayName, profileState, lifecycleState } = payload.data;
  if (typeof memberId !== 'string' || !/^mem_[A-Za-z0-9_-]{16,128}$/.test(memberId)) {
    throw new ProfileContractError('memberId does not match the pinned format.');
  }
  if (displayName !== null && typeof displayName !== 'string') {
    throw new ProfileContractError('displayName must be a string or null.');
  }
  if (parseState(profileState) !== 'PROFILE_READY') {
    throw new ProfileContractError('The profile resource must return PROFILE_READY.');
  }
  if (lifecycleState !== 'ACTIVE') {
    throw new ProfileContractError('Only an ACTIVE member may reach the shell.');
  }
  return {
    memberId,
    displayName,
    profileState: 'PROFILE_READY',
    lifecycleState
  };
}

function apiUrl(baseUrl: string, path: string): string {
  return `${baseUrl.replace(/\/+$/, '')}${path}`;
}

async function authorizedGet(url: string, accessToken: string, fetchImpl: ApiFetch): Promise<Response> {
  return fetchImpl(url, {
    method: 'GET',
    headers: {
      accept: 'application/json',
      authorization: `Bearer ${accessToken}`
    }
  });
}

export async function fetchProfileState(
  baseUrl: string,
  accessToken: string,
  fetchImpl: ApiFetch = fetch
): Promise<ProfileStateResult> {
  const response = await authorizedGet(apiUrl(baseUrl, '/api/v1/me/profile-state'), accessToken, fetchImpl);
  if (!response.ok) {
    throw new ProfileHttpError(response.status, `Profile state request failed with ${response.status}.`);
  }
  return parseProfileState(await response.json());
}

export async function fetchMemberProfile(
  baseUrl: string,
  accessToken: string,
  fetchImpl: ApiFetch = fetch
): Promise<MemberProfile | null> {
  const response = await authorizedGet(apiUrl(baseUrl, '/api/v1/me'), accessToken, fetchImpl);
  if (response.status === 409) {
    return null;
  }
  if (!response.ok) {
    throw new ProfileHttpError(response.status, `Member profile request failed with ${response.status}.`);
  }
  return parseMemberProfile(await response.json());
}
