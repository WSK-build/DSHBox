'use strict'

const { browserServeRequest } = require('./serve-client')
const SERVICE_ID = /^[a-f0-9]{32}$/

function loopbackUrl(value) {
  try {
    const url = new URL(value)
    return url.protocol === 'http:' && url.hostname === '127.0.0.1' && url.port &&
      !url.username && !url.password ? url : null
  } catch { return null }
}

function dataOf(result) {
  if (!result || result.ok !== true || result.code !== 'OK') return null
  try { return JSON.parse(result.dataJson) } catch { return null }
}

function matchesService(service, page, now) {
  if (!service || !SERVICE_ID.test(service.serviceId) || !/^[a-f0-9]{24}$/.test(service.projectId) ||
      !['owned-static', 'external'].includes(service.kind) || service.state !== 'RUNNING' ||
      !Number.isSafeInteger(service.leaseExpiresAtMillis) || service.leaseExpiresAtMillis <= now) return false
  const base = loopbackUrl(service.url)
  if (!base || base.origin !== page.origin || base.search || base.hash) return false
  return page.pathname === base.pathname ||
    page.pathname.startsWith(base.pathname.endsWith('/') ? base.pathname : base.pathname + '/')
}

/** Adds project metadata only after the registered loopback service passes a fresh ownership/health check. */
function createProjectContextResolver({ request = browserServeRequest, dependencies = {}, now = Date.now,
  timeoutMs = 2000 } = {}) {
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 5000) throw new TypeError('Invalid lookup budget')
  return async function resolveProjectContext({ pageUrl, signal } = {}) {
    const page = loopbackUrl(pageUrl)
    if (!page || page.pathname.length > 512 || signal && signal.aborted) return null
    const clock = now()
    if (!Number.isSafeInteger(clock) || clock < 0) return null
    const controller = new AbortController()
    const abort = () => controller.abort()
    const timer = setTimeout(abort, timeoutMs)
    if (signal) signal.addEventListener('abort', abort, { once: true })
    const interrupted = new Promise(resolve => controller.signal.addEventListener('abort', () => resolve(null), { once: true }))
    const run = args => controller.signal.aborted ? Promise.resolve(null) : Promise.race([
      Promise.resolve().then(() => request(args, { ...dependencies, signal: controller.signal })).catch(() => null),
      interrupted,
    ])
    try {
      const listed = dataOf(await run({ action: 'list' }))
      if (controller.signal.aborted || !listed || !Array.isArray(listed.services) || listed.services.length > 4) return null
      const candidates = listed.services.filter(service => matchesService(service, page, clock))
      if (candidates.length !== 1) return null
      const candidate = candidates[0]
      const current = dataOf(await run({ action: 'status', serviceId: candidate.serviceId }))
      const checkedAt = now()
      if (controller.signal.aborted || !Number.isSafeInteger(checkedAt) || checkedAt < clock ||
          checkedAt - clock >= timeoutMs || !matchesService(current, page, checkedAt) ||
          current.serviceId !== candidate.serviceId || current.projectId !== candidate.projectId ||
          current.kind !== candidate.kind || current.url !== candidate.url) return null
      return Object.freeze({ serviceId: current.serviceId, projectId: current.projectId,
        kind: current.kind, route: page.pathname, relation: 'current_registered_route_match',
        captureProvenanceVerified: false })
    } catch { return null }
    finally {
      clearTimeout(timer)
      if (signal) signal.removeEventListener('abort', abort)
    }
  }
}

/** Accept only native-verified capture metadata; never promote a fresh service lookup to capture proof. */
function capturedProjectContext(provenance, pageUrl) {
  const value = provenance && provenance.projectContext
  const page = loopbackUrl(pageUrl)
  if (!page || !value || value.captureProvenanceVerified !== true ||
      value.relation !== 'authenticated_document_source' || value.kind !== 'owned-static' ||
      !SERVICE_ID.test(value.serviceId) || !/^[a-f0-9]{24}$/.test(value.projectId) ||
      !SERVICE_ID.test(value.documentNonce) || value.origin !== page.origin || value.route !== page.pathname ||
      typeof value.projectRootRelative !== 'string' || value.projectRootRelative.length < 1 ||
      value.projectRootRelative.length > 1024 || value.projectRootRelative.startsWith('/') ||
      value.projectRootRelative.includes('\\') || /[\u0000-\u001f\u007f]/.test(value.projectRootRelative) ||
      value.projectRootRelative.split('/').some(part => !part || part === '.' || part === '..') ||
      !Number.isSafeInteger(value.issuedAtMillis) || !Number.isSafeInteger(value.capturedAtMillis) ||
      value.issuedAtMillis < 0 || value.capturedAtMillis < value.issuedAtMillis) return null
  return Object.freeze({ serviceId: value.serviceId, projectId: value.projectId, kind: value.kind,
    origin: value.origin, route: value.route, projectRootRelative: value.projectRootRelative,
    documentNonce: value.documentNonce, issuedAtMillis: value.issuedAtMillis,
    capturedAtMillis: value.capturedAtMillis, relation: value.relation, captureProvenanceVerified: true })
}

module.exports = { createProjectContextResolver, capturedProjectContext }
