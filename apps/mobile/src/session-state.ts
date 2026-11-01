export type SessionState =
  | { kind: 'booting'; ledgerAvailable: false }
  | { kind: 'signedOut'; ledgerAvailable: false }
  | { kind: 'profileRequired'; identityId: string; ledgerAvailable: false }
  | { kind: 'authenticated'; identityId: string; memberId: string; ledgerAvailable: true };

export type SessionEvent =
  | { type: 'iamAuthenticated'; identityId: string }
  | { type: 'memberProfileReady'; identityId: string; memberId: string }
  | { type: 'refreshFamilyRevoked' }
  | { type: 'logoutCompleted' };

export const initialSessionState: SessionState = {
  kind: 'signedOut',
  ledgerAvailable: false
};

export function reduceSessionState(state: SessionState, event: SessionEvent): SessionState {
  switch (event.type) {
    case 'iamAuthenticated':
      return { kind: 'profileRequired', identityId: event.identityId, ledgerAvailable: false };
    case 'memberProfileReady':
      return state.kind === 'profileRequired' && state.identityId === event.identityId
        ? {
            kind: 'authenticated',
            identityId: event.identityId,
            memberId: event.memberId,
            ledgerAvailable: true
          }
        : state;
    case 'refreshFamilyRevoked':
    case 'logoutCompleted':
      return initialSessionState;
  }
}
