'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { addProjectDocumentProof, makeServer } = require('../../main/assets/browser/lib/browser-serve.cjs');
const config = { serviceId: 'a'.repeat(32), token: 'b'.repeat(64), root: '/root/projects/demo', leaseExpiresAtMillis: 5000 };

test('served document preserves DOCTYPE and carries verifiable bounded source identity', () => {
  const original = Buffer.from('\ufeff<!DOCTYPE html><html><head></head><body>中文</body></html>');
  const output = addProjectDocumentProof(original, config, '/index.html', 1000).toString('utf8');
  assert.ok(output.startsWith('\ufeff<!DOCTYPE html><meta '));
  const [, encoded, signature] = output.match(/content="([A-Za-z0-9_-]+)\.([a-f0-9]{64})"/);
  assert.equal(signature, crypto.createHmac('sha256', Buffer.from(config.token, 'hex')).update(encoded).digest('hex'));
  const proof = JSON.parse(Buffer.from(encoded, 'base64url'));
  assert.equal(proof.serviceId, config.serviceId);
  assert.equal(proof.projectId, crypto.createHash('sha256').update(config.root).digest('hex').slice(0, 24));
  assert.equal(proof.route, '/index.html');
  assert.equal(proof.expiresAtMillis, 5000);
  assert.match(proof.documentNonce, /^[a-f0-9]{32}$/);
  assert.equal(output.replace(/<meta name="dsh-browser-project" content="[^"]+">/, ''), original.toString('utf8'));
  assert.ok(!output.includes(config.token));
});

test('expired or invalid routes do not produce misleading source proofs', () => {
  const original = Buffer.from('<p>page</p>');
  for (const route of ['/x?secret=1', '/x#section', '/x\n', 'relative', '/' + 'x'.repeat(512)]) {
    assert.equal(addProjectDocumentProof(original, config, route, 1000), original);
  }
  assert.equal(addProjectDocumentProof(original, config, '/', 5000), original);
  assert.equal(addProjectDocumentProof(Buffer.from('<!DOCTYPE html'), config, '/', 1000).toString(), '<!DOCTYPE html');
});

test('distinct document responses cannot share a nonce or signature', () => {
  const original = Buffer.from('<p>page</p>');
  assert.notDeepEqual(addProjectDocumentProof(original, config, '/', 1000), addProjectDocumentProof(original, config, '/', 1000));
});

test('real HTTP HTML serving reports the exact signed body length and retains non-HTML bytes', async () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const http = require('node:http');
  const directory = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'browser-proof-http-'));
  fs.writeFileSync(path.join(directory, 'index.html'), '<!DOCTYPE html><p>original page</p>');
  fs.writeFileSync(path.join(directory, 'file.txt'), 'unchanged');
  const service = await makeServer({ ...config, root: directory, leaseExpiresAtMillis: Date.now() + 60000,
    logFile: path.join(directory, 'service.log') }, { exitProcess: () => {} });
  await new Promise(resolve => service.server.listen(0, '127.0.0.1', resolve));
  const port = service.server.address().port;
  const read = route => new Promise((resolve, reject) => {
    http.get({ hostname: '127.0.0.1', port, path: route }, response => {
      const chunks = []; response.on('data', chunk => chunks.push(chunk));
      response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, bytes: Buffer.concat(chunks) }));
      response.on('error', reject);
    }).on('error', reject);
  });
  try {
    const html = await read('/index.html');
    assert.equal(html.status, 200);
    assert.equal(Number(html.headers['content-length']), html.bytes.length);
    assert.ok(html.bytes.toString().startsWith('<!DOCTYPE html><meta name="dsh-browser-project"'));
    assert.ok(html.bytes.toString().endsWith('<p>original page</p>'));
    assert.equal((await read('/file.txt')).bytes.toString(), 'unchanged');
  } finally {
    await new Promise(resolve => service.server.close(resolve));
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
