'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

test('installer and portable artifacts must have different filenames', () => {
  const pkg = require('./package.json');
  const nsis = pkg.build.nsis.artifactName;
  const portable = pkg.build.portable.artifactName;
  assert.match(nsis, /Setup/i);
  assert.match(portable, /Portable/i);
  assert.notEqual(nsis, portable);
});

test('embedded Windows tray icon contains a complete, valid PNG', () => {
  const source = fs.readFileSync(path.join(__dirname, 'main.cjs'), 'utf8');
  const match = source.match(/const ICON_PNG = '([A-Za-z0-9+/=]+)'/);
  assert.ok(match, 'icon must exist');
  const bytes = Buffer.from(match[1], 'base64');
  assert.equal(bytes.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
  assert.equal(bytes.readUInt32BE(16), 32);
  assert.equal(bytes.readUInt32BE(20), 32);
  const chunks = [];
  let p = 8;
  let ended = false;
  while (p < bytes.length) {
    assert.ok(p + 12 <= bytes.length, 'complete PNG chunk header');
    const n = bytes.readUInt32BE(p);
    const tag = bytes.toString('ascii', p + 4, p + 8);
    assert.ok(p + n + 12 <= bytes.length, 'complete PNG chunk');
    if (tag === 'IDAT') chunks.push(bytes.subarray(p + 8, p + 8 + n));
    p += n + 12;
    if (tag === 'IEND') { ended = true; break; }
  }
  assert.ok(ended, 'PNG must end with IEND');
  assert.equal(p, bytes.length, 'no truncated or trailing data');
  assert.ok(zlib.inflateSync(Buffer.concat(chunks)).length > 0, 'PNG pixel data must decompress');
});
