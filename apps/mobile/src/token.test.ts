import assert from 'node:assert/strict';
import test from 'node:test';

import { readAppSubject } from './token';

function token(payload: Record<string, unknown>): string {
  const encode = (value: Record<string, unknown>) =>
    Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${encode({ alg: 'RS256' })}.${encode(payload)}.signature`;
}

test('reads the app-scoped subject from a token payload', () => {
  assert.equal(readAppSubject(token({ sub: 'aps_0123456789012345678901ab' })), 'aps_0123456789012345678901ab');
});

test('rejects a token without a usable subject', () => {
  assert.throws(() => readAppSubject(token({ sub: '' })));
  assert.throws(() => readAppSubject(token({})));
  assert.throws(() => readAppSubject('not-a-jwt'));
  assert.throws(() => readAppSubject('a.b.c'));
});
