'use strict'

/**
 * Realtime stream bridge for @local/mobile-pilot.
 *
 * 职责与边界：
 * · 读 rendezvous 文件（`<pack>/run/stream-endpoint.json`）拿到宿主控制端点的
 * 端口与控制 token —— 文件仅属主可读，token 不进日志；**每次控制调用现读**
 * （宿主重启换端口/token 后 Node 自跟上，最后一次成功值作读不到时的兜底）；
 * · 挂到 DSH 0.2.2-rc.1 的 **`webServer`** 服务：`register({ kind:'exact', path,
 * handler })`，handler 是 Node `(req, res)`；命名路由**不会**自动过 connection，
 * 每个 handler 先过 connection 的准入（`requestRejection(req)`，或解构
 * `admit(req)` 的 `{rejection}`；都缺席 = fail-closed 全拒）—— 401/403 按
 * rejection 携带的状态码区分；
 * · `/start`、`/stop` 只收 POST；CSRF 门 = Origin 与 Host **同源**（远程访问
 * DSH UI 的场景由同源判定覆盖）或 Origin/Host 为 loopback —— 跨站请求零副作用；
 * · **状态变更全局串行**（start/stop/teardown 排同一条 promise 链）：并发两个
 * start 只有一个执行；stop 排在在飞 start 之后，收的是 start 建好的会话；
 * · **会话归属**：宿主 /start 发回唯一 sessionId，会话落账带身份；收场一律
 * `teardownTarget(target)` —— 只清 `session === target` 的账、只停
 * `target.sessionId`，旧流的 finally/旧面板的 stop 绝不停新一届；浏览器
 * /stop 必须带本会话的 tab token（不匹配 409，不停别人的）；
 * · 第二路 /stream 在同一会话已在转发时回 409（本地判定，不触碰上游）；宿主
 * 409（"已被消费"）不算会话失败，不收场；
 * · start 成功后 30s 内没有任何 /stream 连接：自动收场（页面崩溃/从未取流）；
 * 有连接后由数据面的空闲计时（30s 无字节）接管；
 * · 流数据面：上游 body 是 Web `ReadableStream`，`Readable.fromWeb` + `pipeline`
 * 转 `res`。**流 token 从不出 Node**，浏览器只拿一次性 tab token。
 *
 * 此后面板可交互：tap/swipe/back/home 经 `/mobile-pilot/input` 代理到宿主 /input
 * （动作面收窄到四种，坐标归一化；鉴权=同源/CSRF + tab token + 会话存活 + 宿主控制
 * token，全程不落盘不排队）。本模块也不做  的引用计数。
 */

const path = require('node:path')
const crypto = require('node:crypto')
const fs = require('node:fs')
const { Readable } = require('node:stream')
const { pipeline } = require('node:stream/promises')

const RENDEZVOUS_REL = ['run', 'stream-endpoint.json']
const ROUTE_BASE = '/mobile-pilot/stream'
/** 面板输入路由：面板 → Node 半 → 宿主 /input（取流与 codec 语义不在这一条路上）。 */
const ROUTE_INPUT = '/mobile-pilot/input'
/** 面板任务面路由：列虚拟屏任务 / 搬迁 / 释放 / 旋转。 */
const ROUTE_TASKS = '/mobile-pilot/tasks'
const ROUTE_MOVE = '/mobile-pilot/move'
const ROUTE_RELEASE = '/mobile-pilot/release'
const ROUTE_ROTATE = '/mobile-pilot/rotate'

/** 流转发的空闲上限：30s 没有字节即收（设备死了/编码停了都要让浏览器知道）。 */
const IDLE_TIMEOUT_MS = 30_000
/** 空闲计时器的巡检间隔。 */
const IDLE_CHECK_MS = 5_000
/** 控制端点一次调用的超时（毫秒）。 */
const CONTROL_TIMEOUT_MS = 8_000
/** start 成功后等待首个 /stream 连接的上限：超时自动收场（页面崩溃/从未取流）。 */
const UNUSED_STREAM_TIMEOUT_MS = 30_000

/** 读取并解析 rendezvous；缺失/形状不对回 null —— 调用方按"没有控制端点"处理。 */
function readRendezvous (packRoot, readFile = fs.readFileSync) {
  if (typeof packRoot !== 'string' || packRoot.length === 0) return null
  const file = path.join(packRoot, ...RENDEZVOUS_REL)
  try {
    const raw = JSON.parse(readFile(file, 'utf8'))
    const port = raw && typeof raw.controlPort === 'number' && Number.isInteger(raw.controlPort) ? raw.controlPort : null
    const token = raw && typeof raw.controlToken === 'string' && raw.controlToken.length > 0 ? raw.controlToken : null
    if (port === null || port <= 0 || port > 65535 || token === null) return null
    return { controlPort: port, controlToken: token }
  } catch {
    return null
  }
}

/** loopback 的 http(s) origin 判据（CSRF 的第二道门）。 */
function isLoopbackHttpOrigin (value) {
  return typeof value === 'string' &&
    /^https?:\/\/(127\.0\.0\.1|localhost|\[::1\])(:\d+)?$/i.test(value)
}

/** Host 头是否 loopback；IPv6 的 `[::1]:port` 也要认出来。 */
function isLoopbackHost (value) {
  const raw = String(value || '').toLowerCase()
  if (raw.length === 0) return false
  const bare = raw.startsWith('[')
    ? raw.slice(1, raw.indexOf(']'))
    : raw.split(':')[0]
  return bare === '127.0.0.1' || bare === 'localhost' || bare === '::1'
}

/** CSRF 的第一道门：Origin 与 Host 同源（远程访问 DSH UI 的场景由它覆盖）。 */
function isSameOrigin (origin, host) {
  if (typeof origin !== 'string' || typeof host !== 'string' || origin.length === 0) return false
  const matched = /^https?:\/\/([^/?#]+)$/i.exec(origin)
  if (!matched) return false
  return matched[1].toLowerCase() === host.toLowerCase()
}

function randomToken () {
  return crypto.randomBytes(16).toString('hex')
}

/**
 * 常数时间比较：tab/会话凭据对账用。长度不等直接 false（长度本就是
 * 公开信息），等长走 `timingSafeEqual` —— 与宿主 `constantTimeEquals` 同口径。
 */
function tabMatchesToken (presented, expected) {
  if (typeof presented !== 'string' || typeof expected !== 'string') return false
  if (presented.length !== expected.length || presented.length === 0) return false
  try {
    return crypto.timingSafeEqual(Buffer.from(presented, 'utf8'), Buffer.from(expected, 'utf8'))
  } catch {
    return false
  }
}

/**
 * 校验 webServer 服务的形状（供降级报告与测试）：对象存在且 `register` 是函数。
 */
function inspectWebServer (ctx) {
  const service = ctx ? ctx.webServer : null
  if (!service || typeof service !== 'object') {
    return { ok: false, reason: 'runtime 未提供 webServer 服务（需要 DSH 0.2.2-rc.1+）：面板将显示无流路由，工具与 CLI 不受影响' }
  }
  if (typeof service.register !== 'function') {
    return { ok: false, reason: 'runtime 的 webServer 服务没有 register：面板将显示无流路由，工具与 CLI 不受影响' }
  }
  return { ok: true, register: service.register.bind(service) }
}

/**
 * 路由处理函数（Node `(req, res)` 原生形态）。
 *
 * `deps`：`{ fetchImpl, controlProvider, connection }` —— `controlProvider()` 每次
 * 控制调用现读 rendezvous（宿主重启换端口/token 后自跟上）；connection 是 DSH 的
 * connection 服务（准入判定缺席 = fail-closed 全拒）。
 * 会话状态（闭包内）：`{ sessionId, streamPort, streamToken, tabToken }`。
 * 流 token 从不出本模块；浏览器只拿一次性 tab token。
 */
function createStreamHandlers ({ fetchImpl, controlProvider, connection, unusedStreamTimeoutMs = UNUSED_STREAM_TIMEOUT_MS, idleTimeoutMs = IDLE_TIMEOUT_MS, idleCheckMs = IDLE_CHECK_MS } = {}) {
  if (typeof fetchImpl !== 'function') throw new TypeError('fetchImpl is required')
  if (typeof controlProvider !== 'function') throw new TypeError('controlProvider is required')
  let session = null
  let lastEndedSessionId = null
  let lastGoodControl = null

  function currentControl () {
    const control = controlProvider()
    if (control && Number.isInteger(control.controlPort) && typeof control.controlToken === 'string') {
      lastGoodControl = control
      return control
    }
    return lastGoodControl
  }

  /**
   * 准入。0.2.2-rc.1 的 connection：`requestRejection(req)` 回 undefined=放行、
   * 拒绝则携带状态（401 未认证 / 403 禁止）；`admit(req)` 回 `{peer}`（放行）或
   * `{rejection}`（拒绝）。两种形状都接；服务缺席/抛错 = fail-closed 全拒。
   */
  function admitOrReject (req, res) {
    try {
      if (connection && typeof connection.requestRejection === 'function') {
        const rejection = connection.requestRejection(req)
        if (rejection !== undefined && rejection !== null) {
          const status = typeof rejection === 'number' && rejection >= 400
            ? rejection
            : (rejection && typeof rejection.status === 'number' && rejection.status >= 400 ? rejection.status : 403)
          res.writeHead(status, { 'Content-Type': 'text/plain; charset=utf-8' })
          res.end('forbidden')
          return false
        }
        return true
      }
      if (connection && typeof connection.admit === 'function') {
        const verdict = connection.admit(req)
        if (verdict && verdict.rejection) {
          const status = typeof verdict.rejection === 'number' && verdict.rejection >= 400
            ? verdict.rejection
            : (verdict.rejection && typeof verdict.rejection.status === 'number' ? verdict.rejection.status : 403)
          res.writeHead(status, { 'Content-Type': 'text/plain; charset=utf-8' })
          res.end('forbidden')
          return false
        }
        return true
      }
      res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' })
      res.end('forbidden (no connection service)')
      return false
    } catch (error) {
      res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' })
      res.end('forbidden')
      return false
    }
  }

  /** POST 路由的 CSRF 门：POST +（Origin 同源或 loopback）。 */
  function csrfOrReject (req, res) {
    if (req.method !== 'POST') {
      res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8' })
      res.end('POST only')
      return false
    }
    return originOrReject(req, res)
  }

  /**
   * 只判 Origin 的 CSRF 门：`GET /tasks` 也挡跨站读取 —— 读回的
   * 任务/包名不是秘密，但回包形状是本面板的内部契约，不给任意站点探针的机会。
   * 非 POST 的 405 交给各 handler 自己的方法门（tasks 只认 GET）。
   */
  function originOrReject (req, res) {
    const origin = req.headers && req.headers.origin
    const host = req.headers && req.headers.host
    // 同源 fetch 的 GET 不发送 Origin（Fetch 规范只对跨源/CORS 请求带它）；
    // 面板的 /tasks 正是这种请求。没有 Origin 不等于跨站：本路由还要求
    // tab token（攻击者拿不到），响应也受同源策略保护。只有明确带了
    // 非 loopback、非同源 Origin 才拒绝。
    if (!origin) return true
    if (!isLoopbackHttpOrigin(origin) && !isSameOrigin(origin, host)) {
      res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' })
      res.end('forbidden origin')
      return false
    }
    return true
  }

  function parseQuery (url) {
    const query = {}
    const q = (url || '').split('?')[1] || ''
    for (const pair of q.split('&')) {
      if (pair.length === 0) continue
      query[decodeURIComponent(pair.split('=')[0])] = decodeURIComponent(pair.split('=')[1] || '')
    }
    return query
  }

  // control endpoint requires the browser-side tabToken (header
  // x-pilot-tab) on /start and /input as a **session ownership** credential
  // (multi-tab isolation). It is NOT a sandbox boundary: the data plane is guarded
  // by the protected-app checks. tabToken lives in Node memory and the
  // browser only - never in responses or logs.
  async function controlGet (pathname, tabToken, sessionId) {
    const control = currentControl()
    if (!control) {
      return { ok: false, status: 503, body: { ok: false, reason: 'control endpoint is not available (no rendezvous)' } }
    }
    const url = `http://127.0.0.1:${control.controlPort}${pathname}${pathname.includes('?') ? '&' : '?'}token=${encodeURIComponent(control.controlToken)}`
    // 任务面三路由都带 x-pilot-session（宿主 /tasks /move /release 严格对账）。
    const headers = tabToken ? { 'x-pilot-tab': tabToken } : {}
    if (sessionId) headers['x-pilot-session'] = sessionId
    const response = await fetchImpl(url, { method: 'GET', headers, signal: AbortSignal.timeout(CONTROL_TIMEOUT_MS) })
    const text = await response.text()
    let body = null
    try { body = JSON.parse(text) } catch { body = null }
    return { ok: response.ok, status: response.status, body }
  }

  /** 控制端点的 POST（D /input 与  /move /release 用）：JSON body 原样转发 + tab/session 头。 */
  async function controlPost (pathname, payload, tabToken, sessionId) {
    const control = currentControl()
    if (!control) {
      return { ok: false, status: 503, body: { ok: false, reason: 'control endpoint is not available (no rendezvous)' } }
    }
    const url = `http://127.0.0.1:${control.controlPort}${pathname}${pathname.includes('?') ? '&' : '?'}token=${encodeURIComponent(control.controlToken)}`
    const headers = Object.assign(
      { 'Content-Type': 'application/json' },
      tabToken ? { 'x-pilot-tab': tabToken } : {},
    )
    if (sessionId) headers['x-pilot-session'] = sessionId
    const response = await fetchImpl(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(payload || {}),
      signal: AbortSignal.timeout(CONTROL_TIMEOUT_MS),
    })
    const text = await response.text()
    let body = null
    try { body = JSON.parse(text) } catch { body = null }
    return { ok: response.ok, status: response.status, body }
  }

  /**
   * 上游失败响应里的原因：正文（JSON 的 reason/error 或纯文本）读出来给浏览器。
   * 读不到就回空串 —— 调用方据此只回状态码，不编原因。
   */
  async function readUpstreamReason (upstream) {
    if (!upstream || typeof upstream.text !== 'function') return ''
    try {
      const raw = await upstream.text()
      if (typeof raw !== 'string') return ''
      const trimmed = raw.trim()
      if (!trimmed) return ''
      try {
        const parsed = JSON.parse(trimmed)
        if (parsed && typeof parsed === 'object') {
          const reason = parsed.reason || parsed.error
          return typeof reason === 'string' ? reason : ''
        }
      } catch { /* 不是 JSON：按纯文本处理 */ }
      return trimmed.length > 200 ? trimmed.slice(0, 200) + '…' : trimmed
    } catch {
      return ''
    }
  }

  /**
   * 读请求体并按 JSON 解析：空体/超限/解析失败都回 null（调用方按 400 处理）。
   * 上限 16KB —— 输入请求只有几个数字。
   */
  function readJsonBody (req) {
    // 上限 4KB：与宿主 /input 的 MAX_BODY_BYTES 同值。超限先停止累积并记 'large'，
    // **不立即销毁请求**——让 400 先写出去，响应完成后再由调用方销毁。
    return new Promise((resolve) => {
      const chunks = []
      let size = 0
      let oversize = false
      req.on('data', (chunk) => {
        if (oversize) return
        size += chunk.length
        if (size > 4 * 1024) { oversize = true; resolve('large'); return }
        chunks.push(chunk)
      })
      req.on('end', () => {
        const raw = Buffer.concat(chunks).toString('utf8').trim()
        if (!raw) { resolve(null); return }
        try { resolve(JSON.parse(raw)) } catch { resolve(null) }
      })
      req.on('error', () => resolve(null))
    })
  }

  /**
   * **定向收场**：只清 `session === target` 的账，并只停 `target.sessionId` ——
   * 旧流的 finally / 旧面板的 stop 到来时，账上已是新一届会话，这里不会碰它
   * （宿主对旧 sessionId 回 409 也在契约内）。无 target 幂等空过。
   */
  async function teardownTarget (target) {
    if (!target) return { ok: true, hadSession: false }
    if (session === target) {
      session = null
      lastEndedSessionId = target.sessionId
      // 30s 未用看门狗一并收：句柄留着会把进程挂到定时器结束。
      if (target.unusedTimer) { clearTimeout(target.unusedTimer); target.unusedTimer = null }
    }
    let ok = false
    try {
      const reply = await controlGet(`/stop?sessionId=${encodeURIComponent(target.sessionId)}`)
      ok = !!(reply && reply.ok)
    } catch { ok = false }
    return { ok, hadSession: true, sessionId: target.sessionId }
  }

  /**
   * **状态变更全局串行**：start/stop/teardown 全部排进同一条 promise 链 ——
   * 并发两个 start 只有一个执行；stop 排在在飞 start 之后，收的是 start 建好的
   * 会话（用户语义）；失败重试走同一队列。数据面（stream 转发）不进队列：
   * 它只读会话，收场经 enqueue 排队。
   */
  let chain = Promise.resolve()
  function enqueue (op) {
    const run = chain.then(op, op)
    chain = run.then(function () {}, function () {})
    return run
  }

  function teardownQueued (target) {
    return enqueue(function () { return teardownTarget(target) })
  }

  /**
   * 面板任务面：三条任务路由共用的会话与归属对账。准入/CSRF 由各 handler 先过；
   * 这里只判：会话在跑（409）+ tab 匹配（403，旧面板不得动新会话的任务面）。
   * tab 比较用常数时间（长度不等直接 false），与宿主 `constantTimeEquals` 同口径。
   * 回 target 或 null（已写过响应）。
   */
  function taskTargetOrReject (req, res) {
    const target = session
    if (!target) {
      res.writeHead(409, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ ok: false, reason: 'no stream session; POST /start first' }))
      return null
    }
    const tab = parseQuery(req.url).tab
    if (!tabMatchesToken(tab, target.tabToken)) {
      res.writeHead(403, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ ok: false, reason: 'tab token mismatch; the current session belongs to another panel' }))
      return null
    }
    return target
  }

  return {
    /** 当前会话快照（诊断/测试用；含 sessionId 与浏览器侧 tabToken 凭据，无流/控制 token）。 */
    snapshot () {
      return session
        ? { running: true, streamPort: session.streamPort, tabToken: session.tabToken, sessionId: session.sessionId }
        : { running: false }
    },

    status (req, res) {
      if (!admitOrReject(req, res)) return
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ running: !!session, streamPort: session ? session.streamPort : null, sessionId: session ? session.sessionId : null }))
    },

    start (req, res) {
      if (!admitOrReject(req, res)) return
      if (!csrfOrReject(req, res)) return
      // 客户端在飞中断：close 且没走到写完成（真实 abort 时 destroyed=true、
      // writableFinished=false）才算提前断开；正常写完响应后的 close 不算。
      let clientGone = false
      res.on('close', () => {
        if (!res.writableFinished) clientGone = true
      })
      // 回 enqueue 的 promise：调用方（测试/有序关闭）可等收场完成。
      return enqueue(async function () {
        if (session) {
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: true, alreadyRunning: true, sessionId: session.sessionId, tabToken: session.tabToken, metadata: session.metadata }))
          return
        }
        // tabToken 先生成再带去 /start（宿主存进会话，/input 与幂等 /start 复查；
        // D）。它只进 Node 内存与浏览器响应，不进日志。
        const tabToken = randomToken()
        let reply
        try {
          reply = await controlGet('/start', tabToken)
        } catch (error) {
          // 控制调用失败/超时时宿主状态未知：显式补发 /stop（missing-id 兼容：
          // 宿主没建会话则幂等空过）—— teardownTarget(null) 是空操作，不能用。
          await controlGet('/stop').catch(function () {})
          res.writeHead(502, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, reason: 'control endpoint unreachable: ' + ((error && error.message) || error) }))
          return
        }
        const stream = reply.body && reply.body.ok ? reply.body.stream : null
        if (!reply.ok || !stream || typeof stream.port !== 'number' || typeof stream.token !== 'string' || typeof stream.sessionId !== 'string') {
          // bundle 非法/缺失时宿主可能已开会话：显式补发 /stop（missing-id 兼容：
          // 宿主停当前）。
          await controlGet('/stop').catch(function () {})
          // D：宿主的结构化拒绝（403 + code=protected_app）必须原样透传——
          // 改写成 502 会让面板的 protected_app 分支变成死代码，还会按 502 自动重试。
          // 无 code 的普通失败仍按 502「上游拒绝开流」处理，保持既有语义。
          const code = reply.body && typeof reply.body.code === 'string' ? reply.body.code : ''
          const upstreamStatus = code && reply.status && reply.status >= 400 && reply.status <= 599
            ? reply.status
            : 502
          const failure = {
            ok: false,
            reason: (reply.body && reply.body.reason) || 'control endpoint refused start',
          }
          if (code) failure.code = code
          res.writeHead(upstreamStatus, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify(failure))
          return
        }
        // 会话先落账（收场才有身份），再看要不要立即收场：
        const created = {
          sessionId: stream.sessionId,
          streamPort: stream.port,
          streamToken: stream.token,
          tabToken,
          metadata: stream.metadata || null,
          streamTouched: false,
          relaying: false,
          unusedTimer: null,
        }
        session = created
        if (clientGone) {
          // 浏览器已走：会话已在宿主建立 —— 用它的 sessionId 精确停掉。
          await teardownTarget(created)
          return
        }
        // start 成功后若 30s 内没有任何 /stream 连接：自动收场 —— 覆盖页面崩溃、
        // JS 挂起、取流从未发生；有连接后由数据面的空闲计时接管。
        created.unusedTimer = setTimeout(function () {
          if (session === created && !created.streamTouched) {
            void teardownQueued(created)
          }
        }, unusedStreamTimeoutMs)
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({
          ok: true, sessionId: session.sessionId, tabToken: session.tabToken, metadata: session.metadata,
        }))
      })
    },

    stop (req, res) {
      if (!admitOrReject(req, res)) return
      if (!csrfOrReject(req, res)) return
      // 回 enqueue 的 promise：调用方可等收场完成（测试/有序关闭都要它）。
      return enqueue(async function () {
        const target = session
        if (!target) {
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: true, hadSession: false }))
          return
        }
        // 浏览器 /stop 必须带本会话的 tab token：不匹配 409 —— 旧面板的 stop
        // 不得停掉新面板的会话。
        const tab = parseQuery(req.url).tab
        if (!tabMatchesToken(tab, target.tabToken)) {
          res.writeHead(409, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, reason: 'tab token mismatch; the current session belongs to another panel' }))
          return
        }
        const result = await teardownTarget(target)
        if (!result.ok) {
          // 宿主 /stop 回执丢失：会话在拆除路上，但结局未知 —— 不谎报 ok。
          res.writeHead(503, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, reason: 'stop reply lost; the session is tearing down' }))
          return
        }
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, hadSession: true, sessionId: target.sessionId }))
      }).catch(function () {
        try { res.destroy() } catch { /* 已断则忽略 */ }
      })
    },

    /**
     * 面板输入：面板 → 这里 → 宿主 /input。不缓存、不排队（数据面直通）；
     * 宿主的状态码与原因原样回给浏览器（405/400/409/429/502 各按其码）。
     */
    input (req, res) {
      if (!admitOrReject(req, res)) return
      if (!csrfOrReject(req, res)) return
      // 早退也要把 body 排干再关，避免 RST 让浏览器看到网络错误而不是状态码。
      const drain = () => { try { req.resume() } catch { /* 已断则忽略 */ } }
      if (req.method !== 'POST') {
        drain()
        res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8' })
        res.end('POST only')
        return
      }
      const target = session
      if (!target) {
        drain()
        res.writeHead(409, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: false, reason: 'no stream session; POST /start first' }))
        return
      }
      return readJsonBody(req).then(function (body) {
        if (body === 'large') {
          drain()
          res.writeHead(400, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, reason: 'input body too large (max 4KB)' }))
          try { req.destroy() } catch { /* 已断则忽略 */ }
          return
        }
        if (!body || typeof body !== 'object') {
          drain()
          res.writeHead(400, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, reason: 'malformed input body' }))
          return
        }
        // 浏览器侧凭据是 tab token：不匹配 403 —— 旧面板不得往新会话里注入输入。
        if (!tabMatchesToken(body.tab, target.tabToken)) {
          drain()
          res.writeHead(403, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, reason: 'tab token mismatch; the current session belongs to another panel' }))
          return
        }
        // 只透传白名单字段 + 会话 id：控制 token 不经浏览器（这里才拼），tab token 走
        // 头（宿主存进会话，幂等 /start 与 /input 复查；D）。
        const forward = { action: body.action, sessionId: target.sessionId }
        for (const key of ['x', 'y', 'x1', 'y1', 'x2', 'y2', 'durationMs']) {
          if (body[key] !== undefined) forward[key] = body[key]
        }
        return controlPost('/input', forward, target.tabToken).then(function (reply) {
          res.writeHead(reply.status, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify(reply.body || { ok: false, reason: 'control endpoint returned an unreadable body' }))
        })
      }).catch(function (error) {
        if (!res.writableFinished && !res.destroyed) {
          res.writeHead(502, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, reason: 'control endpoint unreachable: ' + ((error && error.message) || error) }))
        }
      })
    },
    /**
     * `GET /mobile-pilot/tasks?tab=` → 列当前虚拟屏上的任务。
     * 只透传 displayId/tasks；控制 token 由这里拼，tab 与 sessionId 由头转发（不回浏览器）。
     */
    tasks (req, res) {
      if (!admitOrReject(req, res)) return
      // CSRF 门与 POST 路由同款：GET 也只放行同源/loopback 的 Origin。
      if (!originOrReject(req, res)) return
      if (req.method !== 'GET') {
        res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8' })
        res.end('GET only')
        return
      }
      const target = taskTargetOrReject(req, res)
      if (!target) return
      return controlGet('/tasks', target.tabToken, target.sessionId).then(function (reply) {
        if (!reply.ok || !reply.body || typeof reply.body !== 'object') {
          res.writeHead(reply.status && reply.status >= 400 && reply.status <= 599 ? reply.status : 502,
            { 'Content-Type': 'application/json' })
          res.end(JSON.stringify(reply.body || { ok: false, reason: 'control endpoint returned an unreadable body' }))
          return
        }
        // 只透传 displayId/tasks：宿主回包里本来就没有 token，但白名单落在这里才立得住。
        const tasks = Array.isArray(reply.body.tasks) ? reply.body.tasks : []
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: true, displayId: reply.body.displayId, tasks: tasks }))
      }).catch(function (error) {
        res.writeHead(502, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ ok: false, reason: 'control endpoint unreachable: ' + ((error && error.message) || error) }))
      })
    },

    /**
     * `POST /mobile-pilot/move?tab=` body `{taskId, direction}` → 宿主 /move。
     * 会话在跑 + tab 匹配 + body 白名单（sessionId 由这里补、taskId 整数、direction
     * ∈ {main, virtual}）；宿主的状态/原因/code 原样回。
     */
    move (req, res) {
      if (!admitOrReject(req, res)) return
      if (!csrfOrReject(req, res)) return
      const drain = () => { try { req.resume() } catch { /* 已断则忽略 */ } }
      if (req.method !== 'POST') {
        drain()
        res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8' })
        res.end('POST only')
        return
      }
      const target = taskTargetOrReject(req, res)
      if (!target) { drain(); return }
      return readJsonBody(req).then(function (body) {
        if (body === 'large') {
          drain()
          res.writeHead(400, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, reason: 'move body too large (max 4KB)' }))
          try { req.destroy() } catch { /* 已断则忽略 */ }
          return
        }
        if (!body || typeof body !== 'object') {
          drain()
          res.writeHead(400, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, reason: 'malformed move body' }))
          return
        }
        const taskId = Number.isInteger(body.taskId) && body.taskId >= 0 ? body.taskId : null
        const direction = body.direction === 'main' || body.direction === 'virtual' ? body.direction : null
        if (taskId === null || direction === null) {
          drain()
          res.writeHead(400, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, reason: 'taskId must be a non-negative integer and direction must be "main" or "virtual"' }))
          return
        }
        // 只透传白名单字段 + 会话 id：控制 token 不经浏览器，tab/session 走头。
        const forward = { sessionId: target.sessionId, taskId: taskId, direction: direction }
        return controlPost('/move', forward, target.tabToken, target.sessionId).then(function (reply) {
          res.writeHead(reply.status, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify(reply.body || { ok: false, reason: 'control endpoint returned an unreadable body' }))
        })
      }).catch(function (error) {
        if (!res.writableFinished && !res.destroyed) {
          res.writeHead(502, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, reason: 'control endpoint unreachable: ' + ((error && error.message) || error) }))
        }
      })
    },

    /**
     * `POST /mobile-pilot/release?tab=` → 宿主 /release（先停流再释放虚拟屏；
     * 屏上应用会被关闭，二次确认在面板）。body 的 sessionId 由这里补。
     */
    release (req, res) {
      if (!admitOrReject(req, res)) return
      if (!csrfOrReject(req, res)) return
      const drain = () => { try { req.resume() } catch { /* 已断则忽略 */ } }
      if (req.method !== 'POST') {
        drain()
        res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8' })
        res.end('POST only')
        return
      }
      const target = taskTargetOrReject(req, res)
      if (!target) { drain(); return }
      return readJsonBody(req).then(function (body) {
        if (body === 'large') {
          drain()
          res.writeHead(400, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, reason: 'release body too large (max 4KB)' }))
          try { req.destroy() } catch { /* 已断则忽略 */ }
          return
        }
        // body 可以是空/不成形：sessionId 由 Node 半补（会话对账在宿主）。
        const forward = { sessionId: target.sessionId }
        return controlPost('/release', forward, target.tabToken, target.sessionId).then(function (reply) {
          // 成功 = 宿主已停流并收屏：本端账目照 teardownTarget 同款清掉（只清自己的
          // 一届），否则下一次 /start 会对着一块已被释放的屏答 alreadyRunning。
          if (reply.ok && session === target) {
            session = null
            lastEndedSessionId = target.sessionId
            // 30s 未用看门狗一并收：句柄留着会把进程挂到定时器结束。
            if (target.unusedTimer) { clearTimeout(target.unusedTimer); target.unusedTimer = null }
          }
          res.writeHead(reply.status, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify(reply.body || { ok: false, reason: 'control endpoint returned an unreadable body' }))
        })
      }).catch(function (error) {
        if (!res.writableFinished && !res.destroyed) {
          res.writeHead(502, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, reason: 'control endpoint unreachable: ' + ((error && error.message) || error) }))
        }
      })
    },

    /**
     * `POST /mobile-pilot/rotate?tab=` → 宿主 /rotate（先停流再 resize，
     * 屏上任务保留；预览会中断，面板显示"已旋转，点开始预览"）。body 的 sessionId
     * 由这里补。成功后清本端账（与 release 同类，只清自己的那届），下一次 /start
     * 开新届；控制 token/流 token 从不出 Node。
     */
    rotate (req, res) {
      if (!admitOrReject(req, res)) return
      if (!csrfOrReject(req, res)) return
      const drain = () => { try { req.resume() } catch { /* 已断则忽略 */ } }
      if (req.method !== 'POST') {
        drain()
        res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8' })
        res.end('POST only')
        return
      }
      const target = taskTargetOrReject(req, res)
      if (!target) { drain(); return }
      return readJsonBody(req).then(function (body) {
        if (body === 'large') {
          drain()
          res.writeHead(400, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, reason: 'rotate body too large (max 4KB)' }))
          try { req.destroy() } catch { /* 已断则忽略 */ }
          return
        }
        // body 可以是空/不成形：sessionId 由 Node 半补（会话对账在宿主）。
        const forward = { sessionId: target.sessionId }
        return controlPost('/rotate', forward, target.tabToken, target.sessionId).then(function (reply) {
          // 成功 = 宿主已停流并旋转：本端账目照 release 同款清掉（只清自己的一届），
          // 否则下一次 /start 会对着一块已换尺寸的旧账答 alreadyRunning。
          if (reply.ok && session === target) {
            session = null
            lastEndedSessionId = target.sessionId
            if (target.unusedTimer) { clearTimeout(target.unusedTimer); target.unusedTimer = null }
          }
          res.writeHead(reply.status, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify(reply.body || { ok: false, reason: 'control endpoint returned an unreadable body' }))
        })
      }).catch(function (error) {
        if (!res.writableFinished && !res.destroyed) {
          res.writeHead(502, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, reason: 'control endpoint unreachable: ' + ((error && error.message) || error) }))
        }
      })
    },

    stream (req, res) {
      if (!admitOrReject(req, res)) return
      if (req.method !== 'GET') {
        res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8' })
        res.end('GET only')
        return
      }
      // **入口即捕获自己的目标会话**：后续收场只认它 —— 中途若账上换成新一届，
      // 本流的 finally 也只清自己的、不停别人的。
      const target = session
      if (!target) {
        res.writeHead(409, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ error: 'no stream session; POST /start first' }))
        return
      }
      const query = parseQuery(req.url)
      if (!tabMatchesToken(query.tab, target.tabToken)) {
        res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' })
        res.end('forbidden')
        return
      }
      // 同会话的第二路转发回 409（本地判定，不触碰上游、不收场）—— 第一路
      // 不该被它杀掉。
      if (target.relaying) {
        res.writeHead(409, { 'Content-Type': 'text/plain; charset=utf-8' })
        res.end('stream already being relayed for this session')
        return
      }
      // 有 /stream 连接了：未用看门狗交棒给数据面的空闲计时。
      target.streamTouched = true
      if (target.unusedTimer) { clearTimeout(target.unusedTimer); target.unusedTimer = null }
      target.relaying = true
      const abort = new AbortController()
      let finished = false
      let lastDataAt = Date.now()
      // 数据面的空闲计时从入口起跑：等宿主流建立期间的静默同样计入。
      const idleTimer = setInterval(() => {
        if (Date.now() - lastDataAt > idleTimeoutMs) {
          // 先 clearInterval：不收掉自己，这个巡检定时器会把进程挂到永远。
          clearInterval(idleTimer)
          abort.abort(new Error('stream idle timeout'))
          if (!finished) {
            finished = true
            target.relaying = false
            res.destroy()
            void teardownQueued(target)
          }
        }
      }, idleCheckMs)
      // **close 监听在入口挂上**（upstream fetch 之前）：等宿主流建立期间浏览器
      // 断开也要立即收场（abort 上游 + teardown），不等 30s 空闲超时。
      res.on('close', () => {
        if (finished) return
        finished = true
        clearInterval(idleTimer)
        target.relaying = false
        abort.abort(new Error('client gone'))
        void teardownQueued(target)
      })
      let upstream = null
      // 数据面的收场也排进队列；整条链回给调用方（测试等它完成）。
      return enqueue(async function () {
        upstream = await fetchImpl(
          `http://127.0.0.1:${target.streamPort}/stream?token=${encodeURIComponent(target.streamToken)}`,
          { method: 'GET', signal: abort.signal })
      }).then(async function () {
        if (finished) return
        if (!upstream || !upstream.ok || !upstream.body) {
          finished = true
          clearInterval(idleTimer)
          target.relaying = false
          // 上游失败响应体里带原因（"已被消费"/"设备侧拒绝"等）：读出来一并回给浏览器，
          // 只回状态码等于把场景丢在半路。
          const reason = await readUpstreamReason(upstream)
          // 宿主 409 = 该会话已被别的连接消费/已收场：不算会话失败，不收场 ——
          // 回给本连接 409 即可。
          if (upstream && upstream.status === 409) {
            if (!res.writableFinished && !res.destroyed) {
              res.writeHead(409, { 'Content-Type': 'text/plain; charset=utf-8' })
              res.end(reason || 'stream already consumed by another client')
            }
            return
          }
          if (!res.writableFinished && !res.destroyed) {
            res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' })
            res.end(reason || 'stream upstream refused')
          }
          await teardownTarget(target)
          return
        }
        res.writeHead(200, { 'Content-Type': 'video/mp4', 'Cache-Control': 'no-store' })
        const nodeStream = Readable.fromWeb(upstream.body)
        nodeStream.on('data', () => { lastDataAt = Date.now() })
        try {
          await pipeline(nodeStream, res)
        } catch {
          // 上游失败/客户端断开/abort：pipeline 已把 res 收尾，这里只收会话。
        } finally {
          if (!finished) {
            finished = true
            target.relaying = false
            clearInterval(idleTimer)
            await teardownTarget(target)
          }
        }
      }).catch(function (error) {
        if (finished) return
        finished = true
        target.relaying = false
        clearInterval(idleTimer)
        try {
          if (!res.writableFinished && !res.destroyed) {
            res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' })
            res.end('stream upstream unreachable: ' + ((error && error.message) || error))
          }
        } catch { /* 已断则忽略 */ }
        void teardownQueued(target)
      })
    },
  }
}

/**
 * 把处理函数挂到 runtime 的 webServer 服务上（0.2.2-rc.1 形状：
 * `register({ kind:'exact', path, handler })`，handler 是 `(req,res)`）。
 *
 * 每条注册都经 [effect]（装配方传 ctx.effect，注册随插件同生共死并拿到 disposer）；
 * 任一条抛错只报告并点名已注册清单，不向装配期冒泡。
 */
function attach (deps) {
  const webServer = deps ? deps.webServer : null
  const register = webServer && typeof webServer.register === 'function' ? webServer.register.bind(webServer) : null
  if (!register) {
    return { ok: false, via: null, reason: 'runtime 未提供 webServer 服务（需要 DSH 0.2.2-rc.1+）：面板将显示无流路由，工具与 CLI 不受影响' }
  }
  let handlers
  try {
    handlers = createStreamHandlers({
      fetchImpl: deps.fetchImpl,
      controlProvider: deps.controlProvider,
      connection: deps.connection || null,
    })
  } catch (error) {
    return { ok: false, via: 'webServer', reason: 'stream handlers 构造失败：' + ((error && error.message) || error) }
  }
  const effect = (deps && typeof deps.effect === 'function') ? deps.effect : null
  const routes = [
    { kind: 'exact', path: ROUTE_BASE, handler: handlers.stream },
    { kind: 'exact', path: `${ROUTE_BASE}/status`, handler: handlers.status },
    { kind: 'exact', path: `${ROUTE_BASE}/start`, handler: handlers.start },
    { kind: 'exact', path: `${ROUTE_BASE}/stop`, handler: handlers.stop },
    { kind: 'exact', path: ROUTE_INPUT, handler: handlers.input },
    { kind: 'exact', path: ROUTE_TASKS, handler: handlers.tasks },
    { kind: 'exact', path: ROUTE_MOVE, handler: handlers.move },
    { kind: 'exact', path: ROUTE_RELEASE, handler: handlers.release },
    { kind: 'exact', path: ROUTE_ROTATE, handler: handlers.rotate },
  ]
  const disposers = []
  const registered = []
  for (const route of routes) {
    try {
      const disposer = effect
        ? effect(() => register(route), `mobile-pilot: stream route ${route.path}`)
        : register(route)
      if (typeof disposer === 'function') disposers.push(disposer)
      registered.push(route.path)
    } catch (error) {
      return { ok: false, via: 'webServer', registered,
        disposer: () => { for (const d of disposers) runQuietly(d) },
        reason: `路由 ${route.path} 注册失败：${(error && error.message) || error}；` +
          `已注册：[${registered.join(', ')}] —— 预览不可用，工具与 CLI 不受影响` }
    }
  }
  return { ok: true, via: 'webServer', registered, disposer: () => { for (const d of disposers) runQuietly(d) } }
}

function runQuietly (fn) {
  try { fn() } catch { /* 撤销失败不冒泡：runtime 侧自会清 */ }
}

module.exports = {
  ROUTE_BASE,
  ROUTE_INPUT,
  ROUTE_TASKS,
  ROUTE_MOVE,
  ROUTE_RELEASE,
  ROUTE_ROTATE,
  RENDEZVOUS_REL,
  readRendezvous,
  inspectWebServer,
  createStreamHandlers,
  attach,
  randomToken,
  tabMatchesToken,
  isLoopbackHttpOrigin,
  isLoopbackHost,
  isSameOrigin,
}
