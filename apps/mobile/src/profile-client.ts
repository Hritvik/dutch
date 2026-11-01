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
  if (typeof retry !== 'number' || !Number.isInteger(retry) || retry <= 0) {
    throw new ProfileContractError('retryAfterSeconds must be a positive integer.');
  }
  return { state, retryAfterSeconds: retry };
}

export function parseMemberProfile(payload: unknown): MemberProfile {
  if (!isRecord(payload) || !isRecord(payload.data)) {
    throw new ProfileContractError('Member profile response is missing data.');
  }
  const { memberId, displayName, profileState, lifecycleState } = payload.data;
  if (typeof memberId !== 'string' || memberId.length === 0) {
    throw new ProfileContractError('memberId is required.');
  }
  if (displayName !== null && typeof displayName !== 'string') {
    throw new ProfileContractError('displayName must be a string or null.');
  }
  if (typeof lifecycleState !== 'string' || lifecycleState.length === 0) {
    throw new ProfileContractError('lifecycleState is required.');
  }
  return {
    memberId,
    displayName,
    profileState: parseState(profileState),
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
    throw new ProfileContractError(`Profile state request failed with ${response.status}.`);
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
    throw new ProfileContractError(`Member profile request failed with ${response.status}.`);
  }
  return parseMemberProfile(await response.json());
}
