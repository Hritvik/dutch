import type { ProfileStateResult } from './profile-client';
import type { SessionState } from './session-state';

export type AppScreen =
  | 'loading'
  | 'signedOut'
  | 'profileRequired'
  | 'profiling'
  | 'offline'
  | 'home';

export type GateInput = {
  session: SessionState;
  profile?: ProfileStateResult;
  online: boolean;
};

/**
 * Chooses the visible shell from authenticated session state plus the
 * authoritative profile-state answer. Unknown or missing state never grants
 * the authenticated shell.
 */
export function screenFor({ session, profile, online }: GateInput): AppScreen {
  switch (session.kind) {
    case 'booting':
      return 'loading';
    case 'signedOut':
      return 'signedOut';
    case 'authenticated':
      return 'home';
    case 'profileRequired': {
      if (!online) {
        return 'offline';
      }
      if (!profile) {
        return 'profileRequired';
      }
      switch (profile.state) {
        case 'PROFILE_REQUIRED':
        case 'PROFILE_PROVISIONING':
          return 'profileRequired';
        case 'PROFILE_READY':
          return 'profiling';
      }
    }
  }
}
