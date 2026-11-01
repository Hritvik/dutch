export type MobileAuthorizationConfig = {
  clientId: 'dutch-mobile';
  responseType: 'code';
  usePKCE: true;
  scopes: string[];
};

export function createAuthorizationConfig(issuer: string): MobileAuthorizationConfig {
  const parsed = new URL(issuer);
  if (parsed.protocol !== 'https:' || parsed.search || parsed.hash || parsed.pathname !== '/') {
    throw new Error('IAM issuer must be an HTTPS origin without a path, query, or fragment.');
  }
  return {
    clientId: 'dutch-mobile',
    responseType: 'code',
    usePKCE: true,
    scopes: ['openid']
  };
}
