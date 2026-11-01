import * as AuthSession from 'expo-auth-session';

import { createAuthorizationConfig } from './oauth-config';

export async function loadMobileAuthorizationRequest(issuer: string) {
  const configuration = createAuthorizationConfig(issuer);
  const redirectUri = AuthSession.makeRedirectUri({ scheme: 'dutch', path: 'oauth/callback' });
  const request = await AuthSession.loadAsync(
    {
      ...configuration,
      responseType: AuthSession.ResponseType.Code,
      redirectUri
    },
    issuer
  );
  return { request, redirectUri };
}
