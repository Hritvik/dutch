export type DutchAppConfig = {
  apiBaseUrl: string;
  issuer: string | null;
  devSession: boolean;
};

const DEFAULT_API_BASE_URL = 'http://localhost:8080';

const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '10.0.2.2']);

function parseBaseUrl(value: string | undefined, name: string): string {
  const candidate = value && value.length > 0 ? value : DEFAULT_API_BASE_URL;
  const parsed = new URL(candidate);
  if ((parsed.protocol !== 'https:' && parsed.protocol !== 'http:') || parsed.search || parsed.hash) {
    throw new Error(`${name} must be an HTTP(S) origin without a query or fragment.`);
  }
  if (parsed.protocol === 'http:' && !LOCAL_HOSTS.has(parsed.hostname)) {
    throw new Error(`${name} must use HTTPS outside a local development host.`);
  }
  if (parsed.username || parsed.password) {
    throw new Error(`${name} must not carry credentials.`);
  }
  return candidate.replace(/\/+$/, '');
}

export function createAppConfig(env: Record<string, string | undefined>): DutchAppConfig {
  const issuerValue = env.EXPO_PUBLIC_IAM_ISSUER;
  return {
    apiBaseUrl: parseBaseUrl(env.EXPO_PUBLIC_DUTCH_API_URL, 'EXPO_PUBLIC_DUTCH_API_URL'),
    issuer: issuerValue && issuerValue.length > 0 ? issuerValue : null,
    devSession: env.EXPO_PUBLIC_DEV_SESSION === '1'
  };
}
