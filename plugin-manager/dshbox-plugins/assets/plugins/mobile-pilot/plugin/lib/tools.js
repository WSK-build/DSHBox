'use strict'

/**
 * 把手机助手的能力定义为一组 DSH 原生工具（与 todo_write / bash 同级）。
 *
 * 暴露粒度：一个通用入口（phone_call）+ 高频语义工具，不逐条铺开能力清单，
 * 以控制系统提示体积与工具选择成本。
 *
 * 危险与低频通路不进专用工具：`sys.shell`（用户逐条开关的危险能力）与坐标注入
 * （ui.tap / ui.swipe / ui.text）只能经 phone_call 显式点名，专用工具数量固定为 14 个。
 *
 * 其中一个（phone_ask）不走能力通路：它对应入口的 `ask` 子命令，把问题摆成一张卡当面问用户，
 * 因此它不出现在能力清单里，也不经 phone_call。
 *
 * 每个 description 都写明调用约束，用于约束下游行为：
 *   · 优先节点级定位（nodeId / selector），坐标仅作兜底；
 *   · 动作派发成功不代表界面已变化，需以 phone_node / phoneWaitFor / 截图复核。
 * 返回体统一带同一组结构化字段（surface / degraded / retryable / exitCode / elapsedMs /
 * requestId / state），
 * text 只作人类摘要：机器判读一律读字段，不许逼调用方解析文字 —— 超时、降级这类
 * 只在字段里看得准的判据，写进摘要就会被读错。
 * 本模块只负责**定义**工具；注册在 lib/index.js 的 apply() 中完成。
 */

const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { describe, hintFor } = require('./pilot')
const { readManifest } = require('./mount')

/** ui.snapshot 压缩输出中最多展示的节点行数，避免整棵树进入上下文。 */
const TREE_LINES = 200

/** 落盘文件名的前缀：清理只按这一前缀在本目录内进行，不递归、不碰其他文件。 */
const SNAPSHOT_PREFIX = 'pilot-snapshot-'
const SNAPSHOT_SUFFIX = '.json'

/** 截图默认落盘名的前缀与后缀：与快照共用同一套保留策略，只是扩展名不同。 */
const SHOT_PREFIX = 'pilot-shot-'
const SHOT_SUFFIX = '.png'

/** 快照落盘保留份数：调用方只读最近几份，旧份留着没有读者，却会把工作区撑满。 */
const SNAPSHOT_KEEP = 20

/**
 * 目录可用性判定：已存在且是目录才算数；需要新建时只建一层，
 * 父目录都不存在就放弃该候选 —— 落盘目录取不到是降级，凭空造一棵陌生路径的树不是。
 */
function ensureDir (dir) {
  if (!dir) return null
  try {
    if (fs.existsSync(dir)) return fs.statSync(dir).isDirectory() ? dir : null
    if (!fs.existsSync(path.dirname(dir))) return null
    fs.mkdirSync(dir)
    return dir
  } catch {
    return null
  }
}

/**
 * 交付目录：本模块产物的落点。
 *
 * 候选链：调用方指定 → MOBILE_PILOT_OUT_DIR → /opt/pilot/out/<kind> → 系统临时目录。
 * **没有 process.cwd() 这一档**：命令的工作目录在沙箱里就是用户的工作区
 * （/root/projects），产物落在那儿宿主既看不见也清不掉——那正是"截图堆满工作区"的成因。
 * /opt/pilot/out 下的分类与宿主 ArtifactOut 同源（shot/video/audio/file）。
 */
const OUT_ROOT = '/opt/pilot/out';
const OUT_KIND_DIRS = { shot: 'shot', video: 'video', audio: 'audio', file: 'file' };

function outDir (option, kind = 'file') {
  const sub = OUT_KIND_DIRS[kind] || OUT_KIND_DIRS.file
  const candidates = [option, process.env.MOBILE_PILOT_OUT_DIR, path.join(OUT_ROOT, sub), os.tmpdir()]
  for (const dir of candidates) {
    const ready = ensureDir(dir)
    if (ready) return ready
  }
  return os.tmpdir()
}

function stamp () {
  return new Date().toISOString().replace(/[:.]/g, '-')
}

/**
 * 只删本模块自己写出的快照与截图，按修改时间保留最近 SNAPSHOT_KEEP 份。
 * 匹配条件收紧到「前缀 + 后缀」：同目录里可能有调用方自己的文件，删错比留着更贵。
 */
function pruneSnapshots (dir, prefix = SNAPSHOT_PREFIX, suffix = SNAPSHOT_SUFFIX) {
  let names
  try {
    names = fs.readdirSync(dir)
  } catch {
    return
  }
  const dated = []
  for (const name of names) {
    if (!name.startsWith(prefix) || !name.endsWith(suffix)) continue
    const file = path.join(dir, name)
    try {
      const st = fs.statSync(file)
      if (st.isFile()) dated.push({ file, mtimeMs: st.mtimeMs })
    } catch {
      // 取不到时间戳就不判断，宁可不删也不误删。
    }
  }
  if (dated.length <= SNAPSHOT_KEEP) return
  dated.sort((a, b) => b.mtimeMs - a.mtimeMs)
  for (const { file } of dated.slice(SNAPSHOT_KEEP)) {
    try { fs.unlinkSync(file) } catch { /* 已被别的进程收回 */ }
  }
}

/** 将原始 JSON 落盘并返回路径：压缩展示丢弃的细节仍可检索。 */
function dump (dir, name, value) {
  try {
    const file = path.join(dir, `pilot-${name}-${stamp()}${SNAPSHOT_SUFFIX}`)
    fs.writeFileSync(file, JSON.stringify(value, null, 1))
    pruneSnapshots(dir, `pilot-${name}-`)
    return file
  } catch {
    return ''
  }
}

/**
 * 从通道回包提取所有工具共有的结构化字段。
 *
 * 这些字段在 schema 里声明、每条 return 路径都要赋实际值（空串 / 0 / false）：
 * defineTool 会即时校验，缺字段与多余键都会报错。空值语义写进 schema description，
 * 机器判读只认字段 —— text 是给人看的摘要，超时、降级这类判据不进文字。
 */
function channelFields (r) {
  const surface = r && typeof r.surface === 'string' ? r.surface : ''
  return {
    surface,
    // degraded 只在回包携带执行面时有意义：surface 为空说明本次没有可判定的执行面，固定 false。
    degraded: surface !== '' && r.degraded === true,
    degradedFrom: r && typeof r.degradedFrom === 'string' ? r.degradedFrom : '',
    retryable: !!(r && r.ok !== true && r.retryable === true),
    exitCode: r && r.ok === true ? 0 : (r && Number.isInteger(r.exitCode) ? r.exitCode : 6),
    elapsedMs: r && Number.isFinite(r.elapsedMs) ? Math.trunc(r.elapsedMs) : 0,
    // v2 通路才携带请求 id 与宿主阶段；v1 回包（及本地构造的回包）没有这两个键，按空串报"不适用"。
    requestId: r && typeof r.requestId === 'string' ? r.requestId : '',
    state: r && typeof r.state === 'string' ? r.state : ''
  }
}

/**
 * 本地构造（未发生通道调用）的回包字段：没有执行面与耗时可言，一律给空值；
 * 成功按 0 计退出码，失败属包装层自身故障，按约定计 6。请求 id 与阶段同样不适用。
 */
function localFields (ok) {
  return {
    surface: '', degraded: false, degradedFrom: '', retryable: false,
    exitCode: ok ? 0 : 6, elapsedMs: 0, requestId: '', state: ''
  }
}

/**
 * 产物交付失败的判定。宿主/入口把产物复制到落点失败时，能力本身执行成功（回包仍 ok），
 * 但 artifacts 的槽位是空串并标 artifactDelivery:'failed' —— 那条路径从未写出文件。
 * 两个形状都要认：显式标记，或成功回包里槽位为空串（不标标记的宿主）。
 */
function artifactDeliveryFailed (r) {
  if (!r || typeof r !== 'object') return false
  if (r.artifactDelivery === 'failed') return true
  return r.ok === true && Array.isArray(r.artifacts) && r.artifacts.length > 0 && r.artifacts[0] === ''
}

/** 产物交付失败的统一文本：能力成功的事实与产物没落地的事实都要说，并给出改法。 */
function artifactDeliveryFailedText () {
  return '能力执行成功，但产物交付失败（复制到落点失败），文件未落地。\n' +
    '处置建议: 本地没有这个文件；改用 out 参数指向一个存在且可写的目录后重新发起。'
}

/**
 * 统一的输出外壳。所有字段均**声明**且每条 return 路径都赋实际值（空串 / 0 / false）：
 * defineTool 会即时校验 schema，缺字段与多余键都会报错。
 * text 只是人类摘要：机器判读用以上结构化字段，不要解析 text。
 */
function resultSchema (extra = {}) {
  return {
    type: 'object',
    additionalProperties: false,
    properties: {
      ok: { type: 'boolean', required: true, description: '本工具整体是否成功（ok=true）。' },
      code: { type: 'string', required: true, description: '失败时的错误码；成功为空串。' },
      text: { type: 'string', required: true, description: '人类可读摘要：状态、原因、处置建议。机器判读用以上结构化字段，不要解析 text。' },
      path: { type: 'string', required: true, description: '产物/落盘路径；无则为空串。' },
      surface: { type: 'string', required: true, description: '本次执行所在执行面（foreground / virtual 等）；空串=本次回包未携带执行面（数据读取类工具，或失败于选定执行面之前）。' },
      degraded: { type: 'boolean', required: true, description: '执行面是否被降级（如请求虚拟屏而实际落在前台）；仅 surface 非空时有意义，surface 为空时固定 false。' },
      degradedFrom: { type: 'string', required: true, description: '降级来源（被降掉的那个执行面）；空串=无降级来源。' },
      retryable: { type: 'boolean', required: true, description: '失败能否按原参数重试（取宿主 error.retryable）；成功固定 false。' },
      exitCode: { type: 'integer', required: true, description: '入口进程退出码：成功 0；失败取宿主 error.exitCode；包装层自身故障为 6，拿不到时也按 6 报。' },
      elapsedMs: { type: 'integer', required: true, description: '宿主回包耗时（毫秒）；拿不到时为 0。' },
      requestId: { type: 'string', required: true, description: '宿主侧请求 id（回包携带时原样给出）：用 mobile-pilot status / cancel 跟进同一条请求时要带上它；空串=本回包未携带（v1 通路或本地构造的回包）。' },
      state: { type: 'string', required: true, description: '宿主报的请求阶段（v2 终态帧才有，如 SUCCEEDED / UNKNOWN / CANCELLED）：UNKNOWN 表示执行与否无法证实，处置建议会指向 status/cancel；空串=本回包不适用（v1 回包没有阶段可言）。' },
      ...extra
    }
  }
}

const renderText = (_args, value) => [{ type: 'text', text: value.text }]

/** nodeId / selector 二选一的目标参数。 */
function targetParams () {
  return {
    nodeId: { type: 'integer', description: '来自最近一次 phone_observe 的 nodeId；界面变化后失效。' },
    selector: {
      type: 'object',
      additionalProperties: true,
      description: '按字段匹配：text / desc / id / class / index / package（同时给出则需全部匹配；index 计兄弟节点）。与 nodeId 二选一。'
    }
  }
}

const RECT_KEYS = ['left', 'top', 'right', 'bottom']

/**
 * 节点矩形的渲染。宿主交回的是平铺的 left/top/right/bottom（已裁到屏幕内），
 * 裁空时改成给 offscreen:true —— 没有 bounds 这个键，所以也不能照 "bounds" 去读：
 * 空矩形会被下游当成一个屏内可点的坐标。
 */
function renderBox (node) {
  if (!node) return '坐标=未知'
  if (node.offscreen === true) return 'offscreen=true（在此显示面上无可见面积，不可作点击目标）'
  if (RECT_KEYS.every((key) => Number.isFinite(node[key]))) {
    return `坐标=(${node.left},${node.top})-(${node.right},${node.bottom})`
  }
  return '坐标=未知'
}

/**
 * 控件树压缩为文本：保留有文本/描述或框架自报可点的节点，超量时指向落盘的完整 JSON。
 *
 * 快照里的 clickable 是框架原始位，宿主判"能不能点"用的是该节点自报的 actions 列表，
 * 两者可以不一致，因此这里必须把「以 phone_node 的 actions 为准」随树一起交出去。
 */
function renderTree (data, dumpPath) {
  const nodes = data && Array.isArray(data.nodes) ? data.nodes : []
  const screen = data && data.screen ? data.screen : null
  const head = [
    `package=${(data && data.package) || '?'}`,
    `nodes=${nodes.length}`,
    screen ? `screen=${screen.width}x${screen.height}@${screen.display}` : '',
    screen && screen.densityDpi ? `densityDpi=${screen.densityDpi}` : '',
    // 落盘失败时不能声称"完整内容"还在哪里：data 在本层没有另存副本，指过去就是让人白找。
    `full=${dumpPath || '（落盘失败，本层未保存完整副本）'}`
  ].filter(Boolean).join(' ')
  const legend = '说明：C=框架自报 clickable、S=scrollable；能不能真的点下去以 phone_node 的 actions 为准（二者可不一致，点错会回 E_NODE_NOT_ACTIONABLE）。offscreen 的节点没有可点面积。'
  const lines = [head, legend]
  let shown = 0
  for (const n of nodes) {
    const label = String((n && n.text) || (n && n.desc) || '').trim()
    const marked = !!n && (n.clickable === true || n.scrollable === true)
    if (!label && !marked) continue
    if (shown >= TREE_LINES) {
      lines.push(`…已展示 ${shown} 个，树上另有 ${nodes.length - shown} 个节点未列出（完整 JSON 见 full=）`)
      break
    }
    const cls = String((n && n.class) || '').split('.').pop()
    const flags = `${n.clickable === true ? 'C' : '-'}${n.scrollable === true ? 'S' : '-'}`
    lines.push(`id=${n.nodeId} ${cls} ${flags} ${renderBox(n)} ${label ? JSON.stringify(label) : '(无文本)'}`)
    shown++
  }
  return lines.join('\n')
}

function compact (value, limit = 400) {
  let text
  try { text = typeof value === 'string' ? value : JSON.stringify(value) } catch { text = String(value) }
  if (!text) return ''
  return text.length > limit ? `${text.slice(0, limit)}…(截断：余 ${text.length - limit} 字符未显示)` : text
}

/** 有则渲染、无则说明为什么没有：宿主对量不出位移的节点根本不写 advanced，写成 0 会被读成"一次都没动"。 */
function fieldOr (value, absentNote) {
  return value === undefined || value === null ? absentNote : String(value)
}

/**
 * 构造全部工具定义。
 * @param {{defineTool: Function, channel: object, outDirOption?: string}} deps
 * @returns {Array<object>}
 */
function buildTools ({ defineTool, channel, outDirOption }) {
  // 截图与快照分开：截图落 out/shot（用户会去看图库式的地方），
  // 压缩 JSON 与全量转储落 out/file（它们是排障附件，不是"照片"）。
  const dir = outDir(outDirOption, 'file')
  const shotDir = outDir(outDirOption, 'shot')
  const tools = []
  const push = (spec) => tools.push(defineTool(spec))

  /** 处置建议与摘要统一由通道层生成，工具不再各写一份文案。 */
  // 预览截断时由本层另存一份完整 JSON，并把路径写进截断说明：宿主侧的列表类能力没有分页与
  // 过滤参数，被截掉的那部分不留副本就再也取不回来。
  const summary = (r) => describe(r, (capability, value) => dump(dir, 'full', value))

  // ── 0. 通道状态 ───────────────────────────────────────────────────────
  push({
    name: 'phone_status',
    description:
      '报告手机助手通道状态：挂载点、能力清单条数与可用条数、Shizuku 后端（running/authorized/bound/trustedDisplay）。' +
      'probe=true 时执行一次 pkg.query 以验证通道可用（清单仅证明文件存在）：probe 失败则本工具整体失败，通道只有 probe 成功才算证实可用。' +
      '挂载点在而清单缺失或无法解析时报 E_WRAPPER_BAD_MANIFEST（ok=false），不再以空清单继续。' +
      'list=true 时逐条列出能力清单（可用 filter 收窄）：设备侧不必再自己 cat 那份 JSON 才知道有哪些能力。' +
      '开始手机任务前应先调用本工具：清单中的 usable / assistantTier / 拒绝状态决定可用路径。',
    parameters: {
      probe: { type: 'boolean', description: '是否执行一次 pkg.query 验证通道。默认 false。' },
      list: { type: 'boolean', description: '为 true 时附逐条能力清单（id | 类别 | 档位 | 上限 | usable | implemented | 可用面 | 需系统权限），取自挂载点里那份 capabilities.json。默认 false。' },
      filter: { type: 'string', description: '配合 list 使用：按 id 或类别的子串收窄，不区分大小写，例如 "ui." 或 "calendar"。' }
    },
    output: {
      schema: resultSchema({
        root: { type: 'string', required: true, description: '解析得到的挂载点；未找到挂载点时为空串。' },
        capabilities: { type: 'integer', required: true, description: '清单中的能力条数；清单缺失或无法解析时为 0。' },
        usable: { type: 'integer', required: true, description: '当前 usable=true 的条数。' },
        shizuku: { type: 'string', required: true, description: 'backend.shizuku 的 JSON 文本；清单未提供该字段时为空串。' },
        probeOk: { type: 'boolean', required: true, description: '请求 probe（probe=true）时表示 pkg.query 验证是否成功；probe 未请求时固定为 true（此时无判读意义）。' }
      }),
      render: renderText
    },
    execute: async (args) => {
      const probeRequested = !!(args && args.probe === true)
      const resolved = channel.resolve()
      if (!resolved.root) {
        const tried = resolved.tried.map((t) => `${t.source}:${t.dir}(entry=${t.hasEntry ? 'y' : 'n'})`).join(' ')
        return { ok: false, code: 'E_WRAPPER_NO_MOUNT', text: `未找到手机助手挂载点。已尝试：${tried}`, path: '', root: '', capabilities: 0, usable: 0, shizuku: '', ...localFields(false), probeOk: !probeRequested }
      }
      const manifest = readManifest(resolved.info.manifest)
      if (!manifest || !Array.isArray(manifest.capabilities)) {
        // 挂载点在而清单读不出来：以空清单继续会把「文件在」伪装成「通道可用」，
        // 这里必须以失败收场，让调用方先重铺资产，而不是拿着 0 条清单往下走。
        return {
          ok: false,
          code: 'E_WRAPPER_BAD_MANIFEST',
          text: `挂载点在（${resolved.root}），但能力清单缺失或无法解析（${resolved.info.manifest}）。\n处置建议: ${hintFor({ code: 'E_WRAPPER_BAD_MANIFEST' })}`,
          path: '', root: resolved.root, capabilities: 0, usable: 0, shizuku: '',
          ...localFields(false), probeOk: !probeRequested
        }
      }
      const caps = manifest.capabilities
      const usable = caps.filter((c) => c.usable === true).length
      const backend = manifest.backend ? manifest.backend : null
      const shizuku = backend && backend.shizuku ? JSON.stringify(backend.shizuku) : ''
      const lines = [
        `挂载点 ${resolved.root}（来源 ${resolved.source}）`,
        `能力 ${caps.length} 条，usable ${usable} 条`,
        resolved.info.entryExecutable ? '入口具备执行位' : '入口无执行位（本层以 node 执行，属正常路径）',
        shizuku ? `shizuku: ${shizuku}` : 'shizuku: 清单未提供 backend 字段',
        `清单生成时间: ${manifestTime(manifest, resolved.info.manifest)}`,
        `落盘目录: ${dir}`
      ]
      if (args && args.list === true) {
        // 清单本身没有对应的能力 id（宿主只把它铺成文件），所以列举走这里：
        // 读的就是挂载点里那份 capabilities.json，字段口径与文件一致，不另算一套。
        const needle = typeof args.filter === 'string' ? args.filter.toLowerCase() : ''
        const rows = caps.filter((c) => !needle ||
          `${c.id || ''} ${c.category || ''}`.toLowerCase().includes(needle))
        lines.push(`清单 ${rows.length}/${caps.length} 条（来源 ${resolved.info.manifest}${needle ? `，filter="${args.filter}"` : ''}）`)
        lines.push('id | 类别 | 档位 | 上限 | usable | implemented | 可用面 | 需系统权限')
        for (const c of rows) {
          lines.push([
            c.id || '', c.category || '', c.assistantTier || '', c.ceiling || '',
            String(c.usable === true), String(c.implemented === true),
            Array.isArray(c.surfaces) ? c.surfaces.join('+') : '', c.systemPermission || '-',
          ].join(' | '))
        }
        const blocked = caps.filter((c) => c.usable !== true && c.implemented === true)
        if (blocked.length) {
          lines.push(`不可调的已实现能力 ${blocked.length} 条：${blocked.map((c) => c.id).join('、')}；` +
            '逐条原因看上面的表与手册，本层不重复 guide 那句')
        }
      }
      if (probeRequested) {
        const r = await channel.call('pkg.query', {})
        if (!r.ok) {
          // probe 是对「清单只是文件」的补证：它失败时整体必须跟着失败，
          // 否则 ok:true 会让调用方误以为通道已经证实可用。
          lines.push(`probe pkg.query: ${r.code || '失败'}`)
          lines.push('probe 失败，通道未证实可用：清单只能证明文件存在，不代表宿主能应答。')
          const hint = hintFor(r)
          if (hint) lines.push(`处置建议: ${hint}`)
          return { ok: false, code: r.code || '', text: lines.join('\n'), path: '', root: resolved.root, capabilities: caps.length, usable, shizuku, ...channelFields(r), probeOk: false }
        }
        lines.push(`probe pkg.query: ok ${r.elapsedMs}ms（${r.data && r.data.count} 个应用）`)
        return { ok: true, code: '', text: lines.join('\n'), path: '', root: resolved.root, capabilities: caps.length, usable, shizuku, ...channelFields(r), probeOk: true }
      }
      return { ok: true, code: '', text: lines.join('\n'), path: '', root: resolved.root, capabilities: caps.length, usable, shizuku, ...localFields(true), probeOk: true }
    }
  })

/**
 * 清单的生成时刻。
 *
 * 宿主不往清单里写时间字段（顶层只有 protocol / entry / backend / capabilities），
 * 手册说的口径是"这份文件的修改时间就是内容铺下去的时刻"。所以清单里有 `generatedAt`
 * 就用它（往后兼容），否则报文件时间；连文件都读不到时说明原因，不停在"未知"上。
 */
function manifestTime (manifest, file) {
  if (manifest && manifest.generatedAt) return String(manifest.generatedAt)
  try {
    return `清单未带时间字段，按文件时间 ${fs.statSync(file).mtime.toISOString()}`
  } catch {
    return '清单未带时间字段，且读不到文件时间'
  }
}

  // ── 1. 通用入口 ───────────────────────────────────────────────────────
  push({
    name: 'phone_call',
    description:
      '调用任意一条 pilot 能力（通用入口）。参数原样透传，返回保留 surface / degraded / retryable / exitCode / elapsedMs 等结构化字段（机器判读读字段，不解析 text）。' +
      '应优先使用专用 phone_* 工具；仅当所需能力未被覆盖（contact.read、cal.read、loc.read、media.*、audio.capture、appops.set 等）时使用本工具。' +
      '危险与低频通路只在这里点名：sys.shell 是用户逐条开关的危险能力，坐标注入（ui.tap / ui.swipe / ui.text）也没有专用工具，' +
      '两者都要求调用方明确写出能力 id 与参数，不给一键包装。' +
      '返回 E_AWAITING_CONSENT 指的是 Pilot 审批在等答复 —— 这是本模块自己的许可，与 Android 系统弹框无关：' +
      '可在三处任一处答复，且同一时刻只呈现一处：助手页内的那张卡（该页在前台时）、悬浮卡、通知栏的「允许/拒绝」；' +
      '宿主界面位于最前面时悬浮卡不出现，请到通知栏或助手页答复，随后以完全相同的参数重试即可接上同一张框。' +
      '采集类能力（screen.capture / screen.record）在此之外还可能需要 Android 系统的屏幕采集同意（MediaProjection 系统弹框），' +
      '那是另一种许可，本模块审批里的「允许」代替不了它。',
    parameters: {
      capability: { type: 'string', required: true, description: '能力 id，例如 pkg.query / contact.read / sys.shell。' },
      args: { type: 'object', additionalProperties: true, description: '该能力的参数对象。' },
      timeoutMs: { type: 'integer', description: '覆盖本层超时（毫秒）；需要用户确认的能力建议 60000 以上。入口只接受 1000..120000，本层按该区间夹紧并在摘要里说明。' },
      out: { type: 'string', description: '产物落盘路径（对应 --out，如 screen.capture）。' }
    },
    output: { schema: resultSchema(), render: renderText },
    execute: async (args) => {
      const r = await channel.call(args.capability, args.args || {}, { timeoutMs: args.timeoutMs, out: args.out })
      if (artifactDeliveryFailed(r)) {
        // 带 --out 的调用同样有产物交付这一环：能力成功而文件没落地时，
        // 拿 ok:true 收场等于让调用方去读一个不存在的地方。
        return {
          ok: false,
          code: 'E_ARTIFACT_WRITE',
          text: artifactDeliveryFailedText(),
          path: '',
          ...channelFields(r),
          retryable: false,
          exitCode: 6
        }
      }
      return { ok: r.ok, code: r.code || '', text: summary(r), path: (r.artifacts && r.artifacts[0]) || '', ...channelFields(r) }
    }
  })

  // ── 2. 读取界面 ───────────────────────────────────────────────────────
  push({
    name: 'phone_observe',
    description:
      '读取当前界面控件树（ui.snapshot），返回压缩后的可见文本与坐标，完整 JSON 落盘并在 path 给出路径；' +
      '落盘失败时本工具失败（E_ARTIFACT_WRITE、artifactOk=false），本层不保存完整副本。调用约束：' +
      '① 每次操作前重新读取 —— nodeId 随界面变化失效；' +
      '② 虚拟屏与主屏（用户正在看的屏幕）的坐标系不同（例如虚拟屏 1080×1920、主屏 1260×2800），坐标不可跨显示面复用；' +
      '③ 控件树的文本内容可能与实际显示不一致，需要确认时以 phone_capture + read_image 为准；' +
      '④ 快照里的 clickable 是框架原始位，能否点下去以 phone_node 的 actions 为准。',
    parameters: {},
    output: {
      schema: resultSchema({
        nodes: { type: 'integer', required: true, description: '节点总数；读取失败时为 0。' },
        artifactOk: { type: 'boolean', required: true, description: '快照 JSON 是否成功落盘；false 时 path 为空串且本层未保存完整副本。ui.snapshot 失败（无快照可落盘）时固定 true。' }
      }),
      render: renderText
    },
    execute: async () => {
      const r = await channel.call('ui.snapshot', {})
      if (!r.ok) return { ok: false, code: r.code || '', text: summary(r), path: '', nodes: 0, ...channelFields(r), artifactOk: true }
      const data = r.data || {}
      const file = dump(dir, 'snapshot', data)
      const nodes = Array.isArray(data.nodes) ? data.nodes.length : 0
      if (!file) {
        // 快照读到了但存不下来：data 在本层没有副本，绝不能报成功，
        // 也不能把「完整内容」指向一个不存在的地方。
        const text = `${renderTree(data, '')}\n处置建议: ${hintFor({ code: 'E_ARTIFACT_WRITE' })}`
        return { ok: false, code: 'E_ARTIFACT_WRITE', text, path: '', nodes, ...channelFields(r), retryable: false, exitCode: 6, artifactOk: false }
      }
      return {
        ok: true,
        code: '',
        text: renderTree(data, file),
        path: file,
        nodes,
        ...channelFields(r),
        artifactOk: true
      }
    }
  })

  // ── 3. 点击 / 长按 ────────────────────────────────────────────────────
  push({
    name: 'phone_click',
    description:
      '点击控件（ACTION_CLICK；long=true 为长按）。应使用 nodeId / selector，避免坐标。' +
      '先用 phone_node 确认该控件的 actions 含 click / long-click：clickable 为真而 actions 里没有 click 的控件确实存在，点下去回 E_NODE_NOT_ACTIONABLE。' +
      '返回 dispatched=true 仅表示动作已派发，不代表界面已变化：需用 phone_node / phone_waitFor / phone_capture 复核。',
    parameters: {
      ...targetParams(),
      long: { type: 'boolean', description: 'true 时改为长按（ui.longClick）。' }
    },
    output: { schema: resultSchema(), render: renderText },
    execute: async (args) => {
      const payload = args.nodeId != null ? { nodeId: args.nodeId } : { selector: args.selector || {} }
      const r = await channel.call(args.long === true ? 'ui.longClick' : 'ui.click', payload)
      return { ok: r.ok, code: r.code || '', text: summary(r), path: '', ...channelFields(r) }
    }
  })

  // ── 4. 写入文本 ───────────────────────────────────────────────────────
  push({
    name: 'phone_setValue',
    description:
      '将文本写入输入控件（ui.setValue，无需预先聚焦）。' +
      '部分应用的输入控件不处理该动作：返回 dispatched 成功但界面不变化。' +
      '回包的 requested / readBack 是信息不是判据（控件会重写输入：掩码、自动补全、maxLength）。' +
      '虚拟屏没有输入法通路；遇到不处理该动作的控件，应改为主屏（用户正在看的屏幕）输入或系统快捷入口，并以截图确认结果。',
    parameters: {
      ...targetParams(),
      text: { type: 'string', required: true, description: '要写入的文本（支持中文）。' }
    },
    output: { schema: resultSchema(), render: renderText },
    execute: async (args) => {
      const payload = args.nodeId != null ? { nodeId: args.nodeId } : { selector: args.selector || {} }
      payload.text = args.text
      const r = await channel.call('ui.setValue', payload)
      return { ok: r.ok, code: r.code || '', text: summary(r), path: '', ...channelFields(r) }
    }
  })

  // ── 5. 滚动 ───────────────────────────────────────────────────────────
  push({
    name: 'phone_scroll',
    description:
      '滚动控件（ui.scroll）。direction 必填 forward/backward；times 1..20 是**步数上限**而非下限。' +
      '回包分列两个计数：dispatched 是控件接受派发的次数，advanced 是观察到值变化的次数（控件在动画途中会吞掉或合并派发，' +
      '所以 advanced 才是走掉的格数）；movementVerified 只在量得出位移且两者一致时为真。' +
      '控件没有自报量程时宿主不写 advanced —— 那是"这里量不出"，不是"一次都没动"；此时仍需以 phone_observe / phone_waitFor 复核。' +
      '给了 until（目标值）时回包附 reached / target，stoppedEarly 与 stoppedReason 说明在哪一步、因为什么停下；' +
      'times 与 until 同时给出时 until 也受 times 约束，只给 until 时步数上限取宿主默认（回包的 stepLimit 才是本次实际允许几拍）。' +
      'until 的前提是该控件自报量程（见 phone_node 的 range）：部分厂商时钟滚轮不报量程，这类控件会回 E_NODE_NOT_ACTIONABLE 并指回 ui.node {range}。' +
      'setProgress 同样要求量程，所以无量程的滚轮没有"直接设值"这条路——只剩坐标路径（phone_call 的 ui.tap / ui.swipe），不要重发。',
    parameters: {
      ...targetParams(),
      direction: { type: 'string', required: true, enum: ['forward', 'backward'], description: '滚动方向。' },
      times: { type: 'integer', description: '本次调用的步数上限 1..20，默认 1；与 until 同时给出时 until 也受它约束。' },
      until: { type: 'number', description: '目标值：滚到该控件量程里的这个值就停。前提是控件自报 range，否则回 E_NODE_NOT_ACTIONABLE。' }
    },
    output: { schema: resultSchema(), render: renderText },
    execute: async (args) => {
      const payload = args.nodeId != null ? { nodeId: args.nodeId } : { selector: args.selector || {} }
      payload.direction = args.direction
      if (args.times != null) payload.times = args.times
      if (args.until != null) payload.until = args.until
      const r = await channel.call('ui.scroll', payload)
      if (!r.ok) return { ok: false, code: r.code || '', text: summary(r), path: '', ...channelFields(r) }
      const d = r.data || {}
      const lines = [
        `dispatched=${fieldOr(d.dispatched, '未提供')}（控件接受的派发次数） requested=${fieldOr(d.requested, '未提供')} stepLimit=${fieldOr(d.stepLimit, '未提供')} direction=${fieldOr(d.direction, '未提供')}`,
        `advanced=${fieldOr(d.advanced, '未提供（该控件不自报量程，位移量不出，须回读界面）')}（观察到值变化的次数） movementVerified=${fieldOr(d.movementVerified, '未提供')}`,
        d.until === undefined && d.target === undefined
          ? 'reached=未要求（未给 until 时宿主不写 reached：没要求到位不等于没到位）'
          : `reached=${fieldOr(d.reached, '未提供')} target=${fieldOr(d.target, '未提供')}`,
        `value=${fieldOr(d.value, '未提供（当前值请读 phone_node 的 range.current）')}`
      ]
      if (d.stoppedEarly === true) lines.push(`stoppedEarly: ${fieldOr(d.stoppedReason, '未给出原因')}`)
      if (d.note) lines.push(`note: ${d.note}`)
      if (d.dispatched != null && d.advanced != null && d.dispatched !== d.advanced) {
        lines.push('派发次数与位移次数不一致：控件吞掉了差额，以回读到的值为准，不要把 dispatched 当成走掉的格数。')
      }
      return { ok: true, code: '', text: lines.join('\n'), path: '', ...channelFields(r) }
    }
  })

  // ── 6. 单控件状态 ─────────────────────────────────────────────────────
  push({
    name: 'phone_node',
    description:
      '读取单个控件的状态（ui.node）：class / id / package / text / desc / enabled / checkable / checked / clickable /' +
      ' longClickable / scrollable / selected / visible / focused / editable，加上该控件自报的 actions 与（范围控件才有的）range。' +
      '可点与否看 actions，不看 clickable：后者是框架原始位，clickable=true 而 actions 里没有 click 的控件确实存在。' +
      '位置以平铺的 left/top/right/bottom 给出（已裁到屏内），整颗被裁空时给 offscreen=true。' +
      '用于判定控件是否可操作、是否为范围控件、当前值为何；可替代再次截图，也可作为操作后的校验手段。',
    parameters: targetParams(),
    output: {
      schema: resultSchema({ actions: { type: 'string', required: true, description: '该控件接受的动作列表，逗号分隔；读取失败或控件不接受任何动作时为空串。' } }),
      render: renderText
    },
    execute: async (args) => {
      const payload = args.nodeId != null ? { nodeId: args.nodeId } : { selector: args.selector || {} }
      const r = await channel.call('ui.node', payload)
      if (!r.ok) return { ok: false, code: r.code || '', text: summary(r), path: '', actions: '', ...channelFields(r) }
      const n = (r.data && r.data.node) || {}
      const actions = Array.isArray(n.actions) ? n.actions.join(',') : ''
      const flags = ['enabled', 'checkable', 'checked', 'clickable', 'longClickable', 'scrollable', 'selected', 'visible', 'focused', 'editable']
        .map((key) => `${key}=${n[key] === true ? 'true' : n[key] === false ? 'false' : '未提供'}`)
        .join(' ')
      const text = [
        `nodeId=${fieldOr(r.data && r.data.nodeId, '未提供')} matchMode=${fieldOr(r.data && r.data.matchMode, '未提供')} ${String(n.class || '').split('.').pop()} id=${n.id || ''} package=${n.package || ''} display=${fieldOr(n.display, '未提供')}`,
        `text=${JSON.stringify(n.text || '')} desc=${JSON.stringify(n.desc || '')}`,
        flags,
        renderBox(n),
        n.range ? `range=${JSON.stringify(n.range)}（范围控件：滚动/设值以此为准）` : 'range=无（非范围控件；ui.scroll 的 until 需要 range）',
        `actions=${actions || '空（该控件不接受任何动作）'}`,
        actions.split(',').includes('click') ? '' : '注意：actions 里没有 click，点下去会回 E_NODE_NOT_ACTIONABLE（即使 clickable=true）。'
      ].filter(Boolean).join('\n')
      return { ok: true, code: '', text, path: '', actions, ...channelFields(r) }
    }
  })

  // ── 7. 等待条件 ───────────────────────────────────────────────────────
  push({
    name: 'phone_waitFor',
    description:
      '等待条件成立（ui.waitFor）：文本、checked 或控件消失，默认 3000ms、上限 15000ms。' +
      '操作后应使用本工具替代固定延时。宿主会把等待压到本次调用在通道里剩下的预算内，回包的 requestedMs / cappedToBudget 说明是否被压。' +
      '目标写法非法或有歧义会立刻失败而不是等满超时，因此 E_WAIT_TIMEOUT 只表示界面未进入该状态，与通道无关。',
    parameters: {
      ...targetParams(),
      text: { type: 'string', description: '等待该控件变为指定文本。' },
      checked: { type: 'boolean', description: '等待勾选状态。' },
      absent: { type: 'boolean', description: '等待该控件消失。' },
      timeoutMs: { type: 'integer', description: '等待上限，默认 3000，最大 15000（宿主侧上限，比本层 ui.* 预算短）。' }
    },
    output: { schema: resultSchema(), render: renderText },
    execute: async (args) => {
      const payload = args.nodeId != null ? { nodeId: args.nodeId } : { selector: args.selector || {} }
      for (const key of ['text', 'checked', 'absent', 'timeoutMs']) if (args[key] != null) payload[key] = args[key]
      const r = await channel.call('ui.waitFor', payload)
      return { ok: r.ok, code: r.code || '', text: summary(r), path: '', ...channelFields(r) }
    }
  })

  // ── 8. 全局按键 ───────────────────────────────────────────────────────
  push({
    name: 'phone_key',
    description:
      '发送全局按键（ui.key）。前台屏只接受 back / home / recents，其他键返回 unknown key；' +
      'enter 只在虚拟屏上有意义（前台路径跑的是全局动作，其中没有 enter）。',
    parameters: {
      key: { type: 'string', required: true, enum: ['back', 'home', 'recents', 'enter'], description: '按键名；enter 仅虚拟屏可用。' }
    },
    output: { schema: resultSchema(), render: renderText },
    execute: async (args) => {
      const r = await channel.call('ui.key', { key: args.key })
      return { ok: r.ok, code: r.code || '', text: summary(r), path: '', ...channelFields(r) }
    }
  })

  // ── 9. 启动应用 ───────────────────────────────────────────────────────
  push({
    name: 'phone_launch',
    description:
      '按包名启动应用（app.launch）。返回 verified / landedOn：verified=false 表示命令已发出但未确认落地，不应视为成功。' +
      '向虚拟屏投送应用时，仅当 landedOn 等于 surface.virtual 返回的 displayId 才算落地；grabbedUserScreen=true 才说明它落到了主屏（用户正在看的屏幕）上，值得告知用户。' +
      'landedOn=0 且没有该标记只表示用户本来就在这个应用上，说明不了这次启动去了哪里；' +
      'topOnTarget / targetDisplayMoved 用于分辨"换了个包名落地"与"真的没起来"，读完这两项再判定失败。',
    parameters: {
      package: { type: 'string', required: true, description: '包名（可由 phone_call pkg.query 获取）。' }
    },
    output: { schema: resultSchema(), render: renderText },
    execute: async (args) => {
      const r = await channel.call('app.launch', { package: args.package })
      return { ok: r.ok, code: r.code || '', text: summary(r), path: '', ...channelFields(r) }
    }
  })

  // ── 10. 截图 ──────────────────────────────────────────────────────────
  push({
    name: 'phone_capture',
    description:
      '截图并返回本地文件路径（随后用 read_image 查看）。这是获取像素的唯一入口：' +
      '视频/画布等无控件树内容、以及控件树与实际显示不一致时的仲裁均依赖它。' +
      '前台屏截图要不要过 Android 系统的屏幕采集同意框（MediaProjection）取决于执行路线：' +
      '无障碍截图路线没有系统框；MediaProjection 路线每个采集会话要系统同意一次；虚拟屏路线没有系统框 —— 以回包的 surface 与错误码为准。' +
      '返回 E_AWAITING_CONSENT 指的是 Pilot 审批在等答复（本模块自己的许可，不是上述系统采集同意）：' +
      '可在三处任一处答复，且同一时刻只呈现一处：助手页内的那张卡（该页在前台时）、悬浮卡、通知栏的「允许/拒绝」；' +
      '宿主界面位于最前面时悬浮卡不出现，请到通知栏或助手页答复，随后以完全相同的参数重试即可接上同一张框。',
    parameters: {
      out: { type: 'string', description: `落盘路径，默认写入本包落盘目录（${dir}）。` }
    },
    output: { schema: resultSchema(), render: renderText },
    execute: async (args) => {
      const ownName = !args.out
      const file = args.out || path.join(shotDir, `${SHOT_PREFIX}${stamp()}${SHOT_SUFFIX}`)
      const r = await channel.call('screen.capture', {}, { out: file })
      // 默认名落在自己的落盘目录里，与快照同一套保留策略：调用方只认回包里那份 path，
      // 旧帧留着没有读者，却会把目录撑满。调用方指定落点时本次没有自己的产物，不清。
      if (ownName) pruneSnapshots(shotDir, SHOT_PREFIX, SHOT_SUFFIX)
      if (artifactDeliveryFailed(r)) {
        // 产物复制到落点失败时，回包仍是 ok:true 而 artifacts 槽位是空串：
        // 照常报成功会把人引去读一个不存在的文件，兜底回预设名同样如此 ——
        // 这里必须以失败收场，path 保持空串，"截图已保存"不许出现。
        return {
          ok: false,
          code: 'E_ARTIFACT_WRITE',
          text: artifactDeliveryFailedText(),
          path: '',
          ...channelFields(r),
          retryable: false,
          exitCode: 6
        }
      }
      const where = (r.artifacts && r.artifacts[0]) || (r.ok ? file : '')
      const text = r.ok
        ? `截图已保存: ${where}（随后用 read_image 查看）\nsurface=${r.surface} display=${r.data && r.data.display} bytes=${r.data && r.data.bytes}`
        : summary(r)
      return { ok: r.ok, code: r.code || '', text, path: where, ...channelFields(r) }
    }
  })

  // ── 11. 系统快捷入口（闹钟 / 计时器）──────────────────────────────────
  push({
    name: 'phone_intent',
    description:
      '通过固定模板调用系统公开入口（sys.intent）。表内八条，没有第九条：' +
      'alarm.set(hour 0..23, minute 0..59, 可附 message)、timer.set(length 秒 1..86400, 可附 message)、' +
      'alarm.show、timer.show、settings.open(page: wifi|bluetooth|display|location|sound|apn|developer|nfc)、' +
      'app.info(package)、dial(number，只打开拨号盘不拨出)、web.open(url，只收 http/https)。' +
      '后六条只是把系统页面摆到用户眼前，不替他改任何东西；表里没有删除或取消闹钟的入口，也不给加。' +
      'SKIP_UI 只对 alarm.set/timer.set 有意义，其余一定上屏。' +
      '返回值只表示"已交给哪个应用"：本机实测 SET_ALARM 有多个候选时会先弹系统选择器，' +
      'resolvedPackage 可能是 com.android.intentresolver；要确认闹钟真设上，得请用户在时钟里看一眼，' +
      '或在用户开启危险能力后走 phone_call sys.shell {"verb":"dumpsys","args":["alarm"]}。',
    parameters: {
      template: {
        type: 'string', required: true,
        enum: ['alarm.set', 'timer.set', 'alarm.show', 'timer.show', 'settings.open', 'app.info', 'dial', 'web.open'],
        description: '模板名。'
      },
      hour: { type: 'integer', description: 'alarm.set 的小时 0..23。' },
      minute: { type: 'integer', description: 'alarm.set 的分钟 0..59。' },
      length: { type: 'integer', description: 'timer.set 的时长（秒 1..86400）。' },
      message: { type: 'string', description: '可选标签或提示语，最长 100 字。' },
      page: {
        type: 'string',
        enum: ['wifi', 'bluetooth', 'display', 'location', 'sound', 'apn', 'developer', 'nfc'],
        description: 'settings.open 要打开的系统设置页。'
      },
      package: { type: 'string', description: 'app.info 的目标包名（可由 phone_call pkg.query 取得）。' },
      number: { type: 'string', description: 'dial 的号码，只含数字与 + * # ( ) . , 空格 连字符，≤32 字。' },
      url: { type: 'string', description: 'web.open 的网址，必须 http:// 或 https:// 开头，不含空白。' }
    },
    output: { schema: resultSchema(), render: renderText },
    execute: async (args) => {
      const payload = { template: args.template }
      for (const key of ['hour', 'minute', 'length', 'message', 'page', 'package', 'number', 'url']) {
        if (args[key] != null) payload[key] = args[key]
      }
      const r = await channel.call('sys.intent', payload)
      return { ok: r.ok, code: r.code || '', text: summary(r), path: '', ...channelFields(r) }
    }
  })

  // ── 12. 虚拟屏 ────────────────────────────────────────────────────────
  push({
    name: 'phone_surface',
    description:
      '管理后台虚拟屏（surface.virtual）：create 创建（返回 displayId）/ query 查询 / release 释放。' +
      '后台操作前先创建；随后 phone_launch 必须确认 landedOn 等于该 displayId；操作完成后释放，并让宿主回到前台（app.launch 的 package 填宿主自身包名，本机为 com.dshbox.app，也可先用 phone_call pkg.query 取；宿主对「以宿主自身为目标」的显式放行仅限 app.launch 只带宿主包名这一种形状，其余带宿主包名的调用仍会被拒）。' +
      '这个形状不受档位拦阻（不弹确认框）：它的常用时机正是用户看不到确认框的时候。回到前台落在宿主自己的主界面（DSHBox 首页），用户从这里重新进入对话；宿主已经在前台时它什么都不启动，回 verified:true + alreadyInFront:true，属成功。' +
      '虚拟屏的存活条件是"上面有应用在跑 + 持续有调用"：release、通道停止或五分钟无调用都会收回，query 回 display=-1 即为已收回。' +
      '虚拟屏上的文本写入仍受输入法限制（虚拟屏没有输入法通路）；宿主界面位于最前面时写操作会被暂停。' +
      '虚拟屏的控件树能否读到由 backend.shizuku.trustedDisplay.nodeTree 说明，alive 不等于读得到。',
    parameters: {
      action: { type: 'string', required: true, enum: ['create', 'query', 'release'], description: '动作。' },
      width: { type: 'integer', description: '可选，320..2560（由宿主钳制）。' },
      height: { type: 'integer', description: '可选，320..2560。' },
      dpi: { type: 'integer', description: '可选，显示密度。' }
    },
    output: { schema: resultSchema(), render: renderText },
    execute: async (args) => {
      const payload = { action: args.action }
      for (const key of ['width', 'height', 'dpi']) if (args[key] != null) payload[key] = args[key]
      const r = await channel.call('surface.virtual', payload)
      const text = r.ok ? `虚拟屏 ${payload.action}: ${compact(r.data)}` : summary(r)
      return { ok: r.ok, code: r.code || '', text, path: '', ...channelFields(r) }
    }
  })

  // ── 13. 向用户提问 ────────────────────────────────────────────────────
  push({
    name: 'phone_ask',
    description:
      '向用户当面提一个问题并等答复：宿主把它摆成一张卡贴在屏幕最上层（与审批卡同款外观），' +
      '适合助手正在前台操作别的应用、用户看不到对话页的时候。选项固定 2..3 条，问题与选项都有字数上限。' +
      '答复三支都是成功：choice=option（用户选了某项，看 choiceIndex / choiceLabel）、' +
      'choice=reject_all（这一组选项都不合适 —— 重新组织选项后再问，不要原样重问）、' +
      'choice=reask（问题本身要换个法子组织 —— 改问法后再问）。后两支不是失败，也不能当成"没答"忽略。' +
      '同屏只允许一张卡：与审批卡、与另一问都互斥，摆不上时回 E_ASK_BUSY。' +
      '等待窗口由 timeoutMs 决定（默认 60000，夹在 5000..120000）。' +
      '没有答复时按 E_ASK_TIMEOUT / E_ASK_NO_SURFACE 的处置建议走；提问不改动设备状态，重问是安全的。',
    parameters: {
      question: { type: 'string', required: true, description: '要问用户的一句话，最长 200 字（去首尾空白后不得为空）。' },
      options: {
        type: 'array',
        description: '2..3 个选项，每项为非空字符串且不超过 60 字；用户另有一个「全部驳回」与一个「重新提问」。'
      },
      timeoutMs: { type: 'integer', description: '等待答复的窗口（毫秒），夹在 5000..120000，默认 60000。' }
    },
    output: {
      schema: resultSchema({
        choice: { type: 'string', required: true, description: '答复支：option=选中某项 / reject_all=这一组选项都不合适 / reask=问题要重新组织；空串=本次没有答复（失败）。' },
        choiceIndex: { type: 'integer', required: true, description: '选中项下标（从 0 起）；非 option 时为 -1。' },
        choiceLabel: { type: 'string', required: true, description: '选中项的原文；非 option 时为空串。' }
      }),
      render: renderText
    },
    execute: async (args) => {
      const r = await channel.ask({
        question: args.question,
        options: args.options,
        timeoutMs: args.timeoutMs
      })
      const kind = r.choice && typeof r.choice.kind === 'string' ? r.choice.kind : ''
      const index = r.choice && Number.isInteger(r.choice.index) ? r.choice.index : -1
      const label = r.choice && typeof r.choice.label === 'string' ? r.choice.label : ''
      // 三支收尾各自对应调用方不同的下一步：驳回否的是选项（重排后再问），
      // 重新提问否的是问题（换问法再问）—— 合并成一句会把两者引向同一个错误动作。
      let text = summary(r)
      if (r.ok === true && kind === 'option') {
        text = `用户选择：${label}（第 ${index + 1} 项，choiceIndex=${index}）`
      } else if (r.ok === true && kind === 'reject_all') {
        text = '用户全部驳回：这一组选项都不合适。请重新组织选项后再问一次，不要原样重问。'
      } else if (r.ok === true && kind === 'reask') {
        text = '用户要求重新提问：问题本身要换个法子组织。请改问法后再问，不要原样重发。'
      }
      return {
        ok: r.ok,
        code: r.code || '',
        text,
        path: '',
        ...channelFields(r),
        choice: r.ok === true ? kind : '',
        choiceIndex: r.ok === true && kind === 'option' ? index : -1,
        choiceLabel: r.ok === true && kind === 'option' ? label : ''
      }
    }
  })

  return tools
}

module.exports = {
  buildTools,
  renderTree,
  renderBox,
  resultSchema,
  compact,
  outDir,
  pruneSnapshots,
  TREE_LINES,
  SNAPSHOT_PREFIX,
  SNAPSHOT_KEEP,
  SHOT_PREFIX,
  SHOT_SUFFIX
}
