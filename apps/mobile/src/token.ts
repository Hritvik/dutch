/**
 * Reads the app-scoped subject from a signed access token for local routing
 * only. The resource servers verify the signature; this value never grants
 * authority and is never sent anywhere.
 */
export function readAppSubject(accessToken: string): string {
  const parts = accessToken.split('.');
  if (parts.length !== 3) {
    throw new Error('Access token is not a JWT.');
  }
  let payload: unknown;
  try {
    payload = JSON.parse(decodeBase64Url(parts[1]));
  } catch {
    throw new Error('Access token payload is not valid JSON.');
  }
  if (typeof payload !== 'object' || payload === null) {
    throw new Error('Access token payload is not an object.');
  }
  const subject = (payload as Record<string, unknown>).sub;
  if (typeof subject !== 'string' || subject.length === 0) {
    throw new Error('Access token has no subject.');
  }
  return subject;
}

function decodeBase64Url(value: string): string {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/');
  const padded = normalized.padEnd(normalized.length + ((4 - (normalized.length % 4)) % 4), '=');
  return atob(padded);
}
