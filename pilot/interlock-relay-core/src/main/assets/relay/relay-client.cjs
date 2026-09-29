#!/usr/bin/env node
// Relay call entry on the sandbox side. Zero third-party dependencies: Node
// built-ins only.
//
// File format: this file is CommonJS. It is materialized into the sandbox as an
// extensionless script under <entry>/bin/<command>; the entry directory and the
// command name are both derived from this file's own location and base name, so
// the same bytes work under any deployment path. Extensionless entries are
// parsed as CommonJS, hence no import/export syntax.
//
// 两条通道：能力请求走 inbox/outbox 信箱（协议 v1）；提交回执、状态查询、取消与
// 探活走 control-inbox/control-outbox 控制通道（协议 v2，宿主存在时才有这对目录）。
// 控制请求同样以「先写 .part 再改名」投递——宿主只处理改名后出现的文件，
// 直接写入会让宿主读到半截内容。submit 的受理回执（state=QUEUED、无 result）
// 不是终点：宿主随后会把同一份回包文件原子覆盖成终态帧。
// submit 一旦落进控制信箱就不再换通道重投：请求随时可能已被宿主消费，
// 投件后的通道异常按 UNKNOWN 收场；本地等待到点则补发一次有界 cancel，
// 宿主证实执行前已撤销时，把「未知」收窄成「未执行」。
//
// 本进程跑完即退出，不留常驻状态，因此不存在累积型内存增长。

const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { performance } = require('node:perf_hooks');

// Self-derivation: the host materializes this script at <entry>/bin/<command>.
// The entry directory is this file's parent directory and the command name is
// this file's own base name; usage text and disposition hints reuse them, so the
// same script is correct under any deployment mount point.
const ENTRY_DIR = path.dirname(__dirname);
const CMD = path.basename(process.argv[1] || 'relay');
const RUN_DIR = path.join(ENTRY_DIR, 'run');
const INBOX = path.join(RUN_DIR, 'inbox');
const OUTBOX = path.join(RUN_DIR, 'outbox');
const CONTROL_INBOX = path.join(RUN_DIR, 'control-inbox');
const CONTROL_OUTBOX = path.join(RUN_DIR, 'control-outbox');
const CAPABILITIES = path.join(ENTRY_DIR, 'capabilities.json');
const PROTOCOL_VERSION = 1;
const CONTROL_PROTOCOL_VERSION = 2;

const EXIT_OK = 0;
const EXIT_USAGE = 1;

/** 本进程自己出问题（通道不可用、等待到点）时的兜底值。 */
const EXIT_INTERNAL = 6;
const FALLBACK_EXIT = EXIT_INTERNAL;
const EXIT_TIMEOUT = 3;

/** status/cancel 找不到目标请求时的退出码：与「请求不存在」对齐，区别于本进程故障。 */
const EXIT_NOT_FOUND = 4;
/** cancel 只被记下意愿、未获宿主证实撤销时的退出码：结论未出，需要跟进。 */
const EXIT_RETRY_LATER = 5;

const DEFAULT_TTL_MS = 90000;
const MIN_DELAY_MS = 5;
const MAX_DELAY_MS = 200;

/** status/cancel/health 这类控制命令的本地等待预算。它们不等审批与后端，5 秒足够。 */
const CONTROL_BUDGET_MS = 5000;

/** 等待到点后补发 cancel 的有界等待：只等宿主对撤销的证实，不再拉长本次调用。 */
const CANCEL_CONFIRM_BUDGET_MS = 2000;

/** 宿主状态存储里的终态阶段名。CANCEL_REQUESTED 不在其中：那仍是飞行中的请求。 */
const TERMINAL_PHASES = new Set([
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'INTERRUPTED_BEFORE_EXECUTION',
  'UNKNOWN',
  'EXPIRED',
]);

/** 控制通道拒绝提交时回包 cause 里内嵌的宿主错误码 → 退出码（宿主单源表的对齐子集）。 */
const EMBEDDED_EXIT_CODES = {
  E_REQUEST_TOO_LARGE: EXIT_USAGE,
  E_TRANSPORT_MALFORMED: EXIT_USAGE,
  E_NOT_IMPLEMENTED: EXIT_USAGE,
  E_QUEUE_FULL: EXIT_RETRY_LATER,
  E_INTERNAL: EXIT_INTERNAL,
};

/** callV2 的返回哨兵：v2 往返没能完成，需要退回 v1 信箱重走一次。 */
const FALLBACK_TO_V1 = Symbol('fallback-to-v1');

function usage(stream) {
  stream.write(
    [
      `usage: ${CMD} <command> [options]`,
      '',
      'commands:',
      '  doctor [--probe]                check the pack and mailbox directories (static readiness);',
      '                                    --probe adds a v2 control-channel ping',
      '  capabilities                    list capabilities and gate state (JSON)',
      '  call <capability> [options]     run one capability (v2 control channel when present,',
      '                                    the v1 mailbox otherwise)',
      '  status <requestId>              v2: snapshot of one submitted request (JSON)',
      '  cancel <requestId>              v2: ask the host to cancel one submitted request (JSON)',
      '  home                            bring the host app page back to the front; use it after',
      '                                    finishing a foreground task when the user did not ask to stay',
      '  ask --json J                    v2: put a question with 2..3 options plus "Ask again" and',
      '                                    to the user through the self-made overlay card (JSON reply);',
      '                                    use it while driving other apps so the user is not left guessing',
      '',
      'options for call:',
      '  --json J      arguments object as JSON',
      '  --out FILE    deliver the first returned artifact to FILE; must be an absolute path',
      `                (omit --out and it lands under ${OUT_ROOT}/<kind>/ with a generated`,
      '                 name, which the reply reports back)',
      '  --timeout MS  how long this process waits, 1000..120000 (default 90000)',
      '',
      'options for ask:',
      '  --json J      {"question":"...","options":["A","B"],"timeoutMs":60000}',
      '                (question: 1..200 chars; options: 2..3 items, 1..60 chars each;',
      '                 timeoutMs: 5000..120000, default 60000)',
      '',
      'exit codes: 0 ok, 1 usage, 2 denied, 3 timeout, 4 user must act on the phone,',
      '            5 retry later, 6 internal',
      '            status/cancel: 4 also means no such request; cancel: 5 also means the',
      '            cancellation was only requested, not confirmed by the host',
      '',
    ].join('\n'),
  );
}

class UsageError extends Error {}

/** 带 value 的 option。FLAG_OPTIONS 里的开关不带值。 */
const VALUE_OPTIONS = new Set(['json', 'out', 'timeout']);
const FLAG_OPTIONS = new Set(['probe']);

/** 每条命令各自接受的 option：option 合法不等于对每条命令都合法。 */
const COMMAND_OPTIONS = new Map([
  ['doctor', new Set(['probe'])],
  ['capabilities', new Set()],
  ['call', new Set(['json', 'out', 'timeout'])],
  ['status', new Set()],
  ['cancel', new Set()],
  ['home', new Set(['timeout'])],
  ['ask', new Set(['json'])],
]);

/**
 * 严格解析：未知、重复、缺值的 option 都按用法错误拒绝，并点名是哪一个。
 * 拼错的参数静默吞掉会让调用在错误的默认值下继续跑，比当场失败难查得多。
 */
function parseArgs(argv) {
  const out = { _: [] };
  const seen = new Set();
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (token === '--help' || token === '-h') {
      out.help = true;
    } else if (token.startsWith('--')) {
      const name = token.slice(2);
      if (!VALUE_OPTIONS.has(name) && !FLAG_OPTIONS.has(name)) {
        throw new UsageError(`unknown option ${token}`);
      }
      if (seen.has(name)) throw new UsageError(`duplicate option ${token}`);
      seen.add(name);
      if (FLAG_OPTIONS.has(name)) {
        out[name] = true;
        continue;
      }
      const value = argv[i + 1];
      if (value == null || value.startsWith('--')) {
        throw new UsageError(`option ${token} requires a value`);
      }
      out[name] = value;
      i += 1;
    } else {
      out._.push(token);
    }
  }
  return out;
}

/** option 本身合法、但不在该命令的接受名单里，同样按用法错误拒绝。 */
function checkOptions(command, parsed) {
  const allowed = COMMAND_OPTIONS.get(command) || new Set();
  for (const name of [...VALUE_OPTIONS, ...FLAG_OPTIONS]) {
    if (name in parsed && !allowed.has(name)) {
      throw new UsageError(`option --${name} is not valid for ${command}`);
    }
  }
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    // 响应可能正在写入；返回 null 让调用方继续等，而不是当成失败。
    return null;
  }
}

function sleep(ms) {
  const until = performance.now() + ms;
  while (performance.now() < until) {
    const spin = Math.min(20, until - performance.now());
    if (spin > 0) Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, spin);
  }
}

function fail(stream, message, code) {
  stream.write(`${message}\n`);
  return code;
}

/**
 * 递归按键排序的紧凑 JSON：数组保序，对象只比内容不比键序。
 * 同一份参数两种写法必须算出同一个摘要，「同一件事」的判定才不会被键序拆散。
 */
function stableStringify(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value) ?? 'null';
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`;
  const keys = Object.keys(value).sort();
  return `{${keys.map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`).join(',')}}`;
}

/** 参数载荷摘要：随 submit 交给宿主，宿主据此认同「同一件事」并拒绝同 id 异摘要的重放。 */
function digestOf(capability, args) {
  return createHash('sha256').update(`${capability}\n${stableStringify(args)}`).digest('hex');
}

function isValidRequestId(value) {
  return typeof value === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(value);
}

/** 请求 id 只落在文件名白名单内（与宿主的 id 校验同一形状），因此可以安全地拼进路径。 */
function newRequestId() {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/** v2 控制通道是否在位：两个控制目录都存在才算。目录由宿主创建，本进程只探测不创建。 */
function controlDirsAvailable() {
  try {
    return fs.statSync(CONTROL_INBOX).isDirectory() && fs.statSync(CONTROL_OUTBOX).isDirectory();
  } catch (error) {
    return false;
  }
}

/**
 * 终态帧判定（与宿主的写法逐一对齐）：
 * - 带 `result` 键：工作循环写入的终态，内含完整的 v1 回包；
 * - `ok:false`：控制通道在执行前就拒绝了这条提交（cause 说明原因），不会再有后续帧；
 * - `state` 是宿主的终态阶段名：幂等重提交可能拿到无 result 的过期终态。
 * 受理回执（state=QUEUED、无 result）与宿主阶段的中间值都不算终态，继续轮询。
 */
function isTerminalSubmitFrame(frame) {
  if (!frame || typeof frame !== 'object' || Array.isArray(frame)) return false;
  if (Object.prototype.hasOwnProperty.call(frame, 'result')) return true;
  if (frame.ok === false) return true;
  return typeof frame.state === 'string' && TERMINAL_PHASES.has(frame.state);
}

/**
 * 终态帧 → 输出回包。带 result 的帧：v1 回包原样铺开，外层补 v2 的协议版本与宿主阶段；
 * 不带 result 的终态帧：提交被控制通道拒绝（或命中无 result 的过期终态），合成一条
 * v1 形状的失败回包——cause 原文保留在 reason 里，错误码沿用 cause 内嵌的宿主码。
 * cause 没有「E_码/」前缀时按来源分两支：有 cause 文本的（digest conflict、
 * enqueue failed）是这次提交本身站不住，按用法类报 E_TRANSPORT_MALFORMED，
 * 让调用方换 id 重投而不是按宿主故障停下；连 cause 都没有的（终态没落住）才是
 * 宿主侧故障，报 E_INTERNAL。
 */
function submitOutcome(frame) {
  const state = typeof frame.state === 'string' ? frame.state : null;
  if (frame.result && typeof frame.result === 'object' && !Array.isArray(frame.result)) {
    return { ...frame.result, v: CONTROL_PROTOCOL_VERSION, state };
  }
  const cause = typeof frame.cause === 'string' ? frame.cause : '';
  const embedded = /^(E_[A-Z_]+)\//.exec(cause);
  const code = embedded ? embedded[1] : (cause ? 'E_TRANSPORT_MALFORMED' : 'E_INTERNAL');
  return {
    v: CONTROL_PROTOCOL_VERSION,
    id: typeof frame.id === 'string' ? frame.id : null,
    state,
    ok: false,
    reason: cause
      ? `the control channel refused this submission: ${cause}`
      : `the terminal frame carries no result (state=${state || 'unknown'})`,
    error: { code, retryable: false, exitCode: EMBEDDED_EXIT_CODES[code] ?? EXIT_INTERNAL },
  };
}

/**
 * 写一条控制请求并等它的唯一回包。控制回包按请求文件名落盘、一次写成，
 * 所以读到与本次请求 id 相符的帧就是答复。等不到回包（目录消失、写失败、超时）
 * 返回 null，由调用方决定退出码；stderr 已写明原因。
 * quiet 供主流程收尾时的附带请求使用：那些路径随后还要在 stderr 上给出单行
 * 机器可读的结论，过程性说明必须让位——stdout 为空时上层会把整段 stderr
 * 当作回包来解析，多一行文字就解析不动了。
 */
function controlRoundTrip(op, payload, budgetMs = CONTROL_BUDGET_MS, quiet = false) {
  if (!controlDirsAvailable()) {
    if (!quiet) {
      process.stderr.write('control channel unavailable: run/control-inbox and run/control-outbox are missing\n');
    }
    return null;
  }
  const id = newRequestId();
  const request = { v: CONTROL_PROTOCOL_VERSION, op, id, ...payload };
  const part = path.join(CONTROL_INBOX, `.${id}.json.part`);
  const target = path.join(CONTROL_INBOX, `${id}.json`);
  try {
    fs.writeFileSync(part, `${JSON.stringify(request)}\n`);
    fs.renameSync(part, target);
  } catch (error) {
    if (!quiet) process.stderr.write(`control request write failed: ${error.code || error.message}\n`);
    return null;
  }
  const replyFile = path.join(CONTROL_OUTBOX, `${id}.json`);
  const deadline = performance.now() + budgetMs;
  let delay = MIN_DELAY_MS;
  while (performance.now() < deadline) {
    const frame = readJson(replyFile);
    if (frame && frame.id === id) {
      try {
        fs.rmSync(replyFile, { force: true });
      } catch (error) {
        // 回包由宿主按寿命回收，删除失败不影响本次结果。
      }
      return frame;
    }
    sleep(delay);
    delay = Math.min(delay * 2, MAX_DELAY_MS);
  }
  if (!quiet) process.stderr.write(`no reply for ${op} within ${budgetMs}ms\n`);
  return null;
}

function statusCommand(targetId) {
  if (!targetId) return fail(process.stderr, 'status requires a request id', EXIT_USAGE);
  if (!isValidRequestId(targetId)) {
    return fail(process.stderr, `request id must match [A-Za-z0-9_-]{1,64}: ${targetId}`, EXIT_USAGE);
  }
  const reply = controlRoundTrip('status', { targetId });
  if (!reply) return FALLBACK_EXIT;
  process.stdout.write(`${JSON.stringify(reply)}\n`);
  if (reply.found === true) return EXIT_OK;
  if (reply.found === false) return EXIT_NOT_FOUND;
  return FALLBACK_EXIT;
}

function cancelCommand(targetId) {
  if (!targetId) return fail(process.stderr, 'cancel requires a request id', EXIT_USAGE);
  if (!isValidRequestId(targetId)) {
    return fail(process.stderr, `request id must match [A-Za-z0-9_-]{1,64}: ${targetId}`, EXIT_USAGE);
  }
  const reply = controlRoundTrip('cancel', { targetId });
  if (!reply) return FALLBACK_EXIT;
  process.stdout.write(`${JSON.stringify(reply)}\n`);
  const outcome = typeof reply.outcome === 'string' ? reply.outcome : null;
  if (outcome === 'CANCELLED') return EXIT_OK;
  if (outcome === 'COMPLETED') {
    process.stderr.write(`request already settled (state=${reply.state ?? 'unknown'}); nothing to cancel\n`);
    return EXIT_OK;
  }
  if (outcome === 'CANCEL_REQUESTED') {
    process.stderr.write(`host did not confirm the cancellation; follow up with ${CMD} status ${targetId}\n`);
    return EXIT_RETRY_LATER;
  }
  if (outcome === 'NOT_FOUND') return EXIT_NOT_FOUND;
  return FALLBACK_EXIT;
}

function controlHealth() {
  const reply = controlRoundTrip('health', {});
  if (!reply) return null;
  return {
    channelLive: reply.channelLive === true,
    queueDepth: Number.isFinite(reply.queueDepth) ? reply.queueDepth : null,
    oldestQueuedAgeMs: Number.isFinite(reply.oldestQueuedAgeMs) ? reply.oldestQueuedAgeMs : null,
  };
}

/**
 * 退出码取自响应里的 error.exitCode，由宿主单源决定。
 * 客户端不再自己按错误名分类——那样两端的分类会随版本漂移。
 */
function report(response, responseFile, outFile, capability) {
  let exitCode = FALLBACK_EXIT;
  if (response.ok) {
    exitCode = EXIT_OK;
    const source = Array.isArray(response.artifacts) ? response.artifacts[0] : null;
    if (source) {
      // 交付落点：调用方给了全路径就按它落；没给就落到 out/<kind>/ 下的默认名。
      // 「没给」不等于「不要文件」——产物一律交付，真实路径以本回包的 artifacts 为准
      // （USAGE 明写：调用方不该按自己传的路径去找文件）。相对路径在参数校验处已被拒。
      const target = outFile || defaultOutPath(capability, response.artifacts);
      try {
        // 缺省落点时目录由本进程建（宿主也会建；沙盒把它删掉过一次也不该因此失败）。
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.copyFileSync(source, target);
        response.artifacts = [target];
      } catch (error) {
        // 能力本身成功了：退出码仍取宿主的判定。但 stdout 不再指着一个没落地的目标路径——
        // 该产物槽位置空并标出交付失败，机器读数也能发现这一层的问题。
        response.artifacts = ['', ...response.artifacts.slice(1)];
        response.artifactDelivery = 'failed';
        response.artifactDeliveryError = String(error.code || error.message);
        process.stderr.write(
          `artifact copy to ${target} failed: ${response.artifactDeliveryError}; ` +
            'exit code still reflects the capability result\n',
        );
      }
    }
  } else if (response.error && Number.isInteger(response.error.exitCode)) {
    exitCode = response.error.exitCode;
  }

  process.stdout.write(`${JSON.stringify(response)}\n`);
  try {
    fs.rmSync(responseFile, { force: true });
  } catch (error) {
    // 响应由宿主按寿命回收，删除失败不影响本次结果。
  }
  return exitCode;
}

/** v1 老路径：投件、轮询 outbox 同名响应。v2 不可用时它就是全部，行为保持不变。 */
function callV1(capability, args, ttl, deadline, parsed) {
  const id = newRequestId();
  const request = { v: PROTOCOL_VERSION, id, ts: Date.now(), capability, args, ttlMs: ttl };

  try {
    fs.mkdirSync(INBOX, { recursive: true });
    fs.mkdirSync(OUTBOX, { recursive: true });
  } catch (error) {
    return fail(process.stderr, `channel directories unavailable: ${error.code || error.message}`, FALLBACK_EXIT);
  }

  const target = path.join(INBOX, `${id}.json`);
  const part = path.join(INBOX, `.${id}.json.part`);
  try {
    fs.writeFileSync(part, `${JSON.stringify(request)}\n`);
    fs.renameSync(part, target);
  } catch (error) {
    return fail(process.stderr, `request write failed: ${error.code || error.message}`, FALLBACK_EXIT);
  }

  const responseFile = path.join(OUTBOX, `${id}.json`);
  let delay = MIN_DELAY_MS;
  let taken = false;
  while (performance.now() < deadline) {
    const response = readJson(responseFile);
    if (response) return report(response, responseFile, parsed.out, capability);
    // 请求文件被取走即说明宿主确实在消费；仍留在原地则多半是通道未启动或设备被冻结。
    if (!taken && !fs.existsSync(target)) taken = true;
    sleep(delay);
    delay = Math.min(delay * 2, MAX_DELAY_MS);
  }

  try {
    fs.rmSync(target, { force: true });
  } catch (error) {
    // 请求可能已被宿主取走，这里不影响退出码判定。
  }
  process.stderr.write(`${JSON.stringify({ error: 'E_TRANSPORT_TIMEOUT', requestTaken: taken, capability })}\n`);
  return EXIT_TIMEOUT;
}

/**
 * v2 失败后允许退回 v1 重走一次的判据：这条请求是否**确定**没有落进控制信箱。
 * submit 文件成功改名进 control-inbox 之后，宿主随时可能已把它取走执行——
 * 此后的任何通道异常（目录消失、回包消失、读失败）都不许回退重投：
 * 对非幂等动作那等于一次无人授权的重复执行。宁可把结论报成 UNKNOWN，
 * 让调用方用 status/cancel 跟进同一条请求。
 * cause 只在 submitted=false 时参与判断：投件写失败与通道从未在位都能证明
 * 请求没进信箱，允许回退；其余原因一律保守处理。
 */
function fallbackAllowed(submitted, cause) {
  if (submitted) return false;
  return cause === 'submit-write-failed' || cause === 'channel-never-present';
}

/**
 * 投件之后无法证实结局时的统一收场：单行机器可读的 UNKNOWN、退出码 6，
 * stdout 不给伪造结果。requestId 在这行回包里，供调用方用 status/cancel 跟进。
 */
function transportUnknown(capability, id) {
  process.stderr.write(`${JSON.stringify({
    error: 'E_TRANSPORT_UNKNOWN',
    id,
    capability,
    note: `v2 request was submitted; its outcome could not be confirmed — query with '${CMD} status <id>' or '${CMD} cancel <id>'; do not re-send the same call`,
  })}\n`);
  return EXIT_INTERNAL;
}

/**
 * v2 路径：submit 进控制通道，轮询同一份回包文件直到终态帧。
 * 返回退出码；返回 FALLBACK_TO_V1 表示「请求确定没有投进控制信箱」（投件写失败），
 * 退回 v1 重走一次是安全的。请求一旦投进信箱就不再回退：通道中途失联按 UNKNOWN
 * 收场，等待到点则先补发一次有界 cancel，再按宿主的证实收场。
 */
function callV2(capability, args, deadline, parsed) {
  // 入队前的最后一道本地预算检查：已经到点的预算不再投件。
  const remainingMs = Math.round(deadline - performance.now());
  if (remainingMs <= 0) return clientTimeout(capability, null);

  const id = newRequestId();
  const request = {
    v: CONTROL_PROTOCOL_VERSION,
    op: 'submit',
    id,
    capability,
    args,
    // 宿主有效期以本进程的等待预算为上限交给宿主夹紧：宿主排队多久是宿主的事，
    // 本进程等多久只由 --timeout 说，两者不是同一个截止时刻。
    hostTtlMs: Math.max(remainingMs, 1),
    payloadDigest: digestOf(capability, args),
  };
  const part = path.join(CONTROL_INBOX, `.${id}.json.part`);
  const target = path.join(CONTROL_INBOX, `${id}.json`);
  // submit 文件成功改名进 control-inbox 即视为已投递：此后宿主随时可能消费它，
  // 一切回退决策都以这个标记为前提（见 fallbackAllowed）。
  let submitted = false;
  try {
    fs.writeFileSync(part, `${JSON.stringify(request)}\n`);
    fs.renameSync(part, target);
    submitted = true;
  } catch (error) {
    // 投件没有落地（分片没写成或没改成正式名）：这条提交必然没被宿主见到，
    // 退回 v1 重走一次是安全的，不构成对已执行动作的重试。
    if (!fallbackAllowed(submitted, 'submit-write-failed')) return transportUnknown(capability, id);
    process.stderr.write(`v2 submit write failed: ${error.code || error.message}; falling back to v1\n`);
    return FALLBACK_TO_V1;
  }

  const replyFile = path.join(CONTROL_OUTBOX, `${id}.json`);
  let delay = MIN_DELAY_MS;
  while (performance.now() < deadline) {
    const frame = readJson(replyFile);
    if (isTerminalSubmitFrame(frame)) {
      return report(submitOutcome(frame), replyFile, parsed.out, capability);
    }
    if (!frame && !controlDirsAvailable()) {
      // 控制目录连同回包一起消失：v2 通道不复存在。请求已经投进信箱，宿主随时
      // 可能已把它取走执行——回退 v1 重投同一能力对非幂等动作就是重复执行，
      // 因此这里按 UNKNOWN 收场（单行 stderr，stdout 不给伪造结果），
      // requestId 在 stderr 回包里，交给调用方用 status/cancel 跟进。
      return fallbackAllowed(submitted, 'channel-disappeared')
        ? FALLBACK_TO_V1
        : transportUnknown(capability, id);
    }
    sleep(delay);
    delay = Math.min(delay * 2, MAX_DELAY_MS);
  }

  // 等待预算到点仍无终态：宿主可能仍在本有效期内执行这条请求。退回 v1 重投等于
  // 对结果未知的动作自动重试，绝对不做。直接把撤销意愿发给宿主并等一小会儿：
  // 请求文件还躺在信箱里没被认领时，宿主能顺路收走它并证实「执行前已撤销」，
  // 这次等待的结论就从「未知」收窄成「未执行」；证实不了仍按 UNKNOWN 交给调用方跟进。
  // 这里不抢先删自己的提交件——删掉它，宿主就少了「仍在信箱、未认领」这个可证的
  // 事实，取消只会得到 NOT_FOUND，收窄就永远发生不了。
  const cancelReply = controlRoundTrip('cancel', { targetId: id }, CANCEL_CONFIRM_BUDGET_MS, true);
  const outcome = cancelReply && typeof cancelReply.outcome === 'string' ? cancelReply.outcome : null;
  if (outcome === 'CANCELLED') {
    // 等待到点的事实不变（仍按超时退出码报），但结论明确：动作没有执行。
    process.stderr.write(`${JSON.stringify({
      error: 'E_TRANSPORT_TIMEOUT',
      id,
      state: 'CANCELLED',
      note: 'host confirmed the request was cancelled before execution',
    })}\n`);
    return EXIT_TIMEOUT;
  }
  return clientTimeout(capability, id);
}

function clientTimeout(capability, id) {
  const detail = { error: 'E_TRANSPORT_TIMEOUT', capability };
  if (id) {
    // v2 超时带出 requestId 与 UNKNOWN：动作执行与否此刻无法证实，跟进走 status/cancel。
    detail.id = id;
    detail.v = CONTROL_PROTOCOL_VERSION;
    detail.state = 'UNKNOWN';
  }
  process.stderr.write(`${JSON.stringify(detail)}\n`);
  return EXIT_TIMEOUT;
}

function callCommand(parsed) {
  const capability = parsed._[1];
  if (!capability) return fail(process.stderr, 'call requires a capability name', EXIT_USAGE);

  let args = {};
  if (parsed.json) {
    try {
      args = JSON.parse(parsed.json);
    } catch (error) {
      return fail(process.stderr, '--json is not valid JSON', EXIT_USAGE);
    }
    if (!args || typeof args !== 'object' || Array.isArray(args)) {
      return fail(process.stderr, '--json must be an object', EXIT_USAGE);
    }
  }

  // --out 只接受绝对路径：相对路径会把产物落进宿主管不到、也清不掉的目录。
  // 拒绝时给出两条正确写法：省略 --out（落到标准交付目录），或写全路径。
  if (parsed.out && !path.isAbsolute(parsed.out)) {
    return fail(
      process.stderr,
      `--out must be an absolute path (got "${parsed.out}"); omit --out and the file lands under ` +
        `${OUT_ROOT}/<kind>/ with a generated name (the path comes back in the reply), ` +
        `or pass a full path like ${OUT_ROOT}/shot/shot.png`,
      EXIT_USAGE,
    );
  }

  const ttl = Math.min(Math.max(Number(parsed.timeout) || DEFAULT_TTL_MS, 1000), 120000);
  // 本进程等待预算的起点只从单调钟取时：系统对时不能再拉长或压短这一次等待。
  const deadline = performance.now() + ttl;

  if (controlDirsAvailable()) {
    const viaV2 = callV2(capability, args, deadline, parsed);
    // 回退说明由 callV2 按失败点写一行（只发生在投件没写成的场合），这里不再重复。
    if (viaV2 !== FALLBACK_TO_V1) return viaV2;
  }
  return callV1(capability, args, ttl, deadline, parsed);
}

/**
 * Artifact delivery root: same tree as the host's ArtifactOut.
 * The four retention parameters below are mirrored host-side and compared
 * item-by-item by tests - change one side and the other must follow, or the
 * client-computed default path and the host's reaper diverge.
 */
const OUT_ROOT = path.join(ENTRY_DIR, 'out');
const OUT_SUBDIRS = {
  'screen.capture': 'shot',
  'screen.record': 'video',
  'audio.capture': 'audio',
};
const OUT_DEFAULT_SUBDIR = 'file';
const OUT_KEEP_PER_KIND = 20;
const OUT_MAX_TOTAL_BYTES = 268435456;
const OUT_MAX_AGE_MS = 604800000;
const OUT_RECENT_GRACE_MS = 600000;

/** 能力 → 交付子目录；没列到的一律落 file（与宿主 ArtifactOut.subdirFor 同规）。 */
function outSubdirFor(capability) {
  return OUT_SUBDIRS[capability] || OUT_DEFAULT_SUBDIR;
}

/** 默认产物名的扩展名：优先沿用宿主产物自己的扩展名（它是权威的，不用猜）。 */
function outExtensionFor(capability, artifacts) {
  const source = Array.isArray(artifacts) && typeof artifacts[0] === 'string' ? artifacts[0] : '';
  const fromHost = source ? path.extname(source).toLowerCase() : '';
  if (fromHost) return fromHost;
  return { 'screen.capture': '.png', 'screen.record': '.mp4', 'audio.capture': '.m4a' }[capability] || '.bin';
}

/** Default artifact name: `<prefix><sub>-<local time>-<4 random>.<ext>`; the prefix
 * follows the command name so deployments never cross-contaminate each other. */
function defaultOutPath(capability, artifacts) {
  const sub = outSubdirFor(capability);
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}` +
    `-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
  const salt = Math.random().toString(36).slice(2, 6);
  return path.join(OUT_ROOT, sub, `${CMD}-${sub}-${stamp}-${salt}${outExtensionFor(capability, artifacts)}`);
}

/** ask 在「此刻没有任何可用的呈现面」时的退出码：需要用户做动作（开悬浮窗权限或回到助手页）。 */
const EXIT_USER_ACTION = 4;

/** ask 的等待区间：小于 5 秒用户读不完问题，大于 120 秒占着调用方干等。 */
const ASK_MIN_TIMEOUT_MS = 5000;
const ASK_MAX_TIMEOUT_MS = 120000;
const ASK_DEFAULT_TIMEOUT_MS = 60000;
/** 问题与选项的长度上限，与宿主侧 EnvelopeCodec 的 MAX_ASK_* 同一组数（本进程先拒，走用法错误）。 */
const ASK_MAX_QUESTION_CHARS = 200;
const ASK_MAX_OPTION_CHARS = 60;
/**
 * 本地等待预算要比宿主承诺的等待多留一截。这一截要盖住两段：
 * ① 宿主认领收件箱的延迟——控制循环靠 FileObserver 唤醒，观察者失效时退化成
 *    每 5 秒一次的轮询（ControlChannelServer 的 IDLE_POLL_MS），最坏就是 5 秒；
 * ② 宿主上屏判定的预算（AskOverlayPresenter.ON_SCREEN_BUDGET_MS = 4000）与回包落盘。
 * 只留"收尾余量"（5 秒）时，① 一旦发生，正常收尾的回包会落在预算之外，
 * 用户在屏上答了的那一次答案会被当成"没有回包"丢掉。
 */
const ASK_BUDGET_MARGIN_MS = 12000;

/** 宿主自身包名：清单顶层 host.package 携带；缺席时返回 null，由命令给出改用 call 的指引。 */
function hostPackage() {
  const list = readJson(CAPABILITIES);
  const host = list && list.host ? list.host : null;
  return host && typeof host.package === 'string' && host.package ? host.package : null;
}

/**
 * 一键把宿主应用带回前台。
 *
 * 走的就是 app.launch 对宿主自身包名的唯一豁免（只带这一个参数）——前台任务收尾时
 * 用它把用户带回对话页，避免"AI 干完活、用户还停在别的应用里不知道"。
 * 实现上只是把命令改写为一次普通调用，后续重试、超时、UNKNOWN 语义全部与 call 一致。
 */
function homeCommand(parsed) {
  const pkg = hostPackage();
  if (!pkg) {
    return fail(
      process.stderr,
      'cannot resolve the host package: capabilities.json carries no host.package; ' +
        `fall back to: ${CMD} call app.launch --json '{"package":"<host package>"}'`,
      EXIT_USAGE,
    );
  }
  return callCommand({ ...parsed, _: ['call', 'app.launch'], json: JSON.stringify({ package: pkg }) });
}

/**
 * 向用户提一个问题（2..3 个选项 + 全部驳回），经自制悬浮卡呈现并等答复。
 *
 * 用途：助手正在前台操作别的应用、用户看不到对话页时，用它把问题当面摆到用户眼前，
 * 而不是在"已经不在前台的宿主对话页"里提问、让双方空等。
 * 契约：`--json '{"question":"...","options":["A","B"],"timeoutMs":60000}'`；
 * 答复帧三支皆为成功：`{kind:"option",index,label}` 选中 / `{kind:"reject_all"}` 这一组选项
 * 都不合适 / `{kind:"reask"}` 问题本身要重新组织。后两支都由助手重问（驳回重排选项，
 * 重新提问换问法），退出码都是 0；超时/无呈现面/忙各自走专属退出码。
 */
function askCommand(parsed) {
  if (!parsed.json) {
    return fail(
      process.stderr,
      'ask requires --json {"question":"...","options":["...","..."]}',
      EXIT_USAGE,
    );
  }
  let payload;
  try {
    payload = JSON.parse(parsed.json);
  } catch (error) {
    return fail(process.stderr, '--json is not valid JSON', EXIT_USAGE);
  }
  const question = payload && typeof payload.question === 'string' ? payload.question.trim() : '';
  const options = payload && Array.isArray(payload.options) ? payload.options : null;
  if (!question) return fail(process.stderr, 'ask requires a non-empty question string', EXIT_USAGE);
  if (!options || options.length < 2 || options.length > 3 ||
    options.some((item) => typeof item !== 'string' || !item.trim())) {
    return fail(process.stderr, 'ask requires 2..3 non-empty option strings', EXIT_USAGE);
  }
  // 长度上限与宿主侧同一组数（EnvelopeCodec 的 MAX_ASK_QUESTION_CHARS / MAX_ASK_OPTION_CHARS）。
  // 本进程先拒：超长的问题若交给宿主拒，会走成 E_TRANSPORT_MALFORMED → exit 6，
  // 把"改脚本就能修"的用法错误报成"宿主内部故障"，助手会当成宿主坏了而不再重试。
  if (question.length > ASK_MAX_QUESTION_CHARS) {
    return fail(process.stderr, `ask question is longer than ${ASK_MAX_QUESTION_CHARS} characters`, EXIT_USAGE);
  }
  if (options.some((item) => item.trim().length > ASK_MAX_OPTION_CHARS)) {
    return fail(process.stderr, `ask options must be at most ${ASK_MAX_OPTION_CHARS} characters each`, EXIT_USAGE);
  }
  const timeoutMs = Math.min(
    Math.max(Number(payload.timeoutMs) || ASK_DEFAULT_TIMEOUT_MS, ASK_MIN_TIMEOUT_MS),
    ASK_MAX_TIMEOUT_MS,
  );
  // 通道在位与否先记下来：投件之前就不在位，与投件之后等不到回包，是两件不同的事——
  // 前者问都没问出口，后者的问题可能已经摆到屏幕上、只是没有答复回来。
  const reachable = controlDirsAvailable();
  const reply = controlRoundTrip(
    'ask',
    { question, options: options.map((item) => item.trim()), timeoutMs },
    timeoutMs + ASK_BUDGET_MARGIN_MS,
    true,
  );
  if (!reply) {
    if (!reachable) {
      return fail(
        process.stderr,
        'ask needs the v2 control channel (run/control-inbox/outbox); there is no v1 equivalent',
        FALLBACK_EXIT,
      );
    }
    // 通道在位却没有回包：宿主侧可能正忙，或呈现协程被拖住。ask 不改任何状态，
    // 但它也拿不到「没有答复」以外的结论——按超时类别报，并写明可以重新提问。
    process.stderr.write(
      `${JSON.stringify({
        ok: false,
        error: 'E_ASK_NO_REPLY',
        state: null,
        note: 'no reply within the wait budget: no answer was received (the card may never have reached the screen); asking again is safe',
      })}\n`,
    );
    return EXIT_TIMEOUT;
  }
  process.stdout.write(`${JSON.stringify(reply)}\n`);
  if (reply.ok === true) return EXIT_OK;
  // 退出码优先取宿主给的 error.exitCode（分类单源在宿主侧 AskCodes）：两端各写一张
  // 分类表时，改一个前缀而漏了另一处就会把"忙"静默报成"内部故障"。前缀推断只作为
  // 旧宿主的兜底保留。
  const declared = reply.error && Number.isInteger(reply.error.exitCode) ? reply.error.exitCode : null;
  if (declared !== null) return declared;
  const cause = typeof reply.cause === 'string' ? reply.cause : '';
  const embedded = /^(E_[A-Z_]+)\//.exec(cause);
  const code = embedded ? embedded[1] : cause;
  if (code === 'E_ASK_BUSY') return EXIT_RETRY_LATER;
  if (code === 'E_ASK_NO_SURFACE') return EXIT_USER_ACTION;
  if (code === 'E_ASK_TIMEOUT') return EXIT_TIMEOUT;
  return FALLBACK_EXIT;
}

function main() {
  const argv = process.argv.slice(2);
  let parsed;
  try {
    parsed = parseArgs(argv);
  } catch (error) {
    if (error instanceof UsageError) return fail(process.stderr, error.message, EXIT_USAGE);
    throw error;
  }
  const command = parsed._[0];

  if (parsed.help || !command) {
    usage(parsed.help ? process.stdout : process.stderr);
    return parsed.help ? EXIT_OK : EXIT_USAGE;
  }

  if (!COMMAND_OPTIONS.has(command)) {
    usage(process.stderr);
    return EXIT_USAGE;
  }

  try {
    checkOptions(command, parsed);
  } catch (error) {
    return fail(process.stderr, error.message, EXIT_USAGE);
  }

  const maxPositionals = command === 'call' || command === 'status' || command === 'cancel' ? 2 : 1;
  if (parsed._.length > maxPositionals) {
    return fail(process.stderr, `unexpected argument '${parsed._[maxPositionals]}'`, EXIT_USAGE);
  }

  if (command === 'doctor') {
    // 自检只做静态装配检查：清单可读、信箱目录可写，回答的是「装配齐不齐」。
    // 它不测量通道消费是否在进展，也不证明下一次调用能通——那需要端到端探测，
    // --probe 时走 v2 控制通道的 health，与这里的静态结论分层报告。
    // 沙盒插件自己的 doctor 会额外查挂载点解析与工具 API，
    // 两处报的字段同名，助手不必猜是哪一层的输出。
    const list = readJson(CAPABILITIES);
    const caps = list && Array.isArray(list.capabilities) ? list.capabilities : null;
    const dirs = [INBOX, OUTBOX, path.join(RUN_DIR, 'artifacts')];
    const writable = {};
    let allWritable = true;
    for (const dir of dirs) {
      let ok = false;
      try {
        fs.accessSync(dir, fs.constants.W_OK | fs.constants.X_OK);
        ok = true;
      } catch (error) {
        allWritable = false;
      }
      writable[dir] = ok;
    }
    const report2 = {
      command: 'doctor',
      entryDir: ENTRY_DIR,
      protocol: PROTOCOL_VERSION,
      pack: {
        capabilities: !!list,
        guide: fs.existsSync(path.join(ENTRY_DIR, 'USAGE.md')),
      },
      mailbox: { dirs: writable, writable: allWritable },
      manifest: list && {
        // 宿主清单当前不携带时间字段，generatedAt 恒为缺省；保留这个键只为向后兼容。
        generatedAt: list.generatedAt,
        capabilities: caps ? caps.length : 0,
        usable: caps ? caps.filter((c) => c.usable === true).length : 0,
      },
      // channelLive 只有端到端探测才有资格给出：默认恒为 null，--probe 拿到控制通道
      // 回话时才填宿主报的实况值。静态装配检查不能替它作答。
      channelLive: null,
    };
    // ok 的语义是「静态装配就绪」：清单可解析且含能力数组、三个信箱目录可写。
    // 它不表示「调用能通」——端到端是否可用，要看 --probe 或一次真实调用的回包。
    report2.ok = !!list && allWritable && !!caps;
    if (parsed.probe) {
      const health = controlHealth();
      report2.probe = health ? 'ok' : 'unavailable';
      if (health) {
        report2.channelLive = health.channelLive;
        report2.queueDepth = health.queueDepth;
        report2.oldestQueuedAgeMs = health.oldestQueuedAgeMs;
      }
    }
    process.stdout.write(`${JSON.stringify(report2, null, 2)}\n`);
    if (!report2.ok) {
      process.stderr.write(
        !list
          ? 'capabilities.json missing or unparsable: start the assistant page once, then enable the sandbox\n'
          : 'mailbox directories are not usable: ' +
            Object.keys(writable).filter((d) => !writable[d]).join(', ') + '\n',
      );
    }
    // 探活失败不回头否定静态装配结论：退出码只由静态检查决定。
    return report2.ok ? EXIT_OK : FALLBACK_EXIT;
  }

  if (command === 'capabilities') {
    const list = readJson(CAPABILITIES);
    process.stdout.write(`${JSON.stringify(list, null, 2)}\n`);
    return list ? EXIT_OK : FALLBACK_EXIT;
  }

  if (command === 'status') return statusCommand(parsed._[1]);
  if (command === 'cancel') return cancelCommand(parsed._[1]);
  if (command === 'home') return homeCommand(parsed);
  if (command === 'ask') return askCommand(parsed);
  return callCommand(parsed);
}

// 直接执行时跑主流程；被测试 require 时只导出纯函数，不产生任何副作用。
module.exports = { digestOf, stableStringify, parseArgs, isTerminalSubmitFrame, submitOutcome, fallbackAllowed };

if (require.main === module) {
  process.exit(main());
}
