'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { deliverBrowserImage, imageBlock } = require('../../assets/plugins/browser-use/plugin/lib/image-delivery')

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'browser-image-'))
  fs.mkdirSync(path.join(root, 'shot'))
  fs.mkdirSync(path.join(root, 'selection'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aJ1sAAAAASUVORK5CYII=', 'base64')
  const candidate = path.join(root, 'shot', 'one.png')
  fs.writeFileSync(candidate, png)
  return { root, candidate, png }
}

test('durable SDK reference carries pixels rather than a filesystem image path', async (t) => {
  const { root, candidate, png } = fixture(t)
  let received
  const result = await deliverBrowserImage(candidate, { saveImage: async (input) => {
    received = input
    return { attachmentId: 'owned-sha256', mediaType: 'image/png', bytes: png.length, width: 1, height: 1 }
  } }, { outputRoot: root })
  assert.equal(result.code, 'OK')
  assert.deepEqual(received.data, png)
  assert.equal(imageBlock(result.referenceJson).attachment.attachmentId, 'owned-sha256')
  assert.equal(JSON.stringify(imageBlock(result.referenceJson)).includes(candidate), false)
})

test('missing service is explicitly unavailable and never fabricates a reference', async (t) => {
  const { root, candidate } = fixture(t)
  assert.deepEqual(await deliverBrowserImage(candidate, null, { outputRoot: root }), {
    code: 'ATTACHMENTS_UNAVAILABLE', referenceJson: '',
  })
})

test('out-of-scope files and incorrect image bytes never reach storage', async (t) => {
  const { root, candidate } = fixture(t)
  let calls = 0
  const store = { saveImage: async () => { calls++; throw new Error('must not be reached') } }
  const outside = path.join(root, 'secret.png')
  fs.copyFileSync(candidate, outside)
  assert.equal((await deliverBrowserImage(outside, store, { outputRoot: root })).code, 'IMAGE_DELIVERY_FAILED')
  fs.writeFileSync(candidate, Buffer.alloc(30))
  assert.equal((await deliverBrowserImage(candidate, store, { outputRoot: root })).code, 'IMAGE_DELIVERY_FAILED')
  assert.equal(calls, 0)
})

test('invalid storage response is rejected instead of becoming an image block', async (t) => {
  const { root, candidate } = fixture(t)
  const result = await deliverBrowserImage(candidate, { saveImage: async () => ({ attachmentId: candidate }) }, { outputRoot: root })
  assert.equal(result.code, 'IMAGE_DELIVERY_FAILED')
  assert.equal(imageBlock(result.referenceJson), null)
})

test('PNG dimension bomb and corrupt header are rejected before invoking the decoder', async (t) => {
  const { root, candidate, png } = fixture(t)
  let calls = 0
  const store = { saveImage: async () => { calls++; throw new Error('must not be reached') } }
  const oversized = Buffer.from(png)
  oversized.writeUInt32BE(0xffffffff, 16)
  fs.writeFileSync(candidate, oversized)
  assert.equal((await deliverBrowserImage(candidate, store, { outputRoot: root })).code, 'IMAGE_DELIVERY_FAILED')
  const broken = Buffer.from(png)
  broken[29] ^= 1
  fs.writeFileSync(candidate, broken)
  assert.equal((await deliverBrowserImage(candidate, store, { outputRoot: root })).code, 'IMAGE_DELIVERY_FAILED')
  assert.equal(calls, 0)
})

test('symbolic-link image cannot exfiltrate a file through an allowed leaf name', async (t) => {
  const { root, candidate } = fixture(t)
  const link = path.join(root, 'selection', 'link.png')
  fs.symlinkSync(candidate, link)
  let called = false
  const result = await deliverBrowserImage(link, { saveImage: async () => { called = true } }, { outputRoot: root })
  assert.equal(result.code, 'IMAGE_DELIVERY_FAILED')
  assert.equal(called, false)
})
