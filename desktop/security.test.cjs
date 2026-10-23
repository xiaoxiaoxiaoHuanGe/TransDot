'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { normalizeServerUrl, isServerPage } = require('./security.cjs');

test('accepts HTTPS server origins, including nonstandard ports', () => {
  assert.equal(normalizeServerUrl(' https://example.com:9004/ '), 'https://example.com:9004');
});
test('allows HTTP only for local development', () => {
  assert.equal(normalizeServerUrl('http://localhost:5757'), 'http://localhost:5757');
  assert.throws(() => normalizeServerUrl('http://example.com:5757'), /HTTPS/);
  assert.throws(() => normalizeServerUrl('http://192.168.1.20:5757'), /HTTPS/);
});
test('rejects redirects through arbitrary URL parts and credentials', () => {
  for (const url of ['https://example.com/path', 'https://example.com/?a=1', 'https://x:y@example.com', 'file:///tmp/a', 'javascript:alert(1)']) {
    assert.throws(() => normalizeServerUrl(url));
  }
});
test('only trusts the configured origin', () => {
  assert.equal(isServerPage('https://example.com:9004/messages', 'https://example.com:9004'), true);
  assert.equal(isServerPage('https://example.com.evil.org/', 'https://example.com'), false);
  assert.equal(isServerPage('https://example.com:9005', 'https://example.com:9004'), false);
  assert.equal(isServerPage('file:///tmp/index.html', 'https://example.com'), false);
});
