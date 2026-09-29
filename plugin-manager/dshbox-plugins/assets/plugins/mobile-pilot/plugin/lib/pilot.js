'use strict'

/**
 * 通道客户端：把 `<挂载点>/bin/pilot` 的一次调用封装成一个 Promise。
 *
 * 行为约束：
 *   1) 参数以 **argv 数组**传递，不经 shell 解释 —— 参数含引号、括号、中文时不依赖转义；
 *   2) 调用**串行化**：宿主侧是单条信箱通道，并发请求会相互干扰，有状态操作（surface.*）尤甚；
 *   3) 退出码与 `error.code` **原样透出**，不做归并 —— 调用方要据此区分
 *      「等待用户确认」「宿主界面在最前面（可恢复中断）」「能力未实现」等不同处置路径；
 *   4) 下发给入口的 `--timeout` 必须落在入口自身接受的区间内：入口会把它夹紧，
 *      夹紧后的预算比本层等待值短，于是「谁掐断了这次调用」在本层留下错误的证据；
 *   5) 通道是**有界**的：在途与排队的总数到达 maxQueued 时，新调用当场被拒
 *      （E_WRAPPER_QUEUE_FULL），不投件也不入队。每次调用的超时预算从**受理时刻**起算，
 *      排队等待计入预算 —— 排在长调用后面，不能把一条设了 1 秒的调用拖成事实上的无限等待。
 *
 * 本模块不实现信箱协议本身：请求信封、outbox 回包、错误码由宿主与 `bin/pilot` 共同持有，
 * 这里只负责投递与解析，避免出现第二份会各自漂移的协议规格。
 */

const { spawn } = require('node:child_process')
const { performance } = require('node:perf_hooks')
const { resolvePack } = require('./mount')

/** 未命中分档表时的默认超时（毫秒）。 */
const DEFAULT_TIMEOUT_MS = 30000

/**
 * 在途与排队总数的默认上限。这是试验初值，不是被证明过的安全阈值：
 * 它的作用是给排队兜底，让满额时调用方拿到明确的背压信号，而不是让队列无限加深。
 */
const DEFAULT_MAX_QUEUED = 32

/**
 * 进程内单调时钟。排队与预算的测量只从这里取时：不受系统对时干扰，
 * 也便于测试注入假时钟来推演排队与超时的交错。
 */
const monotonicNowMs = () => performance.now()

/**
 * 入口脚本对 `--timeout` 接受的区间。超出部分会被入口静默夹紧，
 * 因此本层先夹紧，并在结果里说明夹紧发生在这一层。
 */
const ENTRY_TIMEOUT_MIN_MS = 1000
const ENTRY_TIMEOUT_MAX_MS = 120000

/**
 * 按能力分档的超时。需要用户确认的能力（ASK / SESSION_CONSENT）给足余量：
 * 确认尚未完成时不应由本层先行掐断。档位值可以高于入口上限，由 [timeoutPlan] 夹紧。
 */
const TIMEOUTS = [
  [/^screen\.record$/, 180000],
  [/^screen\.(capture|observe)$/, 90000],
  [/^ui\./, 20000],
  [/^sys\./, 30000],
  [/^app\.(launch|install|stop)$/, 60000],
  [/^clip\./, 60000]
]

/**
 * 一次调用实际使用的超时预算。
 * @returns {{ms: number, requestedMs: number, clamped: boolean}} `ms` 是交给入口的值，
 *          `requestedMs` 是本层档位或调用方覆盖值，`clamped` 说明差额由本层在入口之前掐的。
 */
function timeoutPlan (capability, override) {
  let requested = DEFAULT_TIMEOUT_MS
  if (Number.isFinite(override) && override > 0) requested = Math.trunc(override)
  else for (const [pattern, ms] of TIMEOUTS) if (pattern.test(capability)) { requested = ms; break }
  const ms = Math.min(Math.max(requested, ENTRY_TIMEOUT_MIN_MS), ENTRY_TIMEOUT_MAX_MS)
  return { ms, requestedMs: requested, clamped: ms !== requested }
}

function timeoutFor (capability, override) {
  return timeoutPlan(capability, override).ms
}

/**
 * 提问通路（`bin/pilot ask`）的窗口区间与载荷长度上限。
 *
 * 与入口 CLI 及宿主 EnvelopeCodec 同一组数：本层按同一组数先拒，超长载荷才会走成
 * 用法错误（退出码 1）而不是交给宿主拒、被记成传输层故障（退出码 6）—— 后者会把
 * 「改调用就能修」报成「宿主坏了」，让调用方不再重试。
 */
const ASK_MIN_TIMEOUT_MS = 5000
const ASK_MAX_TIMEOUT_MS = 120000
const ASK_DEFAULT_TIMEOUT_MS = 60000
const ASK_MAX_QUESTION_CHARS = 200
const ASK_MAX_OPTION_CHARS = 60

/**
 * 本层等一次提问的预算在窗口之上多留的一截，与入口 CLI 的 ASK_BUDGET_MARGIN_MS 是同一个数：
 * 宿主认领收件箱最坏 5 秒（观察者失效时退化成 5 秒轮询），加上屏判定预算（4 秒）与回包落盘。
 * 只留收尾余量时，用户在屏上答了的那一次答复会被本层当成「没有回包」丢掉。
 */
const ASK_BUDGET_MARGIN_MS = 12000

/**
 * 校验一次提问的载荷，不触碰通道。
 *
 * 返回 `{ error }`（调用方改参数就能修）或 `{ payload, windowMs, budgetMs }`：
 * `windowMs` 是交给宿主的等待窗口，`budgetMs` 是本层等待的总预算（窗口 + 余量）。
 */
function askPayload (input) {
  const source = input && typeof input === 'object' ? input : {}
  const question = typeof source.question === 'string' ? source.question.trim() : ''
  if (!question) return { error: 'question 去空白后为空：问题必须是一句非空文本' }
  if (question.length > ASK_MAX_QUESTION_CHARS) {
    return { error: `question 超过 ${ASK_MAX_QUESTION_CHARS} 字（本层与宿主的同一上限）` }
  }
  const raw = Array.isArray(source.options) ? source.options : null
  if (!raw || raw.length < 2 || raw.length > 3) {
    return { error: 'options 必须是 2..3 项：少于两项无从选择，多于三项用户读不完' }
  }
  if (raw.some((item) => typeof item !== 'string' || !item.trim())) {
    return { error: 'options 每一项都必须是非空字符串' }
  }
  const options = raw.map((item) => item.trim())
  if (options.some((item) => item.length > ASK_MAX_OPTION_CHARS)) {
    return { error: `options 每一项不超过 ${ASK_MAX_OPTION_CHARS} 字（本层与宿主的同一上限）` }
  }
  const requested = Number(source.timeoutMs)
  const windowMs = Math.min(
    Math.max(Number.isFinite(requested) && requested > 0 ? Math.trunc(requested) : ASK_DEFAULT_TIMEOUT_MS, ASK_MIN_TIMEOUT_MS),
    ASK_MAX_TIMEOUT_MS,
  )
  return { payload: { question, options, timeoutMs: windowMs }, windowMs, budgetMs: windowMs + ASK_BUDGET_MARGIN_MS }
}

/** 摘要中 data 的预览上限（字符）。 */
const DATA_PREVIEW = 600

/**
 * data 预览。截断时必须把"还剩多少没显示"写出来（字符数与 UTF-8 字节数）：
 * 只有预览长度可判断该不该去读落盘的完整文件。
 *
 * [fullFile] 是完整副本落盘后的路径。**没有落盘就不能说"见落盘文件"** ——
 * 那等于指一个不存在的地方，调用方会照着去找第二次、第三次。
 */
function preview (value, limit = DATA_PREVIEW, fullFile = null) {
  let text
  try {
    text = typeof value === 'string' ? value : JSON.stringify(value)
  } catch {
    text = String(value)
  }
  if (text == null) return ''
  if (text.length <= limit) return text
  const rest = text.slice(limit)
  const where = fullFile ? `完整内容见 ${fullFile}` : '本层没有另存完整副本，这份数据只有预览里这些'
  return `${text.slice(0, limit)}…(截断：余 ${rest.length} 字符 / ${Buffer.byteLength(rest, 'utf8')} 字节未显示，${where})`
}

/**
 * 把一次进程结果归一为固定形状。任何异常都不抛出：调用方始终拿到对象，
 * 失败时携带可判定的 `code`。
 *
 * `timeout` 是本次实际下发给入口的预算：掐断者的归属只能凭它判断，
 * 事后无人能从退出码区分「本层等待超时」与「入口的信箱超时」。
 * `E_WRAPPER_TIMEOUT` 涵盖两种现场 —— 总预算（含排队等待）耗尽、投件后的执行超时；
 * reason 必须能区分这两种，排队耗尽的那一种要点名「未投件」，
 * 不能让调用方误以为入口已经拿着请求跑了一半。
 *
 * `queuedMs` 是受理到投件之间的排队时长：只有走过本地队列的结果才携带，
 * 供调用方核对等待去了哪里，不用反推。
 *
 * v2 控制通道的字段原样透传（v1 回包没有这些键，按空值口径给）：
 * `state` 是宿主报的请求阶段（终态帧带出；超时回包给 UNKNOWN——执行与否无法证实）；
 * `requestId` 是回包里的请求 id，status/cancel 跟进同一条请求时要用它；
 * `artifactDelivery` 只在产物复制失败时为 'failed'，此时 artifacts 里那个槽位是空串，
 * 不能当成可用的目标路径。
 */
function normalize ({ capability, stdout, stderr, exitCode, signal, timedOut, spawnError, timeout, queuedMs }) {
  const plan = timeout || { ms: timeoutFor(capability), requestedMs: timeoutFor(capability), clamped: false }
  const base = {
    capability,
    ok: false,
    code: null,
    retryable: null,
    exitCode: typeof exitCode === 'number' ? exitCode : null,
    surface: null,
    degraded: null,
    degradedFrom: null,
    reason: null,
    note: null,
    elapsedMs: null,
    queuedMs: Number.isFinite(queuedMs) ? Math.max(0, Math.round(queuedMs)) : null,
    timeoutMs: plan.ms,
    timeoutRequestedMs: plan.requestedMs,
    timeoutClamped: plan.clamped,
    data: null,
    artifacts: [],
    state: null,
    requestId: null,
    artifactDelivery: null,
    // 提问通路（ask）的答复载荷，字段名取自回包本身；其余能力没有这个键，固定 null。
    choice: null,
    raw: null,
    stdout: stdout || '',
    stderr: stderr || ''
  }

  if (spawnError) {
    return { ...base, code: 'E_WRAPPER_SPAWN', reason: `入口无法执行: ${spawnError.message}`, exitCode: 6 }
  }
  if (timedOut) {
    // 本层主动终止。宿主可能仍在等待用户确认，因此标记为可重试。
    const clampNote = plan.clamped ? `，档位/覆盖值 ${plan.requestedMs}ms 已被本层按入口上限 ${ENTRY_TIMEOUT_MAX_MS}ms 夹紧` : ''
    return {
      ...base,
      code: 'E_WRAPPER_TIMEOUT',
      retryable: true,
      reason: `包装层在 ${plan.ms}ms 处掐断了等待（不是入口超时，也不是宿主拒答）${clampNote}`,
      exitCode: 6
    }
  }

  let parsed = null
  const out = (stdout || '').trim()
  if (out) { try { parsed = JSON.parse(out) } catch { parsed = null } }
  if (!parsed) {
    const err = (stderr || '').trim()
    if (err) { try { parsed = JSON.parse(err) } catch { parsed = null } }
  }
  if (!parsed || typeof parsed !== 'object') {
    // 入口结束但没有给出可解析的回包：属本层故障，统一按 6 报出，
    // 同时把子进程的真实退出码与信号留在 reason 里备查。
    const who = `（子进程退出码 ${typeof exitCode === 'number' ? exitCode : '未知'}${signal ? `，信号 ${signal}` : ''}）`
    return { ...base, code: 'E_WRAPPER_NO_REPLY', reason: `入口未返回可解析的回包${who}`, exitCode: 6 }
  }

  const rawError = parsed.error
  // 入口有两种错误形状：业务错误给 {code, retryable, exitCode}，传输层超时给一个裸字符串
  // （E_TRANSPORT_TIMEOUT）。漏掉后者会让"没人答这次调用"读成"没有错误码的失败"。
  const error = rawError && typeof rawError === 'object' ? rawError : null
  const code = error
    ? (typeof error.code === 'string' ? error.code : null)
    : (typeof rawError === 'string' ? rawError : null)
  let reason = typeof parsed.reason === 'string' ? parsed.reason : null
  // 信箱超时的归属：入口在它自己的 --timeout 预算内没等到回包。不写出来，
  // 调用方会把它读成"宿主停了"，而真因往往只是确认还没被答复。
  if (code === 'E_TRANSPORT_TIMEOUT' && !reason) {
    const clampNote = plan.clamped ? `，本层档位/覆盖值 ${plan.requestedMs}ms 已按入口上限夹紧` : ''
    reason = `入口在其 --timeout 预算 ${plan.ms}ms 内未取得回包（掐断者是入口，不是包装层${clampNote}）`
  }
  return {
    ...base,
    ok: parsed.ok === true,
    code,
    retryable: error && typeof error.retryable === 'boolean' ? error.retryable : null,
    exitCode: typeof exitCode === 'number' ? exitCode : (error && error.exitCode) || null,
    surface: parsed.surface ?? null,
    degraded: typeof parsed.degraded === 'boolean' ? parsed.degraded : null,
    degradedFrom: parsed.degradedFrom ?? null,
    reason,
    note: typeof parsed.note === 'string' ? parsed.note : null,
    degradeReason: typeof parsed.degradeReason === 'string' ? parsed.degradeReason : null,
    elapsedMs: typeof parsed.elapsedMs === 'number' ? parsed.elapsedMs : null,
    data: parsed.data ?? null,
    artifacts: Array.isArray(parsed.artifacts) ? parsed.artifacts : [],
    state: typeof parsed.state === 'string' ? parsed.state : null,
    requestId: typeof parsed.id === 'string' ? parsed.id : null,
    artifactDelivery: parsed.artifactDelivery === 'failed' ? 'failed' : null,
    // 答复三支（选中 / 全部驳回 / 重新提问）都在这一层原样交回：本层不解释、不代替用户作答。
    choice: parsed.choice && typeof parsed.choice === 'object' ? parsed.choice : null,
    raw: parsed
  }
}

/**
 * 本地构造的回包：这次调用没有发生（用法错误、排队满额、排队期间被取消）。
 *
 * 不能拿 `normalize` 的空 stdout 路径凑：那条路径是「入口没给回包」的兜底，
 * 一律按包装层故障记 6，会把用法错误的 1 吞成宿主故障，让调用方去查宿主。
 * 退出码由调用方点名，`elapsedMs` 统一为 0（没有发生过等待）。
 */
function localResult (capability, { code, exitCode, reason, retryable }) {
  const idle = timeoutFor(capability)
  const result = normalize({
    capability,
    stdout: '',
    stderr: '',
    exitCode,
    timeout: { ms: idle, requestedMs: idle, clamped: false },
  })
  result.code = code
  result.exitCode = exitCode
  result.reason = reason
  result.retryable = retryable
  result.elapsedMs = 0
  return result
}

/**
 * 停在最前面时**可能**是"等挑图标的系统选择框"的那几个包：分身的中介容器读不出里面那个包，
 * 宿主为此回 `verified:false` 加 `observed`。这是"看不清现场"，不是"确认有个框"。
 */
const MEDIATORS = new Set(['com.vivo.doubleinstance', 'com.android.intentresolver'])

/** 宿主按数值校验的参数名（顶层）。落点类键不在列：它们不是可以改对的名字。 */
const NUMERIC_ARGS = new Set([
  'x', 'y', 'fromX', 'fromY', 'toX', 'toY', 'nodeId', 'times', 'until', 'durationMs', 'timeoutMs',
  'percent', 'seconds', 'limit', 'startMs', 'endMs', 'hour', 'minute',
])

/** 同一个键名在不同能力下类型不同：`ui.setProgress` 的 value 是数值，`sys.settings.write` 的是字符串。 */
const NUMERIC_ARGS_BY_CAPABILITY = { 'ui.setProgress': ['value'] }

/** 嵌套着数值键的那一层，以及只在那一层里才是数值名的键（顶层的 index 不是本层参数）。 */
const NESTED_ARG_OBJECTS = ['selector', 'relative']
const NESTED_NUMERIC_ARGS = new Set(['index'])

const NUMERIC_LITERAL = /^-?\d+(?:\.\d+)?$/

/**
 * 把数值参数里"数字字面量的字符串"转回数字，一层嵌套（`selector` / `relative`）一起处理。
 *
 * 调用链上有一层会把工具入参里的数值送成字符串，而宿主严格校验类型，
 * 于是 `ui.tap` / `ui.swipe` / `ui.setProgress` 全被挡在参数校验上。只认名单内的键：
 * `ui.setText` 的 `text`、`pkg.query` 的 `keyword` 合法取值本来就是 "123" 这样的字符串，
 * 一律转换会把可用入参改成非法入参。
 */
function numericArgs (args, capability = '', nested = false) {
  if (!args || typeof args !== 'object') return args
  const extra = NUMERIC_ARGS_BY_CAPABILITY[capability] || []
  const convert = (key) => NUMERIC_ARGS.has(key) || extra.includes(key) || (nested && NESTED_NUMERIC_ARGS.has(key))
  const out = {}
  for (const [key, value] of Object.entries(args)) {
    const text = typeof value === 'string' ? value.trim() : null
    out[key] = text != null && convert(key) && NUMERIC_LITERAL.test(text) ? Number(text) : value
  }
  for (const nest of NESTED_ARG_OBJECTS) {
    if (out[nest] && typeof out[nest] === 'object') out[nest] = numericArgs(out[nest], capability, true)
  }
  return out
}

/** 选择框在现场时的处置：先看清，再答掉，最后原参数重发。 */
function chooserHint (holder, ok) {
  // 同一句判据在两种回包形状下要说两句实话：前台那次是 ok + verified:false（没判失败），
  // 虚拟屏那次是 ok:false + E_LAUNCH_NOT_LANDED。写成一句就会有一半是错的。
  const shape = ok
    ? '这一条回的是 verified:false，不是失败'
    : '这一条回的是失败（E_LAUNCH_NOT_LANDED），但目标包可能已经起来了、只是认不出是哪个分身'
  return `停在最前面的是 ${holder}，宿主认不出它里面装着哪个应用（${shape}）。` +
    '两种现场都可能：系统级的"选一个图标"框（应用分身、打开方式）在等一次点击，' +
    '或者分身容器本身就在前面。先 ui.snapshot 读一次树 —— 这台机上这类框读得到树，' +
    '但两个图标常常既无 text 也无 desc，只有像素分得清哪个是本体哪个是分身，所以再 screen.capture 看一眼。' +
    '要答的是这一屏上的界面，落点由用户的执行模式偏好决定，`ui.tap` 不接受 display 参数；' +
    'release 或重建虚拟屏收不掉一个选择框，也不该拿它当第一条路。'
}

/**
 * 按 code 给出处置建议。文案进入工具结果，供调用方直接采用，避免无意义的重复尝试。
 * 仅覆盖已定义的错误码；未收录的码原样返回，不做猜测。
 */
function hintFor (result) {
  // 降级挂在成功回包上时 code 是空的，那条码只在 degradeReason 里；两条取其一去查表。
  const code = (result && result.code) || (result && result.ok ? result.degradeReason : null)
  // 中介包判据优先于 code：启动核验认不出分身容器里那个包时回的是 ok + verified:false，
  // 没有错误码可挂靠，而 data.observed 已经点名了停在最前面的包。
  const observed = result && result.data && typeof result.data.observed === 'string' ? result.data.observed : null
  if (observed && MEDIATORS.has(observed)) return chooserHint(observed, result.ok === true)
  // 宿主报 UNKNOWN 时它比任何错误码都优先：这一刻连"做没做"都无法证实，
  // 按错误码给的重试建议照做就可能变成对非幂等动作的二次执行。
  if (result && result.state === 'UNKNOWN') {
    return '宿主无法证实该请求是否已执行：先用 mobile-pilot status 查询同一请求（用回包里的 requestId），' +
      '或用 mobile-pilot cancel 尝试取消；拿到明确终态之前，禁止按原参数自动重试非幂等动作。'
  }
  // 宿主证实「执行前已撤销」与「结果未知」是两种结论，处置相反：前者不用再取消、
  // 也不用再查，后者在拿到终态前禁止重试。这条要排在错误码之前——等待到点的回包
  // 仍挂着 E_TRANSPORT_TIMEOUT，旧文案会把「未执行」误读成「可能已执行」。
  if (result && result.state === 'CANCELLED') {
    return '宿主已证实该请求在执行前被撤销：动作未执行，不需要再用 status/cancel 跟进这条请求。' +
      '是否按原参数重新发起由你按业务确认后决定，这与「结果未知」是两种不同的处置。'
  }
  // 取消只有在宿主证实尚未提交动作时才叫 CANCELLED；其余结论都不能当"已撤销"读。
  if (result && (result.outcome === 'CANCEL_REQUESTED' || code === 'CANCEL_REQUESTED')) {
    return '取消意愿已被宿主记下，但宿主未证实已撤销：动作仍可能照常完成。' +
      '请用 mobile-pilot status 跟进同一 requestId，以宿主的终态为准。'
  }
  switch (code) {
    case 'E_AWAITING_CONSENT':
      return '需要用户在手机上确认。三处答复入口不会同时出现：按可用性依次是助手页内的那张卡（该页在前台时）、悬浮卡、' +
        '通知栏的「允许/拒绝」；同一时刻只有一处可点，在哪一处答都算数、也只算一次。' +
        '宿主界面在最前面时悬浮卡不出现（系统设置一类界面也会盖掉它），此时请到通知栏或助手页答复。' +
        '后台虚拟屏运行期间也没有悬浮卡：问题落在助手页内那张卡上（该页正在看时），否则落通知栏。' +
        '随后以完全相同的参数重试即可接上同一张框；重试要留出真实间隔；改参数不会接上这张框，答复窗口结束后未答的问题不再可答。'
    case 'E_TASK_SUSPENDED_BY_HOST':
      return '宿主自身界面位于前台，写操作被暂停（可恢复中断，非拒绝）。待用户离开该界面后重试；读操作（ui.snapshot / ui.node / ui.waitFor）不受此限。'
    case 'E_GATE_NO_FOREGROUND':
      return '三处确认入口都不可用（无悬浮卡窗口、通知被挡、助手页不在前面）。请让用户切到其他应用或把宿主带到前台，再重试。'
    case 'E_GATE_HOST_DENIED':
      return '被规则或档位拒绝，重试无效：该能力可能处于 DENIED 状态（例如 sys.shell 需用户逐条开启）；reason 点名了参数时，是那个参数写了宿主自身的包名——被拒的是这个过滤条件本身而不是数据，去掉它重发即可。'
    case 'E_GATE_CANCELLED':
      // 这条只在续行入口、任何外部动作提交之前产出：宿主可证未派发，原请求已终局。
      return '该请求已被取消，宿主证实它未执行、没有留下任何副作用：不需要再用 status/cancel 跟进。' +
        '原请求已终局，重发同一参数也不会「接上」它——按业务确认后作为一条新请求重新发起即可，会照常再问一次用户。'
    case 'E_GATE_WAITING_TURN':
      // 队列按「同一件事」复用同一张框：立即重发只会排到同一批后面，问不到人。
      return '更早的确认框还没答完，这一问没有排上、用户没有看到它：稍候退避后重发，' +
        '或先到悬浮卡 / 通知栏 / 助手页把挂着的确认答复掉。立即原样重发只会继续排在同一批后面。'
    case 'E_QUEUE_FULL':
      // 满队回包的 reason 带 retryAfterMs 与 queueDepth：按它退避，不要灌队列。
      return '宿主通道队列已满，本次调用未被受理：按 reason 里的 retryAfterMs 退避后再重试一次，' +
        '不要立即原样重发或连续重发——那只会让队列更满。'
    case 'E_LAUNCH_NOT_LANDED': {
      // 这一码有两种现场，处置方向相反，合成一句就会把其中一种引到反方向上：
      const holder = /(?:foreground is|top on display \d+ is) ([A-Za-z0-9._]+)/.exec([result.reason, result.note, result.degradeReason].filter(Boolean).join(' ')) ||
        /the user's own screen shows ([A-Za-z0-9._]+)/.exec([result.reason, result.note].filter(Boolean).join(' '))
      if (holder) {
        // 点名的包就是分身容器 / 系统选择器时，走选择框那段：这一段自己写着"只有点名到
        // 这类包才按框处理"，却在这一支把中介包当普通应用说，是自相矛盾的两句话。
        if (MEDIATORS.has(holder[1])) return chooserHint(holder[1], result.ok === true)
        return `有另一个应用确实停在最前面（reason 点名的是 ${holder[1]}），目标包没起来。` +
          '这不是后端断了，也**不要 release/重建虚拟屏** —— 那只会换一个屏号，抢前台的东西还在。' +
          '先 ui.snapshot 读一次树，读不到再 screen.capture：停在最前面的是普通应用时，' +
          '下一步是让用户离开那一屏或换目标包，不是等一个可能并不存在的选择框。' +
          '只有当点名的包是分身容器 / 系统选择器（com.vivo.doubleinstance、com.android.intentresolver）时，' +
          '才按"选一个图标"的框处理：图标常无 text 与 desc，要靠像素分清，答法用 desc 或 nodeId / relative 定位。'
      }
      return '启动请求已被系统接下，但目标应用没有来到最前面，且此刻没有别的包占着前面。' +
        '前台屏那一路等 1–2 秒再以同样参数重试一次即可（冷启动要时间）；' +
        '虚拟屏那一路没有「把已在跑的任务搬上虚拟屏」这条路，只能 release 后重建屏再启动，或直接就在它真实落地的屏上操作。'
    }
    case 'E_SURFACE_UNAVAILABLE':
      // 这一码说的是执行面，不是启动结果：它可能挂在一次成功的调用上（作为 degradeReason 出现），
      // 也可能挂在失败上。只报码不给方向，调用方会去查后端连接，而后端好好的。
      return '本次没有走到所期望的后台执行面：请求要虚拟屏，实际落在前台或被拒。' +
        '读 phone_status 的 backend.shizuku（authorized / bound / trustedDisplay.alive）定位缺的那一环：' +
        '屏不在就 surface.virtual {} 建一面，Shizuku 没绑上就先把绑定接通。' +
        '同一次回包里 observed 点名了停在最前面的包时，那是选择框现场，按 screen.capture 的读数处理。'
    case 'E_SURFACE_NO_SHELL':
    case 'E_SURFACE_API_LEVEL':
      // 这两条说的是"这一趟为什么没能走虚拟屏"，与启动或读写本身成不成无关。
      // 不写出来，调用方只能看见 degraded:true 一个布尔，猜不到缺的是哪一环。
      return '这台机器给不出虚拟屏：' +
        (code === 'E_SURFACE_API_LEVEL'
          ? '系统版本低于建屏要求（要 Android 13 及以上），换任何开关都不会变。'
          : 'Shizuku 用户服务没在跑，读 phone_status 的 backend.shizuku（running / authorized / bound）先把这三档接通。') +
        '本次调用已落在前台屏（surface=foreground、degraded=true），同一条回包里 reason / note 说的是这一趟本身。'
    case 'E_BACKEND_UNAVAILABLE':
      return '后端不可用：读取清单中的 backend.shizuku（running / authorized / bound / trustedDisplay.alive / trustedDisplay.nodeTree）确认断点；reason 已点名具体环节时按其处置。'
    case 'E_CAPABILITY_UNAVAILABLE_ON_DEVICE':
      return '这台设备给不出这个能力，重试与开任何开关都不会变（例如 sys.shell 的 media 动词：reason 里带 "this device has no such command" 时说明那条二进制不在本机）。改走别的路径，或告诉用户这条在这台机器上做不了。'
    case 'E_NODE_SYSTEM_CONSENT_TARGET':
      return '解析出来的那颗控件属于系统授权界面，宿主直接拒了：权限弹窗只能由用户本人回答，不写 package、改用 nodeId 或补个 view id 都绕不过这一问。要往下走请用户自己点，或换一个不是授权框的目标。'
    case 'E_NODE_NOT_FOUND':
      return '选择器在当前控件树中未命中，或 nodeId 已失效。重新 ui.snapshot 后重取 nodeId / 选择器。'
    case 'E_NODE_AMBIGUOUS':
      return '选择器命中多个控件，宿主不代为选择。补充 index 或更具体的匹配字段。'
    case 'E_NODE_NOT_ACTIONABLE':
      return '控件存在但不接受该动作：先用 ui.node 读取其 actions / range，再改用相应动作（滚动用 ui.scroll，范围控件用 ui.setProgress）。判定可点与否以 actions 为准，clickable 只是框架原始位。'
    case 'E_ACTION_REJECTED':
      return '动作已派发但被应用拒绝。更换目标或改走其他路径。'
    case 'E_WAIT_TIMEOUT':
      return '等待的条件在超时内未成立（界面未进入该状态），与通道无关。'
    case 'E_TRANSPORT_UNKNOWN':
      // 请求已确认投进控制信箱、通道随后失联：连宿主是否收到都无法证实，
      // 任何「再发一次」的建议在这里都是对非幂等动作的重复执行。
      return '请求已投进宿主，但控制通道随后失联，结果无法证实：' +
        '先用 mobile-pilot status 查询同一请求（用结果里的 requestId），或用 mobile-pilot cancel 尝试取消；' +
        '拿到明确终态之前，禁止按原参数重新发起非幂等动作。'
    case 'E_TRANSPORT_TIMEOUT':
      // 旧文案让调用方"以完全相同的参数重试"——对结果未知的写动作那等于自动重放。
      // v2 之后同一条请求可以用 status/cancel 跟进，先查清结局再决定下一步。
      return `入口在自身 --timeout 预算（本次 ${result.timeoutMs || '未知'}ms）内没等到回包：` +
        '先确认屏幕前有没有等你答复的确认框；回包带 requestId 时，先用 mobile-pilot status 查询同一请求，' +
        '必要时用 mobile-pilot cancel 尝试取消——宿主可能在你的等待超时后、其有效期内完成执行。' +
        '拿到明确终态之前，禁止按原参数自动重试非幂等动作。'
    case 'E_WRAPPER_TIMEOUT':
      // 一个码两种现场：reason 写明「未把请求投给入口」的是排队耗尽，其余是投件后的执行超时。
      return '包装层的总预算耗尽了。reason 点名「未把请求投给入口」的是排队等待耗尽预算：降低并发或加大 timeoutMs 后重试；' +
        '其余是投件后的执行超时：回包带 requestId 时，先用 mobile-pilot status 查询同一请求，' +
        '必要时用 mobile-pilot cancel 尝试取消——宿主可能在你的等待超时后、其有效期内完成执行。' +
        '确认未执行或不需要之后，才谈得上加大 timeoutMs 重试。本层不会给出超过入口上限 ' + ENTRY_TIMEOUT_MAX_MS + 'ms 的预算。'
    case 'E_WRAPPER_QUEUE_FULL':
      // 背压是本层的并发保护，不是设备或宿主的故障：满额时立刻原样重发只会再撞一次。
      return `本层排队已满（在途+排队 ${result.queueDepth ?? '未知'} 条），本次调用未受理、未投件：` +
        '降低并发或退避后再重试，不要立即原样重发。'
    case 'E_WRAPPER_CANCELLED':
      return '本次调用在本地排队期间被取消，未投件未执行：确认是否确实还需要，再重新发起。已经投给入口的在途调用无法由此取消。'
    case 'E_WRAPPER_NO_REPLY':
    case 'E_WRAPPER_SPAWN':
      return '本层未取得回包：先执行 mobile-pilot doctor 核对挂载点与入口，并确认宿主进程在运行。'
    case 'E_WRAPPER_NO_MOUNT':
      return '未找到挂载点：确认宿主已铺好 /opt/pilot（或设置 MOBILE_PILOT_HOME），再用 phone_status 复核。'
    case 'E_WRAPPER_BAD_MANIFEST':
      // 清单读不出来时工具层必须失败而不是拿空清单继续：这里给的是恢复路径。
      return '清单缺失或损坏：先调宿主的助手页重铺资产，或重启沙盒后用 phone_status 复核。'
    case 'E_ARTIFACT_WRITE':
      // 产物写不出去属本层故障：指向可改的落盘目录，并明说数据没有另存副本，不能假装东西还在。
      return '快照落盘失败：改用 MOBILE_PILOT_OUT_DIR 指定可写的落盘目录，或检查当前落盘目录的权限与剩余空间；快照数据没有另存副本。'
    case 'E_ASK_TIMEOUT':
      // 两种现场在宿主侧由 covered 区分，但结论相同：没有答复。追问是安全的，因为 ask 不改设备状态。
      return '等满窗口没有收到答复（宿主无法证实卡片是否真的到了用户眼前，原因见 reason）。' +
        '提出问题不改动设备状态，稍候换一个更好答的问法重问是安全的；连续两次超时说明这条路当前问不到人，' +
        '改把问题带回用户看得见的对话里，不要继续重问。'
    case 'E_ASK_BUSY':
      // 同屏只允许一张卡：这一问根本没摆上去，用户没有看到它。
      return '屏上已经有一张卡在等答复（另一问，或一条待批的写操作），本次提问没有摆上去、用户没有看到它：' +
        '先让用户答掉那张卡（或用 mobile-pilot status 跟进那条请求），再重新提问；原地重问只会再撞一次。'
    case 'E_ASK_NO_SURFACE':
      // 需要用户动手：宿主侧没有可用的呈现面（无悬浮窗授权 / 后台模式隐藏浮窗）。
      return '此刻没有可用的呈现面（原因见 reason）：要么请用户授予悬浮窗权限，要么改走用户自己看得见的路径' +
        '把问题带到对话里问。改问法、重发都不会凭空变出一张卡。'
    case 'E_ASK_NO_REPLY':
      // 入口在通道在位时没等到回包：卡可能没上屏，也可能宿主正忙。
      return '通道在位但没有回包：卡片可能根本没上屏，也可能宿主正忙。没有「没有答复」以外的结论；' +
        '可先用 phone_capture 看一眼屏幕前有没有卡在等，再决定是否重问。'
    case 'E_WRAPPER_USAGE':
      // 本层在触碰通道之前就拒掉的用法错：改调用就能修，重发无效。
      return '本层在投件之前拒掉了这次调用（原因见 reason）：按原因改参数后重新发起，原样重发无效。'
    default:
      return ''
  }
}

/** 可读摘要：状态行 + 超时归属 + 原因 + data 预览 + 产物 + 处置建议。 */
function describe (result, dumpFull) {
  const head = `${result.ok ? 'ok' : (result.code || 'error')} ${result.capability}` +
    (Number.isFinite(result.elapsedMs) ? ` ${result.elapsedMs}ms` : '') +
    (result.surface ? ` surface=${result.surface}` : '') +
    (result.degraded === true ? ` degraded=true${result.degradedFrom ? `(from ${result.degradedFrom})` : ''}` : '')
  const lines = [head]
  if (result.timeoutClamped) {
    lines.push(`timeout: 下发 ${result.timeoutMs}ms（本层档位 ${result.timeoutRequestedMs}ms 已按入口上限 ${ENTRY_TIMEOUT_MAX_MS}ms 夹紧）`)
  }
  if (Number.isFinite(result.queuedMs) && result.queuedMs > 0) {
    lines.push(`queue: 受理后排队 ${result.queuedMs}ms 才轮到执行，超时预算含这段等待`)
  }
  // v2 终态帧才带宿主阶段：这一行给的是"同一条请求拿 requestId 去 status/cancel 跟进"的抓手。
  // v1 回包没有阶段可言，查询也查不到记录，不写出来误导人。
  if (result.state) {
    lines.push(`request: id=${result.requestId ?? '未知'} state=${result.state}`)
  }
  if (result.reason) lines.push(`reason: ${result.reason}`)
  if (result.note) lines.push(`note: ${result.note}`)
  if (result.degradeReason) lines.push(`degrade: ${result.degradeReason}`)
  if (result.data != null) {
    // 预览要截、完整数据不能丢：本层另存一份并给出路径。宿主侧没有分页与过滤参数，
    // 一次 200 多条的列表在这里被截掉之后就再也取不回来，调用方只能绕开通道去 grep。
    const existing = result.artifacts && result.artifacts.length ? result.artifacts[0] : null
    let full = existing
    if (!full && typeof dumpFull === 'function') {
      const text = (() => {
        try { return typeof result.data === 'string' ? result.data : JSON.stringify(result.data) } catch { return '' }
      })()
      if (text.length > DATA_PREVIEW) full = dumpFull(result.capability, result.data) || null
    }
    lines.push(`data: ${preview(result.data, DATA_PREVIEW, full)}`)
  }
  if (result.artifacts && result.artifacts.length) lines.push(`artifacts: ${result.artifacts.join(', ')}`)
  const hint = hintFor(result)
  if (hint) lines.push(`处置建议: ${hint}`)
  return lines.join('\n')
}

/**
 * 串行通道（有界）。
 *   const channel = new PilotChannel()
 *   const r = await channel.call('pkg.query', {})
 *
 * 队列仍按 FIFO 串行：宿主侧是单条信箱通道，所有调用共享这一条队伍是既有约束；
 * 把只读状态读取从队伍里解出去，需要宿主另开一条独立的控制通道，当前协议没有，
 * 所以保序优先，不在这一层私自分流。
 *
 * 排队中的调用可用 cancelPending 撤下；已在途的调用撤不了 —— 本层能做的只是
 * 不把还没投出去的请求投出去。已经投给入口的请求，用入口的 v2 控制通道跟进：
 * mobile-pilot status / cancel 加回包里的 requestId；只有宿主证实尚未提交动作，
 * 取消才算数，其余结论一律按 UNKNOWN 处置，不得按原参数自动重试非幂等动作。
 */
class PilotChannel {
  constructor ({ env = process.env, mountFile, execPath = process.execPath, spawnImpl = spawn, platform = process.platform, maxQueued = DEFAULT_MAX_QUEUED, nowMs = monotonicNowMs } = {}) {
    this.env = env
    this.mountFile = mountFile
    this.execPath = execPath
    this.spawnImpl = spawnImpl
    this.platform = platform
    // 在途+排队的总数上限，call() 受理口径的计数：进入即 +1，本次调用 settle 后 -1。
    this.maxQueued = Number.isInteger(maxQueued) && maxQueued >= 1 ? maxQueued : DEFAULT_MAX_QUEUED
    this.nowMs = typeof nowMs === 'function' ? nowMs : monotonicNowMs
    this.pending = 0
    /** 还在本地排队、尚未投件的调用标记：cancelPending 只作用在这一层。 */
    this.queueTokens = new Set()
    this.tail = Promise.resolve()
  }

  resolve () {
    const options = { env: this.env, platform: this.platform }
    if (this.mountFile) options.mountFile = this.mountFile
    return resolvePack(options)
  }

  /**
   * 有界入队（call 与 ask 共用的骨架）。满额时当场拒绝（E_WRAPPER_QUEUE_FULL）：
   * 不 spawn、不占队列位，调用方立刻拿到可判定的背压信号，而不是把等待无声地叠进队伍深处。
   *
   * 计数口径是「受理即 +1、本次调用 settle（无论成败）后 -1」，且名额回收的回调
   * 先于队列放行注册：前一条 settle 与后一条入队之间不存在计数真空，
   * 满额判定不会放进超量的调用。
   *
   * 总预算在受理时建立（排队等待从此计入 plan.ms），轮到执行时只按剩余预算投件。
   */
  enqueue (label, plan, job) {
    if (this.pending >= this.maxQueued) {
      return Promise.resolve(this.queueFull(label))
    }
    // 受理时通道空闲的调用，下一个微任务就会投件，不存在排队等待：
    // 它的预算整体都是执行预算，按档位原值下发；真正等过通道的调用才按剩余预算收紧。
    const waited = this.pending > 0
    this.pending += 1
    const acceptedAtMs = this.nowMs()
    const ctx = {
      acceptedAtMs,
      deadlineMs: acceptedAtMs + plan.ms,
      plan,
      waited,
      token: { capability: label, cancelled: false },
    }
    this.queueTokens.add(ctx.token)
    const run = () => job(ctx)
    const next = this.tail.then(run, run)
    const release = () => {
      this.pending -= 1
      this.queueTokens.delete(ctx.token)
    }
    next.then(release, release)
    this.tail = next.then(() => undefined, () => undefined)
    return next
  }

  /** 提交一次能力调用：数值参数在此处归一，入队、预算与串行纪律见 [enqueue]。 */
  call (capability, args = {}, options = {}) {
    const payload = numericArgs(args, capability)
    const plan = timeoutPlan(capability, options.timeoutMs)
    return this.enqueue(capability, plan, (ctx) => this.invoke(capability, payload, options, ctx))
  }

  /**
   * 向用户提一个问题（`bin/pilot ask`）：2..3 个选项加一个「全部驳回」，由宿主摆成一张卡并等答复。
   *
   * 与 call 共用同一条通道纪律（有界入队、串行投递、预算自受理时刻起算），差别有三处：
   *   1) 载荷是 `ask --json {question,options,timeoutMs}`，**不下发 `--timeout`** ——
   *      入口对这个子命令明确拒绝它（等待窗口只由载荷里的 timeoutMs 决定，两个来源会互相打架）；
   *   2) 因此本层的等待预算取「窗口 + 余量」，可以高过入口对 `--timeout` 接受的 120000 上限，
   *      也不参与夹紧：窗口写在载荷里，本层改了就等于替调用方改问题窗口。排队吃掉的预算不足以
   *      覆盖一次最小提问时当场退回不投件，不把调用方的等待承诺悄悄放大；
   *   3) 答复三支皆为成功（选中 / 全部驳回 / 重新提问），由调用方按回包的 `choice.kind` 处置 ——
   *      本层只原样交回，绝不替用户作答。
   */
  ask (input = {}) {
    const checked = askPayload(input)
    if (checked.error) {
      // 用法错误在触碰通道之前拒绝：交给宿主拒会走成传输层故障（退出码 6），
      // 把「改调用就能修」报成「宿主坏了」。
      return Promise.resolve(localResult('ask', {
        code: 'E_WRAPPER_USAGE',
        exitCode: 1,
        retryable: false,
        reason: checked.error,
      }))
    }
    const plan = { ms: checked.budgetMs, requestedMs: checked.budgetMs, clamped: false }
    return this.enqueue('ask', plan, (ctx) => {
      const ready = this.prepare('ask', ctx, {
        minBudgetMs: ASK_MIN_TIMEOUT_MS + ASK_BUDGET_MARGIN_MS,
        clampToEntry: false,
      })
      if (ready.error) return ready.error
      return this.deliver(
        'ask',
        ready.entry,
        ['ask', '--json', JSON.stringify(checked.payload)],
        ready.plan,
        ready.queuedMs,
      )
    })
  }


  /** 满额背压的固定回包。queueDepth 单独成字段，机器判读读字段、不解析文字。 */
  queueFull (capability) {
    const result = localResult(capability, {
      code: 'E_WRAPPER_QUEUE_FULL',
      exitCode: 6,
      retryable: true,
      reason: `本层排队已满：在途与排队共 ${this.pending} 条，达到上限 ${this.maxQueued} 条，本次调用未受理（未投件未执行）；降低并发或退避后重试`,
    })
    result.queueDepth = this.pending
    return result
  }

  /**
   * 撤下仍在本地排队、尚未投件的调用：capability 省略时撤全部，指定时只撤该能力的。
   * 返回本次**新**标记的条数（重复撤同一条不重复计数）。被撤下的调用轮到执行时
   * 直接退回 E_WRAPPER_CANCELLED，不会 spawn；已在途的调用不在此列（见类注释）。
   */
  cancelPending (capability) {
    let marked = 0
    for (const token of this.queueTokens) {
      if (token.cancelled) continue
      if (capability == null || token.capability === capability) {
        token.cancelled = true
        marked += 1
      }
    }
    return marked
  }

  /**
   * 投递一次调用。ctx 由 call() 在受理时建立；不经 call 直接调用本方法时，
   * 按受理即空闲处理（没有排队等待可计）。
   */
  invoke (capability, args, { timeoutMs, out } = {}, ctx = null) {
    const context = ctx || {
      acceptedAtMs: this.nowMs(),
      plan: timeoutPlan(capability, timeoutMs),
      waited: false,
      token: null,
    }
    if (context.deadlineMs == null) context.deadlineMs = context.acceptedAtMs + context.plan.ms
    const ready = this.prepare(capability, context)
    if (ready.error) return Promise.resolve(ready.error)
    const argv = ['call', capability]
    if (args && typeof args === 'object' && Object.keys(args).length) argv.push('--json', JSON.stringify(args))
    argv.push('--timeout', String(ready.plan.ms))
    if (out) argv.push('--out', out)
    return this.deliver(capability, ready.entry, argv, ready.plan, ready.queuedMs)
  }

  /**
   * 投件前的统一前置，四段按序：摘除本地排队标记 → 取消判定 → 挂载解析 → 排队剩余预算。
   *
   * 返回 `{ error }` 表示本次调用到此为止（未投件），或 `{ entry, plan, queuedMs }` 表示可以投件。
   *
   * `minBudgetMs` 是「剩余多少预算才值得投件」的下限：call 用入口对 `--timeout` 的下限；
   * ask 用它自己的最小等待预算（窗口下限 + 余量），因为 ask 的窗口不由入口参数决定。
   * `clampToEntry` 只在 call 上为真：它的预算要以 `--timeout` 下发，夹进入口区间是必须的。
   */
  prepare (capability, ctx, { minBudgetMs = ENTRY_TIMEOUT_MIN_MS, clampToEntry = true } = {}) {
    if (ctx.token) this.queueTokens.delete(ctx.token)
    const queuedMs = Math.max(0, this.nowMs() - ctx.acceptedAtMs)

    // 取消判定先于一切：被撤下的调用轮到时直接退回，不做挂载解析，更不 spawn。
    if (ctx.token && ctx.token.cancelled) {
      const result = normalize({ capability, stdout: '', stderr: '', exitCode: 6, timeout: ctx.plan, queuedMs })
      result.code = 'E_WRAPPER_CANCELLED'
      result.retryable = false
      result.reason = `本地排队期间被调用方取消（排队 ${Math.round(queuedMs)}ms），未投件未执行`
      result.elapsedMs = Math.round(this.nowMs() - ctx.acceptedAtMs)
      return { error: result }
    }

    const resolved = this.resolve()
    if (!resolved.root) {
      const tried = resolved.tried.map((t) => `  ${t.source}: ${t.dir} (entry=${t.hasEntry ? 'y' : 'n'}, manifest=${t.hasManifest ? 'y' : 'n'})`).join('\n')
      const result = normalize({ capability, stdout: '', stderr: `未找到手机助手挂载点，已尝试:\n${tried}\n可用 MOBILE_PILOT_HOME 指定。`, exitCode: 6, queuedMs })
      result.code = 'E_WRAPPER_NO_MOUNT'
      result.reason = '未找到手机助手挂载点'
      return { error: result }
    }

    // 排队耗尽判定只对真正等过通道的调用做：剩余预算连下限都垫不出来时，
    // 投件等于把调用方的总预算悄悄放大，不如当场退回，并写明请求没有投出去。
    let plan = ctx.plan
    if (ctx.waited) {
      const remainingMs = ctx.deadlineMs - this.nowMs()
      if (remainingMs < minBudgetMs) {
        const result = normalize({ capability, stdout: '', stderr: '', exitCode: 6, timeout: plan, queuedMs })
        result.code = 'E_WRAPPER_TIMEOUT'
        result.retryable = true
        result.reason = `总预算（含排队等待 ${Math.round(queuedMs)}ms）已耗尽，剩余不足下限 ${minBudgetMs}ms，本层未把请求投给入口`
        result.elapsedMs = Math.round(this.nowMs() - ctx.acceptedAtMs)
        return { error: result }
      }
      if (clampToEntry) {
        // 剩余预算夹进入口区间后下发：排队吃掉的部分不补回，调用方等待的上限仍是受理时的承诺。
        // timeoutRequestedMs 保留调用方/档位的原值，timeoutMs 是实际下发值，差额就是排队与判定的耗时。
        plan = {
          ms: Math.min(Math.max(Math.trunc(remainingMs), ENTRY_TIMEOUT_MIN_MS), ENTRY_TIMEOUT_MAX_MS),
          requestedMs: plan.requestedMs,
          clamped: plan.clamped,
        }
      }
    }
    return { entry: resolved.info.entry, plan, queuedMs }
  }

  /**
   * 投件并等回包。`argv` 由调用方给全（call 带 `--timeout`，ask 不带），本方法只管
   * 进程生命周期与回包归一：兜底计时器比预算多 2000ms，只为防入口失去响应后长期占用通道，
   * 不作「谁掐断了这次调用」的证据 —— 那个结论由入口的预算与回包里的码给出。
   */
  deliver (capability, entry, argv, plan, queuedMs) {
    return new Promise((resolve) => {
      let child
      try {
        child = this.spawnImpl(this.execPath, [entry, ...argv], { stdio: ['ignore', 'pipe', 'pipe'], env: this.env })
      } catch (error) {
        resolve(normalize({ capability, stdout: '', stderr: '', exitCode: 6, spawnError: error, timeout: plan, queuedMs }))
        return
      }
      let stdout = ''
      let stderr = ''
      let timedOut = false
      const timer = setTimeout(() => {
        timedOut = true
        try { child.kill('SIGKILL') } catch { /* 进程已退出 */ }
      }, plan.ms + 2000)
      child.stdout.on('data', (chunk) => { stdout += chunk })
      child.stderr.on('data', (chunk) => { stderr += chunk })
      child.on('error', (error) => {
        clearTimeout(timer)
        resolve(normalize({ capability, stdout, stderr, exitCode: 6, spawnError: error, timeout: plan, queuedMs }))
      })
      child.on('close', (code, signal) => {
        clearTimeout(timer)
        resolve(normalize({ capability, stdout, stderr, exitCode: code, signal, timedOut, timeout: plan, queuedMs }))
      })
    })
  }
}

module.exports = {
  PilotChannel,
  normalize,
  numericArgs,
  describe,
  hintFor,
  timeoutFor,
  timeoutPlan,
  preview,
  // 提问载荷的校验与预算单独露出：窗口夹紧、长度上限与余量都是要在纯 JS 层钉住的契约，
  // 不必起一个假入口进程去验证。
  askPayload,
  DEFAULT_TIMEOUT_MS,
  DEFAULT_MAX_QUEUED,
  ENTRY_TIMEOUT_MIN_MS,
  ENTRY_TIMEOUT_MAX_MS,
  ASK_MIN_TIMEOUT_MS,
  ASK_MAX_TIMEOUT_MS,
  ASK_DEFAULT_TIMEOUT_MS,
  ASK_MAX_QUESTION_CHARS,
  ASK_MAX_OPTION_CHARS,
  ASK_BUDGET_MARGIN_MS,
  DATA_PREVIEW,
  TIMEOUTS
}
