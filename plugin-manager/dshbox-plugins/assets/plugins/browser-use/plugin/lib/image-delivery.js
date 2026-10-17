'use strict'

const fs = require('node:fs')
const path = require('node:path')

const MAX_BYTES = 8 * 1024 * 1024
const PNG_SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])

function sameFile(left, right) {
  return left.dev === right.dev && left.ino === right.ino && left.size === right.size &&
    left.mtimeMs === right.mtimeMs && left.ctimeMs === right.ctimeMs
}

function admissiblePngHeader(bytes) {
  if (bytes.length < 33 || !bytes.subarray(0, 8).equals(PNG_SIGNATURE) ||
      bytes.readUInt32BE(8) !== 13 || bytes.toString('ascii', 12, 16) !== 'IHDR') return false
  const width = bytes.readUInt32BE(16)
  const height = bytes.readUInt32BE(20)
  if (!width || !height || width > 16384 || height > 16384 || width * height > 16 * 1024 * 1024) return false
  let crc = 0xffffffff
  for (let offset = 12; offset < 29; offset++) {
    crc ^= bytes[offset]
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0)
  }
  return ((crc ^ 0xffffffff) >>> 0) === bytes.readUInt32BE(29)
}

/** Only the adapter's fixed output root is admitted; outputRoot is a test seam, never a tool argument. */
function readOwnedImage(candidate, outputRoot = '/opt/browser/out') {
  if (typeof candidate !== 'string') throw new Error('ARTIFACT_UNAVAILABLE')
  const root = path.resolve(outputRoot)
  const relative = path.relative(root, path.resolve(candidate))
  if (!/^(shot|selection)[/\\][A-Za-z0-9_.-]+\.png$/.test(relative) || relative.includes('..')) {
    throw new Error('ARTIFACT_UNAVAILABLE')
  }
  const directory = path.dirname(candidate)
  const directories = [root, directory]
  const identities = directories.map((name) => {
    const stat = fs.lstatSync(name)
    if (!stat.isDirectory() || stat.isSymbolicLink() || fs.realpathSync(name) !== path.resolve(name)) {
      throw new Error('ARTIFACT_UNAVAILABLE')
    }
    return stat
  })
  const before = fs.lstatSync(candidate)
  if (!before.isFile() || before.isSymbolicLink() || before.size < 33 || before.size > MAX_BYTES) {
    throw new Error('ARTIFACT_UNAVAILABLE')
  }
  const descriptor = fs.openSync(candidate, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0))
  try {
    const opened = fs.fstatSync(descriptor)
    if (!opened.isFile() || !sameFile(before, opened)) throw new Error('ARTIFACT_UNAVAILABLE')
    const bytes = Buffer.alloc(opened.size)
    let offset = 0
    while (offset < bytes.length) {
      const count = fs.readSync(descriptor, bytes, offset, bytes.length - offset, offset)
      if (!count) throw new Error('ARTIFACT_UNAVAILABLE')
      offset += count
    }
    if (!sameFile(opened, fs.fstatSync(descriptor)) || !sameFile(opened, fs.lstatSync(candidate))) {
      throw new Error('ARTIFACT_UNAVAILABLE')
    }
    directories.forEach((name, index) => {
      const after = fs.lstatSync(name)
      if (!after.isDirectory() || after.isSymbolicLink() || after.dev !== identities[index].dev ||
          after.ino !== identities[index].ino || fs.realpathSync(name) !== path.resolve(name)) {
        throw new Error('ARTIFACT_UNAVAILABLE')
      }
    })
    if (!admissiblePngHeader(bytes)) throw new Error('ARTIFACT_UNAVAILABLE')
    return bytes
  } finally {
    fs.closeSync(descriptor)
  }
}

function normalizeReference(value) {
  if (!value || typeof value.attachmentId !== 'string' || value.attachmentId.length < 1 ||
      value.attachmentId.length > 256 || /[\s/\\]/.test(value.attachmentId) ||
      !['image/png', 'image/jpeg', 'image/webp', 'image/gif'].includes(value.mediaType) ||
      !Number.isSafeInteger(value.bytes) || value.bytes <= 0 || value.bytes > MAX_BYTES ||
      !Number.isSafeInteger(value.width) || value.width <= 0 ||
      !Number.isSafeInteger(value.height) || value.height <= 0 || value.width * value.height > 64 * 1024 * 1024) {
    throw new Error('ATTACHMENT_REFERENCE_INVALID')
  }
  return {
    attachmentId: value.attachmentId,
    mediaType: value.mediaType,
    bytes: value.bytes,
    width: value.width,
    height: value.height,
  }
}

/** Promote pixels through the existing DSH attachment service, never fabricate a path-based image block. */
async function deliverBrowserImage(candidate, attachments, options = {}) {
  if (!attachments || typeof attachments.saveImage !== 'function') {
    return { code: 'ATTACHMENTS_UNAVAILABLE', referenceJson: '' }
  }
  try {
    const bytes = readOwnedImage(candidate, options.outputRoot)
    const saved = await attachments.saveImage({ data: bytes, mediaType: 'image/png', name: 'browser-selection.png' })
    return { code: 'OK', referenceJson: JSON.stringify(normalizeReference(saved)) }
  } catch {
    return { code: 'IMAGE_DELIVERY_FAILED', referenceJson: '' }
  }
}

function imageBlock(referenceJson) {
  if (typeof referenceJson !== 'string' || referenceJson.length > 1024) return null
  try { return { type: 'image', attachment: normalizeReference(JSON.parse(referenceJson)) } } catch { return null }
}

module.exports = { deliverBrowserImage, imageBlock, readOwnedImage }
