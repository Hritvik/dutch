export type RedirectValidation =
  | { valid: true; authorizationCode: string }
  | { valid: false };

export function validateAuthorizationRedirect(
  returnedUrl: string,
  expectedRedirectUri: string,
  expectedState: string
): RedirectValidation {
  try {
    const returned = new URL(returnedUrl);
    const expected = new URL(expectedRedirectUri);
    const matchesRedirect = returned.protocol === expected.protocol
      && returned.hostname === expected.hostname
      && returned.port === expected.port
      && returned.pathname === expected.pathname;
    const code = returned.searchParams.get('code');
    const state = returned.searchParams.get('state');
    const error = returned.searchParams.get('error');

    return matchesRedirect && !error && code && state === expectedState
      ? { valid: true, authorizationCode: code }
      : { valid: false };
  } catch {
    return { valid: false };
  }
}
