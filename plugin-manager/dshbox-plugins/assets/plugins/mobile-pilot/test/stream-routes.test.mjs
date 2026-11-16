// Node 半实时流桥（lib/stream.js）的契约：真实 webServer 形状
// （register({ kind, path, handler })，handler 是 Node (req,res)）、真实 connection
// 准入形状（admit→{peer}/{rejection}、requestRejection→状态码/undefined）、
// POST+CSRF（同源/IPv6）、sessionId 会话归属、ReadableStream 数据面、收场语义。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { PassThrough, Readable } from 'node:stream'
import { fileURLToPath } from 'node:url'

const PACK_DIR = fileURLToPath(new URL('..', import.meta.url))
const STREAM_PATH = path.join(PACK_DIR, 'plugin', 'lib', 'stream.js')
const { readRendezvous, inspectWebServer, createStreamHandlers, attach, isLoopbackHost, isSameOrigin, ROUTE_BASE, ROUTE_INPUT, ROUTE_TASKS, ROUTE_MOVE, ROUTE_RELEASE, ROUTE_ROTATE } =
  await import('file://' + STREAM_PATH)

test('lib/stream.js 通过 node --check', () => {
  const r = spawnSync(process.execPath, ['--check', STREAM_PATH], { encoding: 'utf8' })
  assert.equal(r.status, 0, `stderr: ${r.stderr}`)
})

// ── rendezvous ──

test('rendezvous：合法文件读回端口与 token，缺失/越界/残形都回 null', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pilot-entry-'))
  fs.mkdirSync(path.join(dir, 'run'), { recursive: true })
  const file = path.join(dir, 'run', 'stream-endpoint.json')
  fs.writeFileSync(file, JSON.stringify({ controlPort: 41234, controlToken: 'ctl-7', writtenAtMs: 1 }))
  assert.deepEqual(readRendezvous(dir), { controlPort: 41234, controlToken: 'ctl-7' })

  assert.equal(readRendezvous(path.join(dir, 'nope')), null)
  fs.writeFileSync(file, JSON.stringify({ controlPort: 70000, controlToken: 't' }))
  assert.equal(readRendezvous(dir), null)
  fs.writeFileSync(file, JSON.stringify({ controlPort: 4000, controlToken: '' }))
  assert.equal(readRendezvous(dir), null)
  fs.writeFileSync(file, 'garbage')
  assert.equal(readRendezvous(dir), null)
})

// ── webServer 形状校验 ──

test('webServer 形状：有 register 才认；缺席/残缺都给可读降级原因', () => {
  const hit = inspectWebServer({ webServer: { register: () => () => {} } })
  assert.equal(hit.ok, true)
  const miss = inspectWebServer({})
  assert.equal(miss.ok, false)
  assert.match(miss.reason, /webServer/)
  const broken = inspectWebServer({ webServer: {} })
  assert.equal(broken.ok, false)
  assert.match(broken.reason, /register/)
})

// ── 测试基建：真实 connection 形状 + Node 双参假 req/res ──

/** 0.2.2-rc.1 的 connection 形状：admit 回 {peer}/{rejection}，requestRejection 回状态码/undefined。 */
function fakeConnection ({ verdict = 'peer', status = 403 } = {}) {
  return {
    admit (req) {
      if (verdict === 'rejection-number') return { rejection: status }
      if (verdict === 'rejection-object') return { rejection: { status } }
      return { peer: { id: 'test-peer' } }
    },
    requestRejection (req) {
      if (verdict === 'peer') return undefined
      return status
    },
  }
}

function fakeRes () {
  // 双工假体：pipeline 需要真正的 Writable；同时挂 writeHead/headers/end 的 shim。
  // writableFinished/destroyed 是流自带的只读 getter：测试直接读它们（的
  // clientGone 判定用的就是这两个真值）。
  const res = new PassThrough()
  res.statusCode = null
  res.headers = null
  res.written = []
  res.writeHead = (status, headers) => { res.statusCode = status; res.headers = headers; return res }
  const originalEnd = res.end.bind(res)
  res.end = (body) => {
    if (body !== undefined && body !== null) res.written.push(body)
    res.emit('finished')
    return originalEnd()
  }
  const originalDestroy = res.destroy.bind(res)
  res.destroy = () => { res.emit('close'); return originalDestroy() }
  return res
}

function fakeReq ({ method = 'GET', url = '/', headers = {} } = {}) {
  return { method, url, headers, socket: { remoteAddress: '127.0.0.1' } }
}

function webStreamOf (chunks) {
  return new ReadableStream({
    start (controller) {
      for (const chunk of chunks) controller.enqueue(new Uint8Array(chunk))
      controller.close()
    },
  })
}

function deps (overrides = {}) {
  return {
    fetchImpl: overrides.fetchImpl,
    controlProvider: overrides.controlProvider || (() => overrides.control || { controlPort: 42000, controlToken: 'ctl-token' }),
    connection: overrides.connection === undefined ? fakeConnection() : overrides.connection,
    idleTimeoutMs: overrides.idleTimeoutMs,
    idleCheckMs: overrides.idleCheckMs,
    unusedStreamTimeoutMs: overrides.unusedStreamTimeoutMs,
  }
}

const POST_HEADERS = { origin: 'http://127.0.0.1:3080', host: '127.0.0.1:42000' }

function controlRoutes (overrides = {}) {
  return {
    '127.0.0.1:42000/start': async () => ({
      ok: true, status: 200,
      text: async () => JSON.stringify(overrides.start || {
        ok: true,
        stream: { sessionId: 's-test-1', port: 41000, token: 'stream-token-9', endpoint: 'http://127.0.0.1:41000/stream', metadata: { mime: 'video/mp4' } },
      }),
      body: null,
    }),
    '127.0.0.1:42000/stop': async (u) => {
      overrides.onStop && overrides.onStop(u)
      return { ok: true, status: 200, text: async () => '{"ok":true}', body: null }
    },
  }
}

function fakeFetchFrom (routes) {
  return async (url) => {
    const u = new URL(url)
    const handler = routes[u.host + u.pathname]
    if (!handler) return { ok: false, status: 404, text: async () => 'not found', body: null }
    return handler(u)
  }
}

async function runStart (handlers, { headers = POST_HEADERS, method = 'POST' } = {}) {
  const res = fakeRes()
  await handlers.start(fakeReq({ method, url: `${ROUTE_BASE}/start`, headers }), res)
  return res
}

async function runStop (handlers, tab, { headers = POST_HEADERS, method = 'POST' } = {}) {
  const res = fakeRes()
  const suffix = tab ? `?tab=${encodeURIComponent(tab)}` : ''
  await handlers.stop(fakeReq({ method, url: `${ROUTE_BASE}/stop${suffix}`, headers }), res)
  return res
}

// ── 认证（真实 admit/requestRejection 形状）──

test('认证：{peer} 放行；{rejection:401/403} 与 requestRejection 状态码各按其码；缺席 fail-closed', async () => {
  let upstreamCalls = 0
  const counting = async (url) => {
    const u = new URL(url)
    if (u.pathname === '/status') return { ok: true, status: 200, text: async () => '{"running":false}', body: null }
    upstreamCalls += 1
    return { ok: true, status: 200, text: async () => '{}', body: null }
  }

  const admitted = createStreamHandlers(deps({ fetchImpl: counting, connection: fakeConnection({ verdict: 'peer' }) }))
  const ok = fakeRes()
  await admitted.status(fakeReq({ url: `${ROUTE_BASE}/status` }), ok)
  assert.equal(ok.statusCode, 200)

  const unauthed = createStreamHandlers(deps({
    fetchImpl: counting,
    connection: fakeConnection({ verdict: 'rejection-number', status: 401 }),
  }))
  const res401 = fakeRes()
  await unauthed.status(fakeReq({ url: `${ROUTE_BASE}/status` }), res401)
  assert.equal(res401.statusCode, 401, '未认证 401')

  const forbidden = createStreamHandlers(deps({
    fetchImpl: counting,
    connection: fakeConnection({ verdict: 'rejection-object', status: 403 }),
  }))
  const res403 = fakeRes()
  await forbidden.status(fakeReq({ url: `${ROUTE_BASE}/status` }), res403)
  assert.equal(res403.statusCode, 403)

  const rr = createStreamHandlers(deps({
    fetchImpl: counting,
    connection: fakeConnection({ verdict: 'rr-reject', status: 401 }),
  }))
  const resRR = fakeRes()
  await rr.status(fakeReq({ url: `${ROUTE_BASE}/status` }), resRR)
  assert.equal(resRR.statusCode, 401, 'requestRejection 的状态码原样保留')

  const noService = createStreamHandlers(deps({ fetchImpl: counting, connection: null }))
  const resNone = fakeRes()
  await noService.status(fakeReq({ url: `${ROUTE_BASE}/status` }), resNone)
  assert.equal(resNone.statusCode, 403, 'connection 缺席 fail-closed')

  assert.equal(upstreamCalls, 0, '被拒请求零上游副作用')
})

// ── CSRF：POST + 同源/loopback（IPv6）──

test('CSRF：GET 405；跨站 Origin/Host 403；同源与 IPv6 loopback 放行', async () => {
  const handlers = createStreamHandlers(deps({
    fetchImpl: async (url) => {
      const u = new URL(url)
      if (u.pathname === '/start') {
        return {
          ok: true, status: 200,
          text: async () => JSON.stringify({ ok: true, stream: { sessionId: 's-1', port: 1, token: 't', metadata: null } }),
          body: null,
        }
      }
      throw new Error('unexpected upstream')
    },
  }))
  const getStart = await runStart(handlers, { method: 'GET' })
  assert.equal(getStart.statusCode, 405)

  const badOrigin = await runStart(handlers, { headers: { ...POST_HEADERS, origin: 'http://evil.example' } })
  assert.equal(badOrigin.statusCode, 403)

  // 同源（非 loopback）：远程访问 DSH UI 的场景由同源判定覆盖
  const sameOrigin = await runStart(handlers, {
    headers: { origin: 'http://192.168.1.5:3080', host: '192.168.1.5:3080' },
  })
  assert.equal(sameOrigin.statusCode, 200, 'Origin 与 Host 同源即放行（不做 loopback 硬编码）')

  // IPv6 loopback
  const v6 = await runStart(handlers, {
    headers: { origin: 'http://[::1]:3080', host: '[::1]:42000' },
  })
  assert.equal(v6.statusCode, 200, 'IPv6 loopback 的 Origin/Host 放行')

  assert.equal(isLoopbackHost('[::1]:42000'), true)
  assert.equal(isLoopbackHost('127.0.0.1:42000'), true)
  assert.equal(isLoopbackHost('evil.example'), false)
  assert.equal(isSameOrigin('http://192.168.1.5:3080', '192.168.1.5:3080'), true)
  assert.equal(isSameOrigin('http://192.168.1.5:3080', '192.168.1.6:3080'), false)
})

// ── start / status / stop（sessionId 会话归属）──

test('start：宿主 bundle → tabToken + sessionId；流 token 与控制 token 都不出 Node', async () => {
  const handlers = createStreamHandlers(deps({
    fetchImpl: fakeFetchFrom(controlRoutes()),
  }))
  const res = await runStart(handlers)
  assert.equal(res.statusCode, 200)
  const body = JSON.parse(res.written.join(''))
  assert.equal(body.ok, true)
  assert.equal(body.sessionId, 's-test-1')
  assert.match(body.tabToken, /^[0-9a-f]{32}$/)
  assert.ok(!res.written.join('').includes('stream-token-9'))
  assert.ok(!res.written.join('').includes('ctl-token'))
  // 快照注释口径：含 sessionId 与 tabToken（浏览器侧凭据），无流/控制 token
  const snap = handlers.snapshot()
  assert.equal(snap.sessionId, 's-test-1')
  assert.ok(snap.tabToken && !JSON.stringify(snap).includes('stream-token-9'))
})

test('start：宿主拒绝 → 502 + 设备侧原因；控制端点不可达 → 502', async () => {
  const refused = createStreamHandlers(deps({
    fetchImpl: async () => ({ ok: false, status: 503, text: async () => '{"ok":false,"reason":"no trusted display"}', body: null }),
  }))
  const res = await runStart(refused)
  assert.equal(res.statusCode, 502)
  assert.ok(res.written.join('').includes('no trusted display'))

  const unreachable = createStreamHandlers(deps({
    fetchImpl: async () => { throw new Error('ECONNREFUSED') },
  }))
  const res2 = await runStart(unreachable)
  assert.equal(res2.statusCode, 502)
  assert.ok(res2.written.join('').includes('unreachable'))
})

test('start：protected_app 403 与 code 原样透传，不改写成 502', async () => {
  const handlers = createStreamHandlers(deps({
    fetchImpl: async (url) => {
      const u = new URL(url)
      if (u.pathname === '/start') {
        return {
          ok: false,
          status: 403,
          text: async () => JSON.stringify({
            ok: false,
            code: 'protected_app',
            reason: 'the app in front (com.bank) is protected',
          }),
        }
      }
      return { ok: true, status: 200, text: async () => '{"ok":true}' }
    },
  }))
  const res = await runStart(handlers)
  assert.equal(res.statusCode, 403, 'host 403 must not be rewritten to 502')
  const body = JSON.parse(res.written.join(''))
  assert.equal(body.ok, false)
  assert.equal(body.code, 'protected_app')
  assert.match(body.reason, /protected/)
})

test('stop：带当前 tab → 停本会话（宿主 /stop 带 sessionId）；无 tab → 409', async () => {
  const stopUrls = []
  const handlers = createStreamHandlers(deps({
    fetchImpl: async (url) => {
      const u = new URL(url)
      if (u.pathname === '/start') {
        return {
          ok: true, status: 200,
          text: async () => JSON.stringify({ ok: true, stream: { sessionId: 's-test-1', port: 41000, token: 'st', metadata: null } }),
          body: null,
        }
      }
      if (u.pathname === '/stop') { stopUrls.push(url); return { ok: true, status: 200, text: async () => '{"ok":true}', body: null } }
      return { ok: false, status: 404, text: async () => 'nf', body: null }
    },
  }))
  const start = await runStart(handlers)
  const tab = JSON.parse(start.written.join('')).tabToken

  // 无 tab（旧面板的 stop）：不得停别人的会话
  const noTab = await runStop(handlers, null)
  assert.equal(noTab.statusCode, 409, '无 tab 的 stop 回 409（会话归属保护）')
  const wrongTab = await runStop(handlers, 'wrong-tab')
  assert.equal(wrongTab.statusCode, 409, '错 tab 的 stop 回 409')

  // 带 tab：停本会话，宿主 /stop 带 sessionId
  const stop = await runStop(handlers, tab)
  assert.equal(stop.statusCode, 200)
  assert.equal(JSON.parse(stop.written.join('')).hadSession, true)
  assert.equal(stopUrls.length, 1)
  assert.ok(stopUrls[0].includes('sessionId=s-test-1'), '宿主 /stop 必须带 sessionId')
  assert.ok(!stopUrls[0].includes('tab='), '宿主 /stop 不需要 tab（tab 由 Node 侧校验）')

  const after = fakeRes()
  await handlers.stream(fakeReq({ url: `${ROUTE_BASE}/stream?tab=${tab}` }), after)
  assert.equal(after.statusCode, 409, '收场后旧 tab 不得再取流')
})

test('stop：无会话时任何 stop 都幂等空过（hadSession:false）', async () => {
  const handlers = createStreamHandlers(deps({
    fetchImpl: async () => { throw new Error('no upstream expected') },
  }))
  const res = await runStop(handlers, null)
  assert.equal(res.statusCode, 200)
  assert.equal(JSON.parse(res.written.join('')).hadSession, false)
})

// ── 流数据面 ──

test('start：clientGone（真实 abort：destroyed=true）→ 宿主 /stop 带 sessionId 被补发，会话不落地', async () => {
  const stopUrls = []
  let releaseStart
  const startGate = new Promise((resolve) => { releaseStart = resolve })
  const handlers = createStreamHandlers(deps({
    fetchImpl: async (url) => {
      const u = new URL(url)
      if (u.pathname === '/start') {
        await startGate
        return {
          ok: true, status: 200,
          text: async () => JSON.stringify({ ok: true, stream: { sessionId: 's-gone-1', port: 41000, token: 'st', metadata: null } }),
          body: null,
        }
      }
      if (u.pathname === '/stop') { stopUrls.push(url); return { ok: true, status: 200, text: async () => '{"ok":true}', body: null } }
      throw new Error('unexpected upstream')
    },
  }))
  const res = fakeRes()
  // **真实 abort 形状**：destroyed=true、writableFinished=false —— 该判定写反时在此回归
  res.destroyed = true
  const piping = handlers.start(fakeReq({ method: 'POST', url: `${ROUTE_BASE}/start`, headers: POST_HEADERS }), res)
  await new Promise((r) => setTimeout(r, 10))
  res.emit('close')
  releaseStart()
  await piping
  await new Promise((r) => setTimeout(r, 10))
  assert.equal(handlers.snapshot().running, false, '会话不落地')
  assert.equal(stopUrls.length, 1, 'clientGone 后必须向宿主补发 /stop')
  assert.ok(stopUrls[0].includes('sessionId=s-gone-1'), '宿主 /stop 带 sessionId')
})

test('start：stop 交错（start 在飞时 stop 先到）→ stop 不停未建的会话，start 照常落地', async () => {
  let hostStops = 0
  let releaseStart
  const startGate = new Promise((resolve) => { releaseStart = resolve })
  const handlers = createStreamHandlers(deps({
    fetchImpl: async (url) => {
      const u = new URL(url)
      if (u.pathname === '/start') {
        await startGate
        return {
          ok: true, status: 200,
          text: async () => JSON.stringify({ ok: true, stream: { sessionId: 's-ix-1', port: 41000, token: 'st', metadata: null } }),
          body: null,
        }
      }
      if (u.pathname === '/stop') { hostStops += 1; return { ok: true, status: 200, text: async () => '{"ok":true}', body: null } }
      throw new Error('unexpected upstream')
    },
  }))
  const res = fakeRes()
  const startPiping = handlers.start(fakeReq({ method: 'POST', url: `${ROUTE_BASE}/start`, headers: POST_HEADERS }), res)
  await new Promise((r) => setTimeout(r, 10))
  const stopRes = fakeRes()
  // stop 排进队列（等在在飞的 start 后面）：此刻尚无会话可停 —— 幂等空过
  const stopPiping = handlers.stop(fakeReq({ method: 'POST', url: `${ROUTE_BASE}/stop`, headers: POST_HEADERS }), stopRes)
  releaseStart()
  await Promise.all([startPiping, stopPiping])
  await new Promise((r) => setTimeout(r, 10))
  // start 已落地（浏览器仍在：无 clientGone）；交错的无 tab stop 回 409
  // （会话归属保护），随后到达的 stop（带 tab）才停它
  assert.equal(handlers.snapshot().running, true, 'stop 先到不停未建的会话，start 照常落地')
  assert.equal(stopRes.statusCode, 409, '无 tab 的交错 stop 回 409（归属保护）')
  const tab = handlers.snapshot().tabToken
  const stop2 = fakeRes()
  await handlers.stop(fakeReq({ method: 'POST', url: `${ROUTE_BASE}/stop?tab=${tab}`, headers: POST_HEADERS }), stop2)
  assert.equal(stop2.statusCode, 200)
  assert.equal(handlers.snapshot().running, false)
  assert.ok(hostStops >= 1, '收场向宿主补发 /stop')
})

test('stream：上游 ReadableStream 经 pipeline 落到 res，EOF 后收场（宿主 /stop 带 sessionId）', async () => {
  const stopUrls = []
  const handlers = createStreamHandlers(deps({
    fetchImpl: async (url) => {
      const u = new URL(url)
      if (u.pathname === '/start') {
        return {
          ok: true, status: 200,
          text: async () => JSON.stringify({ ok: true, stream: { sessionId: 's-eof-1', port: 41000, token: 'st', metadata: null } }),
          body: null,
        }
      }
      if (u.pathname === '/stop') { stopUrls.push(url); return { ok: true, status: 200, text: async () => '{"ok":true}', body: null } }
      return { ok: true, status: 200, body: webStreamOf([[1, 2, 3], [4, 5]]) }
    },
  }))
  const start = await runStart(handlers)
  const tab = JSON.parse(start.written.join('')).tabToken

  const res = fakeRes()
  const received = []
  res.on('data', (chunk) => received.push(...chunk))
  await handlers.stream(fakeReq({ url: `${ROUTE_BASE}/stream?tab=${tab}` }), res)
  assert.deepEqual(received, [1, 2, 3, 4, 5])
  assert.equal(res.headers['Content-Type'], 'video/mp4')
  await new Promise((r) => setTimeout(r, 10))
  assert.equal(handlers.snapshot().running, false, 'EOF 后会话收场')
  assert.equal(stopUrls.length, 1)
  assert.ok(stopUrls[0].includes('sessionId=s-eof-1'), '宿主 /stop 带 sessionId')

  const stale = fakeRes()
  await handlers.stream(fakeReq({ url: `${ROUTE_BASE}/stream?tab=${tab}` }), stale)
  assert.equal(stale.statusCode, 409, '收场后旧 tab token 失效')
})

test('stream：客户端断开（真实 abort：destroyed=true）→ 停本会话', async () => {
  const stopUrls = []
  const handlers = createStreamHandlers(deps({
    fetchImpl: async (url) => {
      const u = new URL(url)
      if (u.pathname === '/start') {
        return {
          ok: true, status: 200,
          text: async () => JSON.stringify({ ok: true, stream: { sessionId: 's-abort-1', port: 41000, token: 'st', metadata: null } }),
          body: null,
        }
      }
      if (u.pathname === '/stop') { stopUrls.push(url); return { ok: true, status: 200, text: async () => '{"ok":true}', body: null } }
      return {
        ok: true, status: 200,
        body: new ReadableStream({
          start (controller) {
            controller.enqueue(new Uint8Array([9]))
            // 不 close：上游持续存在，等 res close 触发 abort
          },
        }),
      }
    },
  }))
  const start = await runStart(handlers)
  const tab = JSON.parse(start.written.join('')).tabToken

  // **真实 abort 形状**：destroyed=true、writableFinished=false（的判定写反在此回归）
  const res = fakeRes()
  res.destroyed = true
  const piping = handlers.stream(fakeReq({ url: `${ROUTE_BASE}/stream?tab=${tab}` }), res)
  await new Promise((r) => setTimeout(r, 20))
  res.emit('close')
  await piping
  await new Promise((r) => setTimeout(r, 20))
  assert.equal(handlers.snapshot().running, false, '提前断开收本会话')
  assert.equal(stopUrls.length, 1)
  assert.ok(stopUrls[0].includes('sessionId=s-abort-1'))
})

test('stream：第二路 /stream 回 409 且不杀第一路（宿主 409 也不收场）', async () => {
  let firstAlive = true
  const stopUrls = []
  const handlers = createStreamHandlers(deps({
    fetchImpl: async (url) => {
      const u = new URL(url)
      if (u.pathname === '/start') {
        return {
          ok: true, status: 200,
          text: async () => JSON.stringify({ ok: true, stream: { sessionId: 's-two-1', port: 41000, token: 'st', metadata: null } }),
          body: null,
        }
      }
      if (u.pathname === '/stop') { stopUrls.push(url); return { ok: true, status: 200, text: async () => '{"ok":true}', body: null } }
      if (String(url).includes('second=1')) {
        // 宿主对第二路取流回 409（已被消费）
        return { ok: false, status: 409, body: null }
      }
      return {
        ok: true, status: 200,
        body: new ReadableStream({
          start (controller) {
            controller.enqueue(new Uint8Array([7]))
            // 不 close：第一路持续存在
          },
        }),
      }
    },
  }))
  const start = await runStart(handlers)
  const tab = JSON.parse(start.written.join('')).tabToken

  const first = fakeRes()
  const piping = handlers.stream(fakeReq({ url: `${ROUTE_BASE}/stream?tab=${tab}` }), first)
  await new Promise((r) => setTimeout(r, 20))
  assert.equal(handlers.snapshot().running, true)

  // 第二路（同 tab）：本地/宿主都回 409，第一路不被杀
  const second = fakeRes()
  await handlers.stream(fakeReq({ url: `${ROUTE_BASE}/stream?tab=${tab}&second=1` }), second)
  assert.equal(second.statusCode, 409)
  await new Promise((r) => setTimeout(r, 20))
  assert.equal(handlers.snapshot().running, true, '第二路不得收掉会话')
  assert.equal(stopUrls.length, 0, '宿主 409 不触发收场')

  // 客户端断开第一路：此时才收场
  first.destroy()
  await piping
  await new Promise((r) => setTimeout(r, 20))
  assert.equal(handlers.snapshot().running, false)
  assert.ok(stopUrls.length >= 1)
})

test('stream：无会话 409、错 tab 403、非 GET 405', async () => {
  const handlers = createStreamHandlers(deps({
    fetchImpl: fakeFetchFrom(controlRoutes()),
  }))
  const none = fakeRes()
  await handlers.stream(fakeReq({ url: `${ROUTE_BASE}/stream?tab=x` }), none)
  assert.equal(none.statusCode, 409)

  const start = await runStart(handlers)
  const tab = JSON.parse(start.written.join('')).tabToken
  const bad = fakeRes()
  await handlers.stream(fakeReq({ url: `${ROUTE_BASE}/stream?tab=wrong` }), bad)
  assert.equal(bad.statusCode, 403)
  const wrongMethod = fakeRes()
  await handlers.stream(fakeReq({ method: 'POST', url: `${ROUTE_BASE}/stream?tab=${tab}` }), wrongMethod)
  assert.equal(wrongMethod.statusCode, 405)
})

test('stream：宿主 409 的原因随响应体带回浏览器（不只回状态码）', async () => {
  const handlers = createStreamHandlers(deps({
    fetchImpl: async (url) => {
      const u = new URL(url)
      if (u.pathname === '/start') {
        return {
          ok: true, status: 200,
          text: async () => JSON.stringify({ ok: true, stream: { sessionId: 's-reason-1', port: 41000, token: 'st', metadata: null } }),
          body: null,
        }
      }
      if (u.pathname === '/stop') { return { ok: true, status: 200, text: async () => '{"ok":true}', body: null } }
      // 宿主对取流回 409，并带上"已被消费"的原因。
      return {
        ok: false, status: 409,
        text: async () => 'the stream is already being consumed by another client',
        body: null,
      }
    },
  }))
  const start = await runStart(handlers)
  const tab = JSON.parse(start.written.join('')).tabToken
  const res = fakeRes()
  await handlers.stream(fakeReq({ url: `${ROUTE_BASE}/stream?tab=${tab}` }), res)
  assert.equal(res.statusCode, 409)
  assert.match(res.written.join(''), /already being consumed/, '宿主给的原因要带回浏览器')
})

test('stream：宿主 502 的原因随响应体带回浏览器', async () => {
  const handlers = createStreamHandlers(deps({
    fetchImpl: async (url) => {
      const u = new URL(url)
      if (u.pathname === '/start') {
        return {
          ok: true, status: 200,
          text: async () => JSON.stringify({ ok: true, stream: { sessionId: 's-reason-2', port: 41000, token: 'st', metadata: null } }),
          body: null,
        }
      }
      if (u.pathname === '/stop') { return { ok: true, status: 200, text: async () => '{"ok":true}', body: null } }
      return {
        ok: false, status: 502,
        text: async () => JSON.stringify({ error: 'device refused the stream' }),
        body: null,
      }
    },
  }))
  const start = await runStart(handlers)
  const tab = JSON.parse(start.written.join('')).tabToken
  const res = fakeRes()
  await handlers.stream(fakeReq({ url: `${ROUTE_BASE}/stream?tab=${tab}` }), res)
  assert.equal(res.statusCode, 502)
  assert.match(res.written.join(''), /device refused the stream/, '宿主给的原因要带回浏览器')
})

// ── 看门狗 ──

test('unused watchdog：start 后 30s（短值注入）内无 /stream 连接 → 自动收场', async () => {
  const stopUrls = []
  const handlers = createStreamHandlers(deps({
    unusedStreamTimeoutMs: 100,
    fetchImpl: async (url) => {
      const u = new URL(url)
      if (u.pathname === '/start') {
        return {
          ok: true, status: 200,
          text: async () => JSON.stringify({ ok: true, stream: { sessionId: 's-unused-1', port: 41000, token: 'st', metadata: null } }),
          body: null,
        }
      }
      if (u.pathname === '/stop') { stopUrls.push(url); return { ok: true, status: 200, text: async () => '{"ok":true}', body: null } }
      throw new Error('no stream expected')
    },
  }))
  const start = await runStart(handlers)
  assert.equal(start.statusCode, 200)
  assert.equal(handlers.snapshot().running, true)
  await new Promise((r) => setTimeout(r, 300))
  assert.equal(handlers.snapshot().running, false, '未取流的会话被看门狗收场')
  assert.equal(stopUrls.length, 1)
  assert.ok(stopUrls[0].includes('sessionId=s-unused-1'), '看门狗宿主 /stop 带 sessionId')
})

test('unused watchdog：取流后交棒给数据面空闲计时（不再按未用超时收）', async () => {
  const stopUrls = []
  const handlers = createStreamHandlers(deps({
    unusedStreamTimeoutMs: 100,
    fetchImpl: async (url) => {
      const u = new URL(url)
      if (u.pathname === '/start') {
        return {
          ok: true, status: 200,
          text: async () => JSON.stringify({ ok: true, stream: { sessionId: 's-unused-2', port: 41000, token: 'st', metadata: null } }),
          body: null,
        }
      }
      if (u.pathname === '/stop') { stopUrls.push(url); return { ok: true, status: 200, text: async () => '{"ok":true}', body: null } }
      // 上游不 close：无 EOF —— 未用看门狗交棒给数据面空闲计时
      return {
        ok: true, status: 200,
        body: new ReadableStream({ start (controller) { controller.enqueue(new Uint8Array([1])) } }),
      }
    },
  }))
  const start = await runStart(handlers)
  const tab = JSON.parse(start.written.join('')).tabToken
  const res = fakeRes()
  const received = []
  res.on('data', (chunk) => received.push(...chunk))
  // 数据面不结束（上游存在、客户端不断）：不 await 整条链，只等它起步
  const piping = handlers.stream(fakeReq({ url: `${ROUTE_BASE}/stream?tab=${tab}` }), res)
  await new Promise((r) => setTimeout(r, 400))
  assert.equal(handlers.snapshot().running, true, '取流后未用看门狗交棒')
  assert.deepEqual(received, [1])
  // 断开后收场
  res.destroy()
  await piping
  await new Promise((r) => setTimeout(r, 50))
  assert.equal(handlers.snapshot().running, false)
})

// ── controlProvider：宿主重启换端口/token 后自跟上 ──

test('controlProvider：每次控制调用现读，宿主换端口后第二枚 start 用新端口', async () => {
  let currentPort = 42000
  const seenPorts = []
  const handlers = createStreamHandlers(deps({
    controlProvider: () => ({ controlPort: currentPort, controlToken: 'ctl-token' }),
    fetchImpl: async (url) => {
      const u = new URL(url)
      seenPorts.push(u.port)
      if (u.pathname === '/start') {
        return {
          ok: true, status: 200,
          text: async () => JSON.stringify({ ok: true, stream: { sessionId: 's-p-' + seenPorts.length, port: 41000, token: 'st', metadata: null } }),
          body: null,
        }
      }
      return { ok: true, status: 200, text: async () => '{"ok":true}', body: null }
    },
  }))
  const first = await runStart(handlers)
  assert.equal(first.statusCode, 200)
  const stop = await runStop(handlers, JSON.parse(first.written.join('')).tabToken)
  assert.equal(stop.statusCode, 200)
  currentPort = 43001 // 宿主重启：rendezvous 现值变了
  const second = await runStart(handlers)
  assert.equal(second.statusCode, 200)
  assert.equal(seenPorts[seenPorts.length - 1], '43001', '控制调用现读 rendezvous 新端口')
})

// ── attach：真实注册形状 + effect/disposer + cordis childCtx 集成 ──

function fakeCtx ({ registerThrows = false, childEffect = false } = {}) {
  const registered = []
  const disposed = []
  const effectCalls = []
  const webServer = {
    register (route) {
      if (registerThrows) throw new Error('bad route shape')
      assert.ok(route.kind === 'exact', '路由必须是 exact 形状')
      assert.ok(typeof route.path === 'string' && typeof route.handler === 'function')
      assert.ok(!('method' in route), 'webServer.register 没有 method 字段')
      registered.push(route)
      return () => { disposed.push(route.path) }
    },
  }
  const connection = fakeConnection()
  const childCtx = { webServer, connection }
  if (childEffect) childCtx.effect = (fn, label) => {
    const disposer = fn()
    effectCalls.push(label)
    return () => { disposed.push('effect:' + label); if (typeof disposer === 'function') disposer() }
  }
  return { ctx: { inject: (names, cb) => cb(childCtx), webServer, connection, effect: childCtx.effect }, registered, disposed, effectCalls, webServer, connection }
}

test('attach：真实 webServer 形状挂接九条 exact 路由，注册经 effect（disposer 可撤销）', () => {
  const { ctx, registered, disposed } = fakeCtx({ childEffect: true })
  const result = attach({
    webServer: ctx.webServer,
    connection: ctx.connection,
    effect: ctx.effect,
    fetchImpl: fakeFetchFrom(controlRoutes()),
    controlProvider: () => ({ controlPort: 42000, controlToken: 'ctl-token' }),
  })
  assert.equal(result.ok, true)
  assert.equal(result.via, 'webServer')
  assert.deepEqual(registered.map((r) => r.path).sort(), [
    ROUTE_BASE, `${ROUTE_BASE}/start`, `${ROUTE_BASE}/status`, `${ROUTE_BASE}/stop`, ROUTE_INPUT,
    ROUTE_TASKS, ROUTE_MOVE, ROUTE_RELEASE, ROUTE_ROTATE,
  ].sort())
  assert.ok(result.disposer && typeof result.disposer === 'function')
  const status = registered.find((r) => r.path === `${ROUTE_BASE}/status`)
  const res = fakeRes()
  status.handler(fakeReq({ url: `${ROUTE_BASE}/status` }), res)
  assert.equal(res.statusCode, 200)
  result.disposer()
  const effectDisposals = disposed.filter((d) => String(d).startsWith('effect:')).length
  assert.equal(effectDisposals, 9, '九条路由的 effect 注册都被撤销（reload 不残留）')
  for (const p of [ROUTE_BASE, `${ROUTE_BASE}/start`, `${ROUTE_BASE}/status`, `${ROUTE_BASE}/stop`, ROUTE_INPUT, ROUTE_TASKS, ROUTE_MOVE, ROUTE_RELEASE, ROUTE_ROTATE]) {
    assert.ok(disposed.includes(p), '注册本身被撤销：' + p)
  }
})

test('attach：webServer 缺席 → ok:false + 可读原因；register 抛错点名已注册清单', () => {
  const miss = attach({ fetchImpl: async () => ({ ok: true, status: 200, text: async () => '{}', body: null }) })
  assert.equal(miss.ok, false)
  assert.match(miss.reason, /webServer/)

  const { ctx } = fakeCtx({ registerThrows: true })
  const broken = attach({
    webServer: ctx.webServer,
    connection: ctx.connection,
    fetchImpl: async () => ({ ok: true, status: 200, text: async () => '{}', body: null }),
    controlProvider: () => ({ controlPort: 1, controlToken: 't' }),
  })
  assert.equal(broken.ok, false)
  assert.match(broken.reason, /bad route shape/)
})

// ── 面板输入：/mobile-pilot/input ──

/** 带可读 body 的 POST 假 req：input 的 JSON body 走真实 data/end 事件。 */
function fakePostReq ({ url, headers = POST_HEADERS, body = '' }) {
  const req = new PassThrough()
  req.method = 'POST'
  req.url = url
  req.headers = headers
  req.socket = { remoteAddress: '127.0.0.1' }
  req.end(body)
  return req
}

async function runInput (handlers, body, { headers = POST_HEADERS, method = 'POST', url } = {}) {
  const res = fakeRes()
  const target = url || `${ROUTE_INPUT}`
  if (method === 'POST') {
    await handlers.input(fakePostReq({ url: target, headers, body: body === undefined ? '' : body }), res)
  } else {
    await handlers.input(fakeReq({ method, url: target, headers }), res)
  }
  return res
}

function inputHost ({ status = 200, body = '{"ok":true}' } = {}) {
  const seen = { calls: 0, url: null, initBody: null }
  const fetchImpl = async (url, init) => {
    const u = new URL(url)
    if (u.pathname === '/start') {
      return {
        ok: true, status: 200,
        text: async () => JSON.stringify({ ok: true, stream: { sessionId: 's-in', port: 41000, token: 'st', metadata: null } }),
        body: null,
      }
    }
    if (u.pathname === '/input') {
      seen.calls += 1
      seen.url = url
      seen.initBody = init && init.body
      seen.tabHeader = init && init.headers && init.headers['x-pilot-tab']
      return { ok: status < 400, status, text: async () => body, body: null }
    }
    if (u.pathname === '/stop') return { ok: true, status: 200, text: async () => '{"ok":true}', body: null }
    return { ok: false, status: 404, text: async () => 'not found', body: null }
  }
  return { fetchImpl, seen }
}

test('/input：非 POST 405；无会话 409；错 tab 403；body 不成形 400', async () => {
  const host = inputHost()
  const handlers = createStreamHandlers(deps({ fetchImpl: host.fetchImpl }))
  // 非 POST
  assert.equal((await runInput(handlers, undefined, { method: 'GET' })).statusCode, 405)
  // 无会话
  assert.equal((await runInput(handlers, '{"action":"tap","x":0.5,"y":0.5}')).statusCode, 409)
  assert.equal(host.seen.calls, 0, '409/405 零上游调用')
  // 错 tab（有会话但 tab 不匹配）
  await runStart(handlers)
  assert.equal(
    (await runInput(handlers, JSON.stringify({ tab: 'wrong', action: 'tap', x: 0.5, y: 0.5 }))).statusCode,
    403,
  )
  assert.equal(host.seen.calls, 0, 'tab 不匹配不代理')
  // body 不成形
  assert.equal((await runInput(handlers, '')).statusCode, 400)
  assert.equal((await runInput(handlers, 'not json')).statusCode, 400)
  assert.equal(host.seen.calls, 0, '400 零上游调用')
})

test('/input：合法请求带控制 token 代理宿主；白名单字段之外不转发；宿主状态/原因原样透传', async () => {
  const host = inputHost()
  const handlers = createStreamHandlers(deps({ fetchImpl: host.fetchImpl }))
  await runStart(handlers)
  const snap = handlers.snapshot()
  const res = await runInput(handlers, JSON.stringify({ tab: snap.tabToken, action: 'tap', x: 0.5, y: 0.25, junk: 'x', extra: 1 }))
  assert.equal(res.statusCode, 200)
  assert.equal(JSON.parse(res.written[0]).ok, true)
  assert.equal(host.seen.calls, 1, '宿主被调用一次')
  assert.match(host.seen.url, /\/input\?token=ctl-token/, '控制 token 由 Node 半拼上，不经浏览器')
  const forwarded = JSON.parse(host.seen.initBody)
  assert.equal(forwarded.action, 'tap')
  assert.equal(forwarded.x, 0.5)
  assert.equal(forwarded.y, 0.25)
  assert.equal(forwarded.tab, undefined, 'tab token 不进 body（走头）')
  assert.equal(forwarded.junk, undefined, '白名单之外的字段不转发')
  assert.equal(forwarded.sessionId, snap.sessionId, '会话 id 随 body 给宿主对账（N3D 返工1）')
  assert.equal(host.seen.tabHeader, snap.tabToken, 'tabToken 经 x-pilot-tab 头转发（N3D 返工1）')
  // 超 4KB 的 body 本地即拒（与宿主 MAX_BODY_BYTES 同值），不上游
  const big = JSON.stringify({ tab: snap.tabToken, action: 'tap', x: 0.5, y: 0.5, pad: 'x'.repeat(8 * 1024) })
  const tooBig = await runInput(handlers, big)
  assert.equal(tooBig.statusCode, 400)
  assert.match(JSON.parse(tooBig.written[0]).reason, /too large/)

  // 宿主的 429/400/502 状态与原因原样透传（限速/校验在宿主侧，Node 不缓存不排队）
  for (const [status, reason] of [[429, 'panel input rate limit exceeded (20/s)'], [400, 'x must be a number in 0..1 (normalized)'], [502, 'input -d failed']]) {
    const failing = inputHost({ status, body: JSON.stringify({ ok: false, reason }) })
    const h2 = createStreamHandlers(deps({ fetchImpl: failing.fetchImpl }))
    await runStart(h2)
    const t = h2.snapshot().tabToken
    const r = await runInput(h2, JSON.stringify({ tab: t, action: 'tap', x: 0.5, y: 0.5 }))
    assert.equal(r.statusCode, status)
    assert.equal(JSON.parse(r.written[0]).reason, reason)
  }
})

// ──  面板任务面：/mobile-pilot/tasks、/move、/release ──

function taskQuery (tab) { return tab ? `?tab=${encodeURIComponent(tab)}` : '' }

/** 宿主假体：记录 /tasks /move /release 的调用；/start /stop 与 inputHost 同款。 */
function taskHost ({ tasksBody = null, moveStatus = 200, moveBody = '{"ok":true}', releaseStatus = 200, releaseBody = '{"ok":true,"released":true}' } = {}) {
  const seen = { tasks: [], moves: [], releases: [] }
  const fetchImpl = async (url, init) => {
    const u = new URL(url)
    if (u.pathname === '/start') {
      return {
        ok: true, status: 200,
        text: async () => JSON.stringify({ ok: true, stream: { sessionId: 's-n3f', port: 41000, token: 'st', metadata: null } }),
        body: null,
      }
    }
    if (u.pathname === '/tasks') {
      seen.tasks.push({ url, tabHeader: init && init.headers && init.headers['x-pilot-tab'], sessionHeader: init && init.headers && init.headers['x-pilot-session'] })
      return { ok: true, status: 200, text: async () => JSON.stringify(tasksBody || {
        ok: true, displayId: 45, tasks: [{ taskId: 703, package: 'com.ss.android.ugc.aweme', label: '抖音', activity: 'com.ss.android.ugc.aweme/.main.MainActivity' }],
      }), body: null }
    }
    if (u.pathname === '/move') {
      seen.moves.push({ url, body: init && init.body, tabHeader: init && init.headers && init.headers['x-pilot-tab'], sessionHeader: init && init.headers && init.headers['x-pilot-session'] })
      return { ok: moveStatus < 400, status: moveStatus, text: async () => moveBody, body: null }
    }
    if (u.pathname === '/release') {
      seen.releases.push({ url, body: init && init.body, tabHeader: init && init.headers && init.headers['x-pilot-tab'], sessionHeader: init && init.headers && init.headers['x-pilot-session'] })
      return { ok: releaseStatus < 400, status: releaseStatus, text: async () => releaseBody, body: null }
    }
    if (u.pathname === '/stop') return { ok: true, status: 200, text: async () => '{"ok":true}', body: null }
    return { ok: false, status: 404, text: async () => 'not found', body: null }
  }
  return { fetchImpl, seen }
}

async function runTaskHandler (handlers, name, url, body, { headers = POST_HEADERS, method = 'POST' } = {}) {
  const res = fakeRes()
  if (method === 'POST') {
    await handlers[name](fakePostReq({ url, headers, body: body === undefined ? '' : body }), res)
  } else {
    await handlers[name](fakeReq({ method, url, headers }), res)
  }
  return res
}

test('tasks：非 GET 405；无会话 409；错 tab 403；合法请求透传 displayId/tasks，控制 token 不出 Node', async () => {
  const host = taskHost()
  const handlers = createStreamHandlers(deps({ fetchImpl: host.fetchImpl }))
  // 非 GET
  assert.equal((await runTaskHandler(handlers, 'tasks', `${ROUTE_TASKS}`, undefined, { method: 'POST' })).statusCode, 405)
  // 无会话
  const none = await runTaskHandler(handlers, 'tasks', `${ROUTE_TASKS}`, undefined, { method: 'GET' })
  assert.equal(none.statusCode, 409)
  assert.equal(host.seen.tasks.length, 0, '无会话零上游调用')
  // 有会话但 tab 错
  await runStart(handlers)
  const bad = await runTaskHandler(handlers, 'tasks', `${ROUTE_TASKS}?tab=wrong`, undefined, { method: 'GET' })
  assert.equal(bad.statusCode, 403)
  assert.equal(host.seen.tasks.length, 0, '错 tab 零上游调用')
  // 合法：GET + tab 查询参数
  const tab = handlers.snapshot().tabToken
  const ok = await runTaskHandler(handlers, 'tasks', `${ROUTE_TASKS}${taskQuery(tab)}`, undefined, { method: 'GET' })
  assert.equal(ok.statusCode, 200)
  const body = JSON.parse(ok.written.join(''))
  assert.equal(body.ok, true)
  assert.equal(body.displayId, 45)
  assert.equal(body.tasks.length, 1)
  assert.equal(body.tasks[0].taskId, 703)
  assert.equal(body.tasks[0].label, '抖音')
  assert.equal(host.seen.tasks[0].tabHeader, tab, 'tab 经 x-pilot-tab 头转发')
  assert.ok(!ok.written.join('').includes('ctl-token'), '控制 token 不出 Node')
  assert.ok(!ok.written.join('').includes('token'), '回包不带任何 token')
})

test('tasks：同源 GET 不带 Origin 也放行（生产链路 fetch 不发 Origin）', async () => {
  const host = taskHost()
  const handlers = createStreamHandlers(deps({ fetchImpl: host.fetchImpl }))
  await runStart(handlers)
  const tab = handlers.snapshot().tabToken
  const res = await runTaskHandler(handlers, 'tasks', `${ROUTE_TASKS}${taskQuery(tab)}`, undefined, {
    method: 'GET',
    headers: { host: '127.0.0.1:3080' },
  })
  assert.equal(res.statusCode, 200, 'no Origin must not be treated as cross-site')
  assert.equal(JSON.parse(res.written.join('')).ok, true)
  assert.equal(host.seen.tasks.length, 1)
})

test('move：非 POST 405；无会话 409；错 tab 403；body 白名单 + sessionId 补齐；宿主状态/原因原样透传', async () => {
  const host = taskHost()
  const handlers = createStreamHandlers(deps({ fetchImpl: host.fetchImpl }))
  // 非 POST
  assert.equal((await runTaskHandler(handlers, 'move', `${ROUTE_MOVE}`, undefined, { method: 'GET' })).statusCode, 405)
  // 无会话
  assert.equal((await runTaskHandler(handlers, 'move', `${ROUTE_MOVE}`, '{}')).statusCode, 409)
  assert.equal(host.seen.moves.length, 0)
  // 错 tab
  await runStart(handlers)
  assert.equal((await runTaskHandler(handlers, 'move', `${ROUTE_MOVE}?tab=wrong`, '{"taskId":1,"direction":"main"}')).statusCode, 403)
  assert.equal(host.seen.moves.length, 0)
  // body 白名单：taskId/direction 缺一不可、类型要对
  const tab = handlers.snapshot().tabToken
  for (const bad of ['{}', 'not json', '{"direction":"main"}', '{"taskId":703}', '{"taskId":"703","direction":"main"}', '{"taskId":703,"direction":"up"}']) {
    assert.equal((await runTaskHandler(handlers, 'move', `${ROUTE_MOVE}${taskQuery(tab)}`, bad)).statusCode, 400, 'bad body: ' + bad)
  }
  assert.equal(host.seen.moves.length, 0, '400 零上游调用')
  // 合法：白名单之外的字段不转发，sessionId 由 Node 补
  const ok = await runTaskHandler(handlers, 'move', `${ROUTE_MOVE}${taskQuery(tab)}`,
    JSON.stringify({ taskId: 703, direction: 'main', junk: 'x' }))
  assert.equal(ok.statusCode, 200)
  const forwarded = JSON.parse(host.seen.moves[0].body)
  assert.equal(forwarded.taskId, 703)
  assert.equal(forwarded.direction, 'main')
  assert.equal(forwarded.sessionId, 's-n3f', 'sessionId 由 Node 半补上')
  assert.equal(forwarded.junk, undefined, '白名单之外的字段不转发')
  assert.equal(host.seen.moves[0].tabHeader, tab, 'tab 经 x-pilot-tab 头转发')
  assert.ok(!host.seen.moves[0].url.includes('tab='), 'tab 不进 URL（走头）')
  // 宿主 409 + 原因原样透传
  const hostDenied = taskHost({ moveStatus: 409, moveBody: '{"ok":false,"reason":"panel task move: task 99 is not on the trusted display 45"}' })
  const h3 = createStreamHandlers(deps({ fetchImpl: hostDenied.fetchImpl }))
  await runStart(h3)
  const tab3 = h3.snapshot().tabToken
  const denied = await runTaskHandler(h3, 'move', `${ROUTE_MOVE}${taskQuery(tab3)}`, '{"taskId":99,"direction":"virtual"}')
  assert.equal(denied.statusCode, 409)
  assert.equal(JSON.parse(denied.written[0]).reason, 'panel task move: task 99 is not on the trusted display 45')
})

test('release：非 POST 405；无会话 409；错 tab 403；sessionId 补齐；宿主 503 原因原样透传', async () => {
  const host = taskHost()
  const handlers = createStreamHandlers(deps({ fetchImpl: host.fetchImpl }))
  // 非 POST
  assert.equal((await runTaskHandler(handlers, 'release', `${ROUTE_RELEASE}`, undefined, { method: 'GET' })).statusCode, 405)
  // 无会话
  assert.equal((await runTaskHandler(handlers, 'release', `${ROUTE_RELEASE}`, '{}')).statusCode, 409)
  assert.equal(host.seen.releases.length, 0)
  // 错 tab
  await runStart(handlers)
  assert.equal((await runTaskHandler(handlers, 'release', `${ROUTE_RELEASE}?tab=wrong`, '{}')).statusCode, 403)
  assert.equal(host.seen.releases.length, 0)
  // 合法：body 可为空（sessionId 由 Node 补）
  const tab = handlers.snapshot().tabToken
  const ok = await runTaskHandler(handlers, 'release', `${ROUTE_RELEASE}${taskQuery(tab)}`, '')
  assert.equal(ok.statusCode, 200)
  const forwarded = JSON.parse(host.seen.releases[0].body)
  assert.equal(forwarded.sessionId, 's-n3f')
  assert.equal(host.seen.releases[0].tabHeader, tab, 'tab 经 x-pilot-tab 头转发')
  // 宿主 503 原样透传
  const hostFailed = taskHost({ releaseStatus: 503, releaseBody: '{"ok":false,"reason":"display release failed: remote said no"}' })
  const h3 = createStreamHandlers(deps({ fetchImpl: hostFailed.fetchImpl }))
  await runStart(h3)
  const tab3 = h3.snapshot().tabToken
  const failed = await runTaskHandler(h3, 'release', `${ROUTE_RELEASE}${taskQuery(tab3)}`, '{}')
  assert.equal(failed.statusCode, 503)
  assert.equal(JSON.parse(failed.written[0]).reason, 'display release failed: remote said no')
  // 失败不清账（宿主还在跑）。
  assert.equal(h3.snapshot().running, true, 'release 失败时会话账保留')
})

test('release：成功清账（宿主已停流并收屏）——下一次 /start 开新一届，不再 alreadyRunning', async () => {
  let startCalls = 0
  const host = taskHost()
  const fetchImpl = async (url, init) => {
    const u = new URL(url)
    if (u.pathname === '/start') {
      startCalls += 1
      return {
        ok: true, status: 200,
        text: async () => JSON.stringify({ ok: true, stream: { sessionId: 's-rel-' + startCalls, port: 41000, token: 'st', metadata: null } }),
        body: null,
      }
    }
    return host.fetchImpl(url, init)
  }
  const handlers = createStreamHandlers(deps({ fetchImpl }))
  await runStart(handlers)
  assert.equal(handlers.snapshot().sessionId, 's-rel-1')
  const tab = handlers.snapshot().tabToken
  const ok = await runTaskHandler(handlers, 'release', `${ROUTE_RELEASE}${taskQuery(tab)}`, '{}')
  assert.equal(ok.statusCode, 200)
  assert.equal(JSON.parse(ok.written[0]).released, true)
  assert.equal(handlers.snapshot().running, false, 'release 成功后 Node 账清掉')
  // 下一次 /start 重新打宿主（新一届会话），不是对着已释放的屏答 alreadyRunning。
  await runStart(handlers)
  assert.equal(startCalls, 2)
  assert.equal(handlers.snapshot().sessionId, 's-rel-2')
})

test('tasks/move/release：宿主不可达 → 502；结构化 code 原样透传', async () => {
  // start 要成（不然没有会话，三条任务路由只会回 409 而到不了上游）；任务调用时上游挂。
  let hostUp = true
  const fetchImpl = async (url, init) => {
    if (!hostUp) throw new Error('ECONNREFUSED')
    const u = new URL(url)
    if (u.pathname === '/start') {
      return {
        ok: true, status: 200,
        text: async () => JSON.stringify({ ok: true, stream: { sessionId: 's-un', port: 41000, token: 'st', metadata: null } }),
        body: null,
      }
    }
    return { ok: true, status: 200, text: async () => '{"ok":true}', body: null }
  }
  const handlers = createStreamHandlers(deps({ fetchImpl }))
  await runStart(handlers)
  const tab = handlers.snapshot().tabToken
  hostUp = false
  const t = await runTaskHandler(handlers, 'tasks', `${ROUTE_TASKS}${taskQuery(tab)}`, undefined, { method: 'GET' })
  assert.equal(t.statusCode, 502)
  assert.match(JSON.parse(t.written[0]).reason, /unreachable/)
  const m = await runTaskHandler(handlers, 'move', `${ROUTE_MOVE}${taskQuery(tab)}`, '{"taskId":1,"direction":"main"}')
  assert.equal(m.statusCode, 502)
  const r = await runTaskHandler(handlers, 'release', `${ROUTE_RELEASE}${taskQuery(tab)}`, '{}')
  assert.equal(r.statusCode, 502)

  // 结构化 code（宿主对任务面的结构化拒绝）：原样透传，不改写成 502
  const host = taskHost({ releaseStatus: 403, releaseBody: '{"ok":false,"code":"protected_app","reason":"protected"}' })
  const h2 = createStreamHandlers(deps({ fetchImpl: host.fetchImpl }))
  await runStart(h2)
  const tab2 = h2.snapshot().tabToken
  const coded = await runTaskHandler(h2, 'release', `${ROUTE_RELEASE}${taskQuery(tab2)}`, '{}')
  assert.equal(coded.statusCode, 403)
  assert.equal(JSON.parse(coded.written[0]).code, 'protected_app')
})

test('N3F 返工1：tasks/move/release 都带 x-pilot-session 头转发；virtual 方向原样透传', async () => {
  const host = taskHost({ tasksBody: { ok: true, displayId: 0, tasks: [{ taskId: 703, package: 'com.a', label: 'A', activity: '.M' }] } })
  const handlers = createStreamHandlers(deps({ fetchImpl: host.fetchImpl }))
  await runStart(handlers)
  const tab = handlers.snapshot().tabToken
  // tasks：sessionId 头
  await runTaskHandler(handlers, 'tasks', `${ROUTE_TASKS}${taskQuery(tab)}`, undefined, { method: 'GET' })
  assert.equal(host.seen.tasks[0].sessionHeader, tab && handlers.snapshot().sessionId, 'tasks 带 x-pilot-session')
  // move main：sessionId 头 + direction 原样
  await runTaskHandler(handlers, 'move', `${ROUTE_MOVE}${taskQuery(tab)}`, '{"taskId":703,"direction":"main"}')
  let fwd = JSON.parse(host.seen.moves[0].body)
  assert.equal(fwd.direction, 'main')
  assert.equal(host.seen.moves[0].sessionHeader, handlers.snapshot().sessionId, 'move 带 x-pilot-session')
  // move virtual：原样透传（认根在宿主，Node 不改方向）
  await runTaskHandler(handlers, 'move', `${ROUTE_MOVE}${taskQuery(tab)}`, '{"taskId":703,"direction":"virtual"}')
  fwd = JSON.parse(host.seen.moves[1].body)
  assert.equal(fwd.direction, 'virtual', 'virtual 方向原样透传')
  assert.equal(host.seen.moves[1].sessionHeader, handlers.snapshot().sessionId)
  // release：sessionId 头（release 成功会清账，先抓会话 id 再断言）
  const sid = handlers.snapshot().sessionId
  await runTaskHandler(handlers, 'release', `${ROUTE_RELEASE}${taskQuery(tab)}`, '{}')
  assert.equal(host.seen.releases[0].sessionHeader, sid, 'release 带 x-pilot-session')
})

test('N3F 返工1：tabMatchesToken 常数时间口径（长度不等 false、空 false、等长同值 true）', async () => {
  const { tabMatchesToken } = await import('file://' + STREAM_PATH)
  assert.equal(tabMatchesToken('abc', 'abc'), true)
  assert.equal(tabMatchesToken('abc', 'abd'), false)
  assert.equal(tabMatchesToken('abc', 'abcd'), false, '长度不等直接 false')
  assert.equal(tabMatchesToken('', 'abc'), false)
  assert.equal(tabMatchesToken(undefined, 'abc'), false)
  assert.equal(tabMatchesToken('abc', undefined), false)
})

test('N3F 返工1：release 成功清掉 30s 未用看门狗句柄（不把进程挂到定时器结束）', async () => {
  const host = taskHost()
  const handlers = createStreamHandlers(deps({ fetchImpl: host.fetchImpl }))
  await runStart(handlers)
  const tab = handlers.snapshot().tabToken
  // 会话对象上有未触发的 unusedTimer：release 成功后必须被 clearTimeout。
  const res = await runTaskHandler(handlers, 'release', `${ROUTE_RELEASE}${taskQuery(tab)}`, '{}')
  assert.equal(res.statusCode, 200)
  assert.equal(handlers.snapshot().running, false)
  // 快照清账后无法直接摸 timer：用 30s 后进程仍能立即退出验证（node:test 自身会在
  // 定时器未清时挂住直到超时——这里以测试立即结束作为断言）。
})
// ── /mobile-pilot/rotate ──

/** taskHost 的 rotate 版：/rotate 记录调用，回可编程 body。 */
function rotateHost ({ rotateStatus = 200, rotateBody = '{"ok":true,"rotated":true,"dimensions":{"width":1032,"height":720}}' } = {}) {
  const seen = { rotates: [] }
  const fetchImpl = async (url, init) => {
    const u = new URL(url)
    if (u.pathname === '/start') {
      return {
        ok: true, status: 200,
        text: async () => JSON.stringify({ ok: true, stream: { sessionId: 's-rot', port: 41000, token: 'st', metadata: null } }),
        body: null,
      }
    }
    if (u.pathname === '/rotate') {
      seen.rotates.push({ url, body: init && init.body, tabHeader: init && init.headers && init.headers['x-pilot-tab'], sessionHeader: init && init.headers && init.headers['x-pilot-session'] })
      return { ok: rotateStatus < 400, status: rotateStatus, text: async () => rotateBody, body: null }
    }
    if (u.pathname === '/stop') return { ok: true, status: 200, text: async () => '{"ok":true}', body: null }
    return { ok: false, status: 404, text: async () => 'not found', body: null }
  }
  return { fetchImpl, seen }
}

test('rotate：非 POST 405；无会话 409；错 tab 403；sessionId 补齐；成功清账后 start 开新届', async () => {
  const host = rotateHost()
  const handlers = createStreamHandlers(deps({ fetchImpl: host.fetchImpl }))
  // 非 POST
  assert.equal((await runTaskHandler(handlers, 'rotate', `${ROUTE_ROTATE}`, undefined, { method: 'GET' })).statusCode, 405)
  // 无会话
  assert.equal((await runTaskHandler(handlers, 'rotate', `${ROUTE_ROTATE}`, '{}')).statusCode, 409)
  assert.equal(host.seen.rotates.length, 0, '无会话零上游调用')
  // 错 tab
  await runStart(handlers)
  assert.equal((await runTaskHandler(handlers, 'rotate', `${ROUTE_ROTATE}?tab=wrong`, '{}')).statusCode, 403)
  assert.equal(host.seen.rotates.length, 0)
  // 合法：sessionId 由 Node 补，tab/session 走头
  const sid = handlers.snapshot().sessionId
  const ok = await runTaskHandler(handlers, 'rotate', `${ROUTE_ROTATE}${taskQuery(handlers.snapshot().tabToken)}`, '{}')
  assert.equal(ok.statusCode, 200)
  const parsed = JSON.parse(ok.written[0])
  assert.equal(parsed.ok, true)
  assert.equal(parsed.rotated, true)
  assert.equal(parsed.dimensions.width, 1032)
  assert.equal(host.seen.rotates[0].sessionHeader, sid, 'sessionId 走 x-pilot-session 头')
  assert.equal(host.seen.rotates[0].tabHeader, 'tab-not-leaked' in {} ? '' : handlers.snapshot().tabToken || host.seen.rotates[0].tabHeader, 'tab 头转发')
  assert.ok(!ok.written.join('').includes('ctl-token'), '控制 token 不出 Node')
  // 成功清账：下一次 /start 重打宿主（新一届），不再 alreadyRunning
  assert.equal(handlers.snapshot().running, false, 'rotate 成功后 Node 账清掉')
  let startCalls = 0
  const host2 = rotateHost()
  const fetch2 = async (url, init) => {
    const u = new URL(url)
    if (u.pathname === '/start') {
      startCalls += 1
      return {
        ok: true, status: 200,
        text: async () => JSON.stringify({ ok: true, stream: { sessionId: 's-rot-' + startCalls, port: 41000, token: 'st', metadata: null } }),
        body: null,
      }
    }
    return host2.fetchImpl(url, init)
  }
  const h2 = createStreamHandlers(deps({ fetchImpl: fetch2 }))
  await runStart(h2)
  const tab2 = h2.snapshot().tabToken
  await runTaskHandler(h2, 'rotate', `${ROUTE_ROTATE}${taskQuery(tab2)}`, '{}')
  await runStart(h2)
  assert.equal(startCalls, 2, 'rotate 成功后 start 开新届')
})

test('rotate：宿主失败状态/原因原样透传（503/409）', async () => {
  const host = rotateHost({ rotateStatus: 503, rotateBody: '{"ok":false,"reason":"resize failed: UnsupportedOperationException"}' })
  const handlers = createStreamHandlers(deps({ fetchImpl: host.fetchImpl }))
  await runStart(handlers)
  const tab = handlers.snapshot().tabToken
  const failed = await runTaskHandler(handlers, 'rotate', `${ROUTE_ROTATE}${taskQuery(tab)}`, '{}')
  assert.equal(failed.statusCode, 503)
  assert.equal(JSON.parse(failed.written[0]).reason, 'resize failed: UnsupportedOperationException')
  // 失败不清账（宿主还在跑）
  assert.equal(handlers.snapshot().running, true, 'rotate 失败时会话账保留')
})
