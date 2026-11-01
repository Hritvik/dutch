import assert from 'node:assert/strict';
import test from 'node:test';

import { createAuthorizationConfig } from './oauth-config';

test('builds an authorization-code S256 request without a mobile client secret', () => {
  const config = createAuthorizationConfig('https://iam.dutch.example');

  assert.equal(config.clientId, 'dutch-mobile');
  assert.equal(config.responseType, 'code');
  assert.equal(config.usePKCE, true);
  assert.deepEqual(config.scopes, ['openid']);
  assert.equal('clientSecret' in config, false);
});

test('rejects insecure or query-bearing issuer values', () => {
  assert.throws(() => createAuthorizationConfig('http://iam.dutch.example'));
  assert.throws(() => createAuthorizationConfig('https://iam.dutch.example?issuer=other'));
});
