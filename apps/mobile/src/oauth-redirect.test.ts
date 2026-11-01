import assert from 'node:assert/strict';
import test from 'node:test';

import { validateAuthorizationRedirect } from './oauth-redirect';

test('accepts only an exact redirect URI and matching state before exchanging a code', () => {
  const result = validateAuthorizationRedirect(
    'dutch://oauth/callback?code=opaque-code&state=state-value-with-entropy',
    'dutch://oauth/callback',
    'state-value-with-entropy'
  );

  assert.deepEqual(result, { valid: true, authorizationCode: 'opaque-code' });
});

test('rejects a prefix redirect, mismatched state, and an error result', () => {
  assert.deepEqual(
    validateAuthorizationRedirect(
      'dutch://oauth/callback/extra?code=opaque-code&state=state-value-with-entropy',
      'dutch://oauth/callback',
      'state-value-with-entropy'
    ),
    { valid: false }
  );
  assert.deepEqual(
    validateAuthorizationRedirect(
      'dutch://oauth/callback?code=opaque-code&state=attacker-state',
      'dutch://oauth/callback',
      'state-value-with-entropy'
    ),
    { valid: false }
  );
  assert.deepEqual(
    validateAuthorizationRedirect(
      'dutch://oauth/callback?error=access_denied&state=state-value-with-entropy',
      'dutch://oauth/callback',
      'state-value-with-entropy'
    ),
    { valid: false }
  );
});
