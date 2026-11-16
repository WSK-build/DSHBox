/**
 * Host loader entry for @local/mobile-pilot.
 *
 * 职责分三层，互不重叠：
 *   · lib/mount.js       —— 解析挂载点（宿主应用绑进沙盒的 /opt/pilot）；
 *   · bin/mobile-pilot   —— 命令行入口，参数原样转发给 `<挂载点>/bin/pilot`；
 *   · lib/index.js       —— 本文件，注册 DSH 原生工具（pilot.js 投递、tools.js 定义）。
 *
 * 本包不重实现信箱协议：请求信封、回包字段与错误码由宿主及其 `bin/pilot` 持有，
 * 避免出现第二份会各自漂移的协议规格。
 *
 * 装载失败不得影响命令行入口：拿不到 defineTool 时只报告一句并返回，不抛异常 ——
 * 工具注册与 CLI 转发是两条独立可用的路径。
 */
'use strict'

const path = require('node:path')
const { pathToFileURL } = require('node:url')
const { resolvePack, readManifest, ENTRY_REL, MANIFEST_REL, GUIDE_REL } = require('./mount')
const { PilotChannel, describe, hintFor } = require('./pilot')
const { buildTools } = require('./tools')
const stream = require('./stream')

/** Stable cordis plugin name - must equal the package name. */
exports.name = '@local/mobile-pilot'

/** 需要 tools 服务：本包的产出是一组原生工具。 */
exports.inject = ['tools']

/** dsh 侧 tools 模块的包名。本包与它分属两棵目录树，解析要按基准目录逐个试。 */
const TOOLS_MODULE = '@deepseek-ai/dsh-tools'

/** 未取得 defineTool 时的说明。它同时是"为什么工具列表里没有 phone_*"的答案。 */
const DSH_TOOLS_MISSING = '未找到 dsh-tools，工具未注册；mobile-pilot CLI 不受影响'

/**
 * `require.resolve` 的候选基准目录。
 *
 * 本包随 profile 复制进沙盒，而 dsh-tools 装在 dsh 自己的 runtime 树里：两棵目录树
 * 谁也不包着谁，只从本文件的位置解析必然 MODULE_NOT_FOUND，那与 API 在不在无关。
 * 工作目录、入口脚本目录与 node 可执行文件目录都要各算一次。
 */
function resolveBases () {
  const bases = new Set([__dirname, process.cwd()])
  if (require.main && require.main.filename) bases.add(path.dirname(require.main.filename))
  if (process.execPath) bases.add(path.dirname(process.execPath))
  return [...bases].filter((p) => typeof p === 'string' && p.length > 0)
}

/**
 * 取 `defineTool` 的那一次尝试，把**卡在哪一步、在哪个目录卡的**一起带回来。
 *
 * 只看"成功/失败"不够。三种情形要分开：`resolve`（包根本不在可解析路径上）、
 * `shape`（在，但导出的 `defineTool` 不是函数）、`require`（解析到了却 require 不动，
 * 纯 ESM 且运行时不支持 require(esm) 时是这一档，动态 import 仍可能成功）。
 *
 * 凡是 resolve 成功过的绝对路径都记进 `resolvedPaths`：require 走不通的纯 ESM，
 * 后续动态 import 要按这些路径来，而不是退回裸包名 —— 裸包名从本文件位置解析，
 * 与这里的候选基准不是一套。
 */
function acquireDefineTool () {
  const attempts = []
  const resolvedPaths = []
  for (const base of resolveBases()) {
    let resolvedPath = null
    try {
      resolvedPath = require.resolve(TOOLS_MODULE, { paths: [base] })
    } catch (error) {
      attempts.push({ base, stage: 'resolve', code: error && error.code, message: error && error.message })
      continue
    }
    resolvedPaths.push(resolvedPath)
    try {
      const defineTool = require(resolvedPath).defineTool
      if (typeof defineTool === 'function') return { defineTool, stage: 'require', resolvedPath, resolvedPaths, attempts }
      attempts.push({ base, stage: 'shape', resolvedPath, message: '导出的 defineTool 不是函数' })
    } catch (error) {
      attempts.push({ base, stage: 'require', resolvedPath, code: error && error.code, message: error && error.message })
    }
  }
  const last = attempts[attempts.length - 1] || {}
  return { defineTool: null, stage: last.stage || 'resolve', code: last.code, message: last.message, resolvedPaths, attempts }
}

/**
 * require 走不通时的动态 import 兜底，按可靠程度递减：
 *   1) 逐个尝试已解析到的绝对路径（file URL 形式）—— resolve 能找到就说明模块就在那儿，
 *      纯 ESM 只是 require 这个入口进不去，换 import 就能进；
 *   2) 全部落空才退回裸包名 —— 它从本文件所在树解析，与上面的候选基准不是一套，
 *      成功与否都只是最后手段，不能当作主通路。
 * 返回 { defineTool, via } 或 { defineTool: null, note }；note 把每一步卡在哪带回来。
 */
async function importDefineTool (resolvedPaths) {
  const notes = []
  for (const resolvedPath of [...new Set(resolvedPaths || [])]) {
    try {
      const mod = await import(pathToFileURL(resolvedPath).href)
      if (typeof mod.defineTool === 'function') return { defineTool: mod.defineTool, via: resolvedPath }
      notes.push(`${path.basename(resolvedPath)}: 导出的 defineTool 不是函数`)
    } catch (error) {
      notes.push(`${path.basename(resolvedPath)}: ${(error && error.code) || (error && error.message) || '失败'}`)
    }
  }
  try {
    const mod = await import(TOOLS_MODULE)
    if (typeof mod.defineTool === 'function') return { defineTool: mod.defineTool, via: TOOLS_MODULE }
    notes.push(`${TOOLS_MODULE}: 导出的 defineTool 不是函数`)
  } catch (error) {
    notes.push(`${TOOLS_MODULE}: ${(error && error.code) || (error && error.message) || '失败'}`)
  }
  return { defineTool: null, note: notes.join('; ') }
}

/** 注册与自检共用的失败句：把阶段与错误码写进同一句，日志里一次读得全。 */
function missingToolsNote (acquired) {
  return [acquired.stage, acquired.code, acquired.message].filter(Boolean).join(' / ')
}

/**
 * 自检入口：`mobile-pilot doctor` 用它报"工具面到底接上没有"。
 *
 * 这条读数只覆盖**从本包所在目录树解析**这一件事，解析不到不等于 API 缺失：dsh 在自己的
 * runtime 树里加载本包，工具是否真在，以 dsh 的工具列表里有没有 `phone_*` 为准。
 * 装包之后没重启 dsh 也会落在同一个读数上，所以失败时必须把这句一起回出来。
 */
function probeToolsApi () {
  const acquired = acquireDefineTool()
  if (acquired.defineTool) return { ok: true, via: acquired.stage, entry: acquired.resolvedPath }
  return {
    ok: false,
    via: null,
    stage: acquired.stage,
    code: acquired.code || null,
    message: acquired.message || null,
    bases: acquired.attempts.map((a) => a.base),
    meaning: '仅表示从这些目录解析不到该模块，不表示插件坏了：dsh 在自己的 runtime 树里加载本包，' +
      '工具是否可用以 dsh 工具列表里有没有 phone_* 为准；只装包而未重启 dsh 也会得到同一读数。',
  }
}

function report (message) {
  try {
    process.stderr.write(`${exports.name}: ${message}\n`)
  } catch {
    // 无控制台（被当作库引入）时放弃报告：注册结果本身已经可查。
  }
}

/**
 * 逐项注册已构造好的工具。构造（buildTools）先于注册全部完成 —— 构造期抛错时
 * 一个都不会注册。注册期第 N 项抛错时停下：注册接口没有批次回滚，已注册的
 * 前几项撤不掉，只能如实例数点名报出来，明示重启后重试或按部分工具继续使用，
 * 不能只用一句模糊的"注册失败"把已注册清单吞掉。
 */
function registerTools (defineTool, ctx, config) {
  if (typeof defineTool !== 'function') throw new TypeError('defineTool 不可用')
  const channel = new PilotChannel()
  const tools = buildTools({ defineTool, channel, outDirOption: config && config.outDir })
  const registered = []
  for (const tool of tools) {
    try {
      ctx.tools.register(tool)
    } catch (error) {
      report(
        `PARTIAL_REGISTRATION: registered=[${registered.join(', ')}] failed=${tool.name} (${(error && error.message) || error})` +
        ' —— 注册没有批次回滚，已列出的工具照常可用；重启 dsh 后重试装配，或先按这部分工具继续使用',
      )
      return registered.length
    }
    registered.push(tool.name)
  }
  return registered.length
}

/** 注册的共用收口：构造期抛错时一个都没注册，照实说；注册期的部分失败由 registerTools 点名。 */
function registerVia (defineTool, ctx, config) {
  try {
    registerTools(defineTool, ctx, config)
  } catch (error) {
    report(`工具构造失败，一个都没有注册：${(error && error.message) || error}`)
  }
}

/**
 * 注册工具。`config.outDir`（可选）指定截图与快照的落盘目录，
 * 未指定时按 MOBILE_PILOT_OUT_DIR → 当前工作目录的顺序取，取不到退回系统临时目录。
 *
 * 返回 Promise 或 undefined；任何失败都以日志报告并以 undefined 结束，
 * 让 loader 把本包当"没提供工具的普通包"继续跑。
 */
/**
 * 实时流路由：读 rendezvous（宿主 App 写在通道 run/ 下的控制端点地址与
 * token），探测 runtime 的 webserver 路由服务并挂接 /mobile-pilot/stream* 四条路由。
 * 任一步不成（没有 rendezvous / 没有可识别的路由 API）都只报告 —— 预览为增强项，
 * 面板会显示对应的无流状态，工具与 CLI 不受影响。token 不进任何报告文本。
 */
/**
 * 服务取得走 `ctx.inject(['webServer','connection'], cb)`：这两个服务可能在本包
 * apply **之后**才 provide，裸读 ctx.webServer 会撞上 undefined 并把预览永久降级。
 * 旧 runtime 没有 ctx.inject 时如实报告（工具与 CLI 不受影响）。
 */
function attachStreamRoutes (ctx) {
  if (typeof (ctx && ctx.inject) !== 'function') {
    report('实时流：runtime 未提供 ctx.inject，无法安全取得 webServer/connection；面板将显示无流状态，工具与 CLI 不受影响')
    return
  }
  try {
    // cordis 的 inject 回调形参是 **(childCtx, config)**：子上下文上才存在注入的
    // webServer/connection —— 不是把服务逐个当参数递进来。
    ctx.inject(['webServer', 'connection'], (childCtx) => {
      const pack = resolvePack({})
      if (!pack.root) {
        report('实时流：找不到助手通道挂载点（/opt/pilot）；面板将显示无流状态')
        return
      }
      const attached = stream.attach({
        webServer: childCtx.webServer,
        connection: childCtx.connection,
        effect: (typeof childCtx.effect === 'function') ? childCtx.effect
          : ((typeof ctx.effect === 'function') ? ctx.effect : null),
        fetchImpl: (typeof globalThis.fetch === 'function') ? globalThis.fetch.bind(globalThis) : null,
        controlProvider: () => stream.readRendezvous(pack.root),
      })
      if (!attached.ok) {
        report(`实时流路由未挂接：${attached.reason}`)
        return
      }
      // attach 内部已把每条注册包进 effect（disposer 随插件同生共死）。
      void attached.disposer
    })
  } catch (error) {
    report(`实时流路由装配失败（不影响工具）：${(error && error.message) || error}`)
  }
}

exports.apply = function apply (ctx, config) {
  if (!ctx || !ctx.tools || typeof ctx.tools.register !== 'function') {
    report('宿主未提供 tools 服务，工具未注册；mobile-pilot CLI 不受影响')
    return undefined
  }
  attachStreamRoutes(ctx)
  const acquired = acquireDefineTool()
  if (acquired.defineTool) {
    registerVia(acquired.defineTool, ctx, config)
    return undefined
  }
  const syncNote = missingToolsNote(acquired)
  return importDefineTool(acquired.resolvedPaths)
    .then((loaded) => {
      if (!loaded.defineTool) {
        report(`${DSH_TOOLS_MISSING}（同步：${syncNote}；动态 import：${loaded.note}）`)
        return undefined
      }
      registerVia(loaded.defineTool, ctx, config)
      return undefined
    })
}

/**
 * 界面操控能力子集：从清单中按 id 前缀 `ui.` 过滤。
 * 判据取自清单自身字段，此处不另行维护名单 —— 宿主新增界面能力时无需改动本文件。
 */
function uiCapabilities (options) {
  const resolved = resolvePack(options)
  const manifest = resolved.root ? readManifest(resolved.info.manifest) : null
  const caps = manifest && Array.isArray(manifest.capabilities) ? manifest.capabilities : []
  return {
    root: resolved.root,
    capabilities: caps.filter((c) => c && typeof c.id === 'string' && c.id.startsWith('ui.'))
  }
}

module.exports.uiCapabilities = uiCapabilities
module.exports.probeToolsApi = probeToolsApi
module.exports.PilotChannel = PilotChannel
module.exports.describe = describe
module.exports.hintFor = hintFor
module.exports.DSH_TOOLS_MISSING = DSH_TOOLS_MISSING
module.exports.resolvePack = resolvePack
module.exports.readManifest = readManifest
module.exports.ENTRY_REL = ENTRY_REL
module.exports.MANIFEST_REL = MANIFEST_REL
module.exports.GUIDE_REL = GUIDE_REL
// 注册与动态 import 的兜底逻辑单独露出：部分注册失败的表现、file URL 的重试顺序，
// 都要在不依赖真实 dsh-tools 的环境下可验证。
module.exports.registerTools = registerTools
module.exports.attachStreamRoutes = attachStreamRoutes
module.exports.importDefineTool = importDefineTool
