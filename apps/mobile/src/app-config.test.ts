import assert from 'node:assert/strict';
import test from 'node:test';

import { createAppConfig } from './app-config';

test('defaults the API base URL and leaves the issuer unset', () => {
  assert.deepEqual(createAppConfig({}), {
    apiBaseUrl: 'http://localhost:8080',
    issuer: null,
    devSession: false
  });
});

test('trims trailing slashes from an explicit API base URL', () => {
  assert.equal(
    createAppConfig({ EXPO_PUBLIC_DUTCH_API_URL: 'https://api.example.test/' }).apiBaseUrl,
    'https://api.example.test'
  );
});

test('rejects a base URL with a query or fragment', () => {
  assert.throws(() => createAppConfig({ EXPO_PUBLIC_DUTCH_API_URL: 'https://api.example.test?x=1' }));
  assert.throws(() => createAppConfig({ EXPO_PUBLIC_DUTCH_API_URL: 'https://api.example.test#top' }));
});

test('requires HTTPS outside a local development host', () => {
  assert.throws(() => createAppConfig({ EXPO_PUBLIC_DUTCH_API_URL: 'http://api.example.test' }));
  assert.equal(
    createAppConfig({ EXPO_PUBLIC_DUTCH_API_URL: 'http://127.0.0.1:4010' }).apiBaseUrl,
    'http://127.0.0.1:4010'
  );
  assert.equal(
    createAppConfig({ EXPO_PUBLIC_DUTCH_API_URL: 'http://10.0.2.2:4010' }).apiBaseUrl,
    'http://10.0.2.2:4010'
  );
});

test('rejects embedded credentials', () => {
  assert.throws(() => createAppConfig({ EXPO_PUBLIC_DUTCH_API_URL: 'https://user:pass@api.example.test' }));
});

test('keeps an explicit issuer for the PKCE request', () => {
  assert.equal(
    createAppConfig({ EXPO_PUBLIC_IAM_ISSUER: 'https://iam.example.test' }).issuer,
    'https://iam.example.test'
  );
});

test('enables the dev session only for the exact opt-in flag', () => {
  assert.equal(createAppConfig({ EXPO_PUBLIC_DEV_SESSION: '1' }).devSession, true);
  assert.equal(createAppConfig({ EXPO_PUBLIC_DEV_SESSION: 'true' }).devSession, false);
});
