import assert from 'node:assert/strict';
import test from 'node:test';

import { createAppConfig } from './app-config';

test('defaults the API base URL and leaves the issuer unset', () => {
  assert.deepEqual(createAppConfig({}), {
    apiBaseUrl: 'http://localhost:8080',
    issuer: null
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

test('keeps an explicit issuer for the PKCE request', () => {
  assert.equal(
    createAppConfig({ EXPO_PUBLIC_IAM_ISSUER: 'https://iam.example.test' }).issuer,
    'https://iam.example.test'
  );
});
