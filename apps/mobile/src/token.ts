const BASE64_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

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

/**
 * Builds an unsigned token for the development-only device journey. It is
 * never accepted by a resource server: the mock API ignores signatures and
 * production builds do not enable the dev session.
 */
export function createDevAccessToken(subject = 'aps_dev_0123456789012345678901'): string {
  return `${encodeBase64Url(JSON.stringify({ alg: 'none', typ: 'JWT' }))}.${encodeBase64Url(
    JSON.stringify({ sub: subject })
  )}.`;
}

function decodeBase64Url(value: string): string {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/').replace(/=+$/, '');
  let bits = 0;
  let accumulator = 0;
  const bytes: number[] = [];
  for (const character of normalized) {
    const index = BASE64_ALPHABET.indexOf(character);
    if (index === -1) {
      throw new Error('Invalid base64url value.');
    }
    accumulator = (accumulator << 6) | index;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      bytes.push((accumulator >> bits) & 0xff);
    }
  }
  return decodeURIComponent(bytes.map((byte) => `%${byte.toString(16).padStart(2, '0')}`).join(''));
}

function encodeBase64Url(value: string): string {
  const bytes = utf8Bytes(value);
  let bits = 0;
  let accumulator = 0;
  let encoded = '';
  for (const byte of bytes) {
    accumulator = (accumulator << 8) | byte;
    bits += 8;
    while (bits >= 6) {
      bits -= 6;
      encoded += BASE64_ALPHABET[(accumulator >> bits) & 0x3f];
    }
  }
  if (bits > 0) {
    encoded += BASE64_ALPHABET[(accumulator << (6 - bits)) & 0x3f];
  }
  return encoded;
}

function utf8Bytes(value: string): number[] {
  const percentEncoded = encodeURIComponent(value);
  const bytes: number[] = [];
  for (let index = 0; index < percentEncoded.length; index += 1) {
    if (percentEncoded[index] === '%') {
      bytes.push(Number.parseInt(percentEncoded.slice(index + 1, index + 3), 16));
      index += 2;
    } else {
      bytes.push(percentEncoded.charCodeAt(index));
    }
  }
  return bytes;
}
