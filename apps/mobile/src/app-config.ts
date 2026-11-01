export type DutchAppConfig = {
  apiBaseUrl: string;
  issuer: string | null;
};

const DEFAULT_API_BASE_URL = 'http://localhost:8080';

function parseBaseUrl(value: string | undefined, name: string): string {
  const candidate = value && value.length > 0 ? value : DEFAULT_API_BASE_URL;
  const parsed = new URL(candidate);
  if ((parsed.protocol !== 'https:' && parsed.protocol !== 'http:') || parsed.search || parsed.hash) {
    throw new Error(`${name} must be an HTTP(S) origin without a query or fragment.`);
  }
  return candidate.replace(/\/+$/, '');
}

export function createAppConfig(env: Record<string, string | undefined>): DutchAppConfig {
  const issuerValue = env.EXPO_PUBLIC_IAM_ISSUER;
  return {
    apiBaseUrl: parseBaseUrl(env.EXPO_PUBLIC_DUTCH_API_URL, 'EXPO_PUBLIC_DUTCH_API_URL'),
    issuer: issuerValue && issuerValue.length > 0 ? issuerValue : null
  };
}
