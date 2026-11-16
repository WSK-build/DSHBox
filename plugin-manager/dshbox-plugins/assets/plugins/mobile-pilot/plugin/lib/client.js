/**
 * Browser half of @local/mobile-pilot.
 *
 * DSH 右侧栏的「虚拟屏」入口与预览面板。装载形态以 DSH 0.2.2-rc.1 的
 * window.__ModuleLoader__ 为准，与本仓库既有客户端插件的 lib/client.js 同式：
 * 顶层一次 load，factory 里给出 name / inject / apply。
 *
 * 面板接入虚拟屏的 H.264 流（MSE）。取数通路是 DSH runtime 的
 * webserver 路由（Node 半挂接的 /mobile-pilot/stream* 同源路由）：面板只发
 * 同源相对请求，流 token 从不出 Node。runtime 没有该路由 API 时如实显示
 * 「无流路由」状态，不猜、不静默黑屏。此后面板可交互：tap/swipe/back/home
 * 经 /mobile-pilot/input 代理到宿主（鉴权=同源/CSRF + tab token + 会话存活 +
 * 宿主控制 token；没有文字、组合键与复杂手势），播放语义不受影响。
 *
 * 版本门控：右侧栏 API（ctx.sidebarRightTabs 与 ctx.slots 的 pane.tab 席位）是
 * 0.2.2-rc.1 起才有的客户端服务。旧 runtime 上 inject 满足不了，loader 不会调
 * apply，客户端半边安静地不激活 —— 入口不出现，宿主半边的 phone_* 工具与
 * mobile-pilot CLI 不受影响。apply 里的缺服务分支只是防御性覆盖，不承担门控。
 */
window.__ModuleLoader__.load({
  id: '@local/mobile-pilot',
  factory: (require) => {
    var module = { exports: {} }
    var exports = module.exports

    // react 拿不到时整个浏览器半边不注册：画不出正文的 tab 是接线错误，不是降级。
    var react = null
    try { react = require('react') } catch (e) { react = null }

    /** tab 系统里的实现身份，全部注册中唯一；同时也是正文席位注册用的 key。 */
    var TAB_ID = '@local/mobile-pilot'
    /** 类型判别符：`ctx.sidebarRight.openTab(kind)` 按它打开本面板。 */
    var TAB_KIND = 'mobile-pilot-virtual-display'
    /** tab 正文席位名：一类型一段正文，按 definition 的 id 派发。 */
    var SLOT_PANE_TAB = 'sidebar.right.pane.tab'

    /** 浏览器半边没有 stderr 可写，note 走 console。 */
    function note (message) {
      try { console.info('[mobile-pilot] ' + message) } catch (e) { /* 无控制台时放弃报告 */ }
    }

    /** 浏览器半边读不到的读数统一用这一句：它表示「没有数据源」，不是「没有在跑」。 */
    var STATUS_UNKNOWN = '状态未知'

    /**
     * 面板的设计语言。
     *
     * 色板全部走 CSS 变量（DSH 主题令牌优先，缺省值兜底明暗两态），8px 间距网格，
     * 卡片圆角 12 / 控件圆角 8，字号 12/14/16/20，行高 1.5；主色用蓝（与 DSH 的
     * 链接/主按钮一致），危险动作用红。按钮三档 primary/secondary/danger；触控
     * 目标 ≥36px；disabled 降透明度。对比度按 WCAG AA 挑的兜底色（浅底 #1f6feb 蓝、
     * #d1242f 红、#1a7f37 绿），DSH 令牌在场时由主题接管。
     */
    var styles = {
      // ── 色板 ──
      colorPrimary: 'var(--dsw-alias-brand-primary, #1f6feb)',
      colorDanger: 'var(--dsw-alias-danger-primary, #d1242f)',
      colorSuccess: 'var(--dsw-alias-success-primary, #1a7f37)',

      // ── 布局 ──
      // root 不再整体滚动 —— 头部与预览固定在顶部，只有动作/诊断等内容
      // 在下方独立的滚动区里滚（滑视频/头部不会把动作面滚走，也不带动右侧栏）。
      root: {
        height: '100%',
        boxSizing: 'border-box',
        overflow: 'hidden',
        padding: 16,
        color: 'var(--dsw-alias-label-primary, inherit)',
        fontSize: 14,
        lineHeight: 1.5,
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
      },
      /** 头部+预览下方的独立滚动区：动作栏/按键/诊断/提示在这里滚。 */
      scrollBody: {
        flex: 1,
        minHeight: 0,
        overflowY: 'auto',
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
      },
      header: {
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        minHeight: 32,
      },
      headerTexts: { flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 2 },
      title: { margin: 0, fontSize: 20, fontWeight: 600, lineHeight: 1.3 },
      subtitle: {
        margin: 0,
        fontSize: 12,
        color: 'var(--dsw-alias-label-secondary, rgba(127,127,127,.9))',
      },
      /** 状态胶囊：圆点 + 一行短语，颜色随相位（蓝=进行/绿=好/红=坏/灰=闲）。 */
      pill: {
        display: 'inline-flex',
        alignItems: 'center',
        gap: 6,
        borderRadius: 999,
        padding: '2px 10px',
        fontSize: 12,
        whiteSpace: 'nowrap',
        flexShrink: 0,
        border: '1px solid var(--dsw-alias-border-l3, rgba(127,127,127,.35))',
      },
      pillDot: { width: 8, height: 8, borderRadius: 999, flexShrink: 0 },
      // 右上角溢出菜单已删 —— 诊断详情只保留底部独立折叠卡这一个入口。

      // ── 卡片 ──
      card: {
        borderRadius: 12,
        border: '1px solid var(--dsw-alias-border-l3, rgba(127,127,127,.35))',
        background: 'var(--dsw-alias-bg-secondary, rgba(127,127,127,.08))',
        padding: 12,
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
      },
      cardHead: {
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 8,
      },
      cardTitle: { margin: 0, fontSize: 16, fontWeight: 600 },

      // ── 预览卡 ──
      // 不再写死 16/9 与最小高 —— 宽高比按会话 metadata 的源尺寸（缺了用
      // video.videoWidth/Height 兜底）动态给 aspect-ratio，视频 width:100% + height:auto
      // + objectFit:contain：源 2560×2064 这类竖长画面按 ~1.24 显示，左右不再大黑边、
      // 上下不被切。竖长源（如 1080×2400）在窄栏里会把下方动作区顶出
      // root（overflow:hidden 直接裁掉），video 加 maxHeight 封顶（objectFit:contain
      // 上下不裁内容），保证下方动作/诊断始终可达。空态（未 playing、video 无内容
      // 尺寸）由渲染处给 videoWrap 一个 EMPTY_PREVIEW_MIN_HEIGHT 的 minHeight。
      videoWrap: { position: 'relative', width: '100%', borderRadius: 12, overflow: 'hidden' },
      video: {
        width: '100%',
        height: 'auto',
        maxHeight: '45vh',
        display: 'block',
        background: '#000',
        objectFit: 'contain',
      },
      emptyState: {
        position: 'absolute',
        left: 0, top: 0, right: 0, bottom: 0,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 8,
        padding: 16,
        textAlign: 'center',
        // 深灰空态底（中性，非红）；红色只用于操作后的真实错误。
        background: 'rgba(20,20,24,.92)',
        color: 'var(--dsw-alias-label-secondary, rgba(255,255,255,.75))',
      },
      emptyText: { margin: 0, fontSize: 14 },
      emptyHint: { margin: 0, fontSize: 12, opacity: 0.75 },
      /** 轻量 spinner：css 边框旋转（keyframes 由面板自持的 <style> 注入）。 */
      spinner: {
        width: 22,
        height: 22,
        borderRadius: 999,
        border: '2px solid rgba(127,127,127,.35)',
        borderTopColor: 'var(--dsw-alias-brand-primary, #1f6feb)',
        animation: 'mobilePilotSpin 900ms linear infinite',
      },

      // ── 按钮（三档）──
      btnBase: {
        borderRadius: 8,
        minHeight: 36,
        padding: '8px 14px',
        fontSize: 14,
        lineHeight: 1.5,
        cursor: 'pointer',
        transition: 'opacity 150ms ease, background-color 150ms ease',
        textAlign: 'center',
        boxSizing: 'border-box',
      },
      btnPrimary: {
        // 深色主题下 --dsw-alias-brand-primary 为 #f9fafb：前景必须用
        // DSH 的 on-brand token，不能硬编码 #fff，否则白底白字。
        background: 'var(--dsw-alias-brand-primary, #1f6feb)',
        color: 'var(--dsw-alias-label-primary-foreground, var(--dsw-alias-bg-base, #0f1115))',
        border: 'none',
        fontWeight: 600,
      },
      btnSecondary: {
        background: 'transparent',
        color: 'var(--dsw-alias-label-primary, inherit)',
        border: '1px solid var(--dsw-alias-border-l3, rgba(127,127,127,.35))',
      },
      btnDanger: {
        background: 'transparent',
        color: 'var(--dsw-alias-danger-primary, #d1242f)',
        border: '1px solid var(--dsw-alias-danger-primary, #d1242f)',
      },
      btnDisabled: { opacity: 0.45, cursor: 'default' },
      btnFull: { width: '100%' },
      // 手机宽度下两列；更窄时自动回落单列（flex-basis + minWidth）。
      btnHalf: { flex: '1 1 calc(50% - 4px)', minWidth: 120, boxSizing: 'border-box' },

      // ── 行与文案 ──
      row: { display: 'flex', alignItems: 'center', gap: 8, minHeight: 36 },
      rowLabel: { color: 'var(--dsw-alias-label-secondary, rgba(127,127,127,.9))' },
      rowValue: { textAlign: 'right' },
      hint: {
        margin: 0,
        color: 'var(--dsw-alias-label-tertiary, rgba(127,127,127,.65))',
        fontSize: 12,
      },
      /** 人类可读的主要错误文案（预览卡上首行；不出现 HTTP 码当主文案）。 */
      errorText: {
        margin: 0,
        color: 'var(--dsw-alias-danger-primary, #d1242f)',
        fontSize: 14,
      },
      /** 输入/任务面的可见结论（成功清空、失败说明原因）。 */
      note: {
        margin: 0,
        fontSize: 12,
        color: 'var(--dsw-alias-label-secondary, rgba(127,127,127,.9))',
      },
      paragraph: { margin: 0 },

      // D：输入层。覆盖层盖满 video（absolute inset 0），touchAction:none
      // 配合 pointer 事件的 preventDefault 阻止页面滚动/手势竞争。无论
      // 会话是否在播都保持 none —— 预览已固定在滚动容器顶部，手指在视频上滑动不再
      // 带动右侧栏/面板滚动。
      inputOverlay: {
        position: 'absolute',
        left: 0, top: 0, right: 0, bottom: 0,
        touchAction: 'none',
      },
      // 小工具行（返回/桌面）与任务动作栏：窄屏自动换行。
      toolRow: { display: 'flex', flexWrap: 'wrap', gap: 8 },

      // 虚拟屏任务列表 UI 已删 —— loadTasks 只服务"移到主屏"的内部取数。

      // ── 诊断详情（默认折叠）──
      diagBody: {
        margin: 0,
        padding: 8,
        borderRadius: 8,
        background: 'var(--dsw-alias-bg-primary, transparent)',
        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
        fontSize: 12,
        lineHeight: 1.6,
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-all',
        color: 'var(--dsw-alias-label-secondary, rgba(127,127,127,.9))',
        // 展开后有上限 + 内部滚动，不把整个面板撑到看不出主内容。
        maxHeight: 220,
        overflowY: 'auto',
      },

      // ── 释放模态 ──
      modalMask: {
        position: 'fixed',
        left: 0, top: 0, right: 0, bottom: 0,
        zIndex: 9,
        // 遮罩加深（遮罩需保证文字对比度）。
        background: 'rgba(0,0,0,.55)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 16,
      },
      modalCard: {
        width: '100%',
        maxWidth: 420,
        borderRadius: 12,
        border: '1px solid var(--dsw-alias-border-l3, rgba(127,127,127,.35))',
        // 不透明面板底色（不用 inherit/半透明），文字对比度足够。
        // 注意：--dsw-alias-bg-elevated / --dsw-alias-bg-primary 在 DSH 里是空值，
        // 旧 fallback #1d1f24 会在浅色主题下继续给黑底；用实际存在的 layer token。
        // 兜底对保持浅色（白卡/深字），避免 token 缺失时白底白字。
        background: 'var(--dsw-alias-bg-layer-2, var(--dsw-alias-bg-layer-1, #fff))',
        color: 'var(--dsw-alias-label-primary, #111)',
        padding: 16,
        display: 'flex',
        flexDirection: 'column',
        gap: 12,
        boxSizing: 'border-box',
        boxShadow: '0 12px 40px rgba(0,0,0,.45)',
      },
      modalTitle: { margin: 0, fontSize: 16, fontWeight: 600 },
      modalActions: { display: 'flex', flexWrap: 'wrap', gap: 8, justifyContent: 'flex-end' },
    }

    /**
     * 状态胶囊的相位色（进行=蓝、好=绿、坏=红、闲=灰）与短语。
     * 短语是人话（"未连接/连接中…/预览中"），技术性 message 不进胶囊。
     */
    var PHASE_PILLS = {
      probing: { tone: 'var(--dsw-alias-label-tertiary, rgba(127,127,127,.65))', text: '检测运行环境…' },
      ready: { tone: 'var(--dsw-alias-success-primary, #1a7f37)', text: '未连接' },
      starting: { tone: 'var(--dsw-alias-brand-primary, #1f6feb)', text: '连接中…' },
      playing: { tone: 'var(--dsw-alias-success-primary, #1a7f37)', text: '预览中' },
      stopped: { tone: 'var(--dsw-alias-label-tertiary, rgba(127,127,127,.65))', text: '已停止' },
      error: { tone: 'var(--dsw-alias-danger-primary, #d1242f)', text: '出错' },
      unsupported: { tone: 'var(--dsw-alias-danger-primary, #d1242f)', text: '无法播放' },
      unavailable: { tone: 'var(--dsw-alias-danger-primary, #d1242f)', text: '无流路由' },
    }

    function statusRow (label, value) {
      return react.createElement('div', { key: label, style: styles.row },
        react.createElement('span', { style: styles.rowLabel }, label),
        react.createElement('span', { style: styles.rowValue }, value))
    }

    /**
     * 三档按钮的工厂：primary/secondary/danger + disabled 降透明度。
     * 触控目标由 btnBase.minHeight 保证（≥36px）；style 仍可被 props.style 覆盖。
     */
    function button (kind, props, label) {
      var style = Object.assign({}, styles.btnBase, styles['btn' + kind] || styles.btnSecondary, props.style || {})
      if (props.disabled) style = Object.assign({}, style, styles.btnDisabled)
      var clean = {}
      for (var key in props) {
        if (key !== 'style') clean[key] = props[key]
      }
      return react.createElement('button', Object.assign({ type: 'button', style: style }, clean), label)
    }
    function statusRow (label, value) {
      return react.createElement('div', { key: label, style: styles.row },
        react.createElement('span', { style: styles.rowLabel }, label),
        react.createElement('span', { style: styles.rowValue }, value))
    }

    /**
     * 面板用的 H.264 fMP4 codec：**从 init 段的 avcC 里现解**（codecFromInit），
     * 不再写死。编码目标升到 1280 长边后 H.264 Level 会到 4.0（avcC:
     * profile 0x42 / compat 0x00 / level 0x28，即 420028）；写死 42001F（3.1）
     * 会被 MSE 以 SourceBuffer 错误拒绝整段流。旧 720 档（Level 3.1，42001F）
     * 也由 avcC 如实带出，两端同一条通路，无需按档位分支。
     */
    /**
     * 面板的全部同源请求面（路由扩到七条，并带 tab 取流）：
     * status/start/stop（流）、input（输入）、tasks/move/release（任务面）。
     * tab 走查询参数，控制 token 从不出 Node；任何其它 URL 一律不发（测试有哨兵）。
     */
    var ROUTE_STATUS = '/mobile-pilot/stream/status'
    var ROUTE_START = '/mobile-pilot/stream/start'
    var ROUTE_STOP = '/mobile-pilot/stream/stop'
    /** 面板输入：面板 → Node 半 → 宿主 /input；动作面 tap/swipe/back/home。 */
    var ROUTE_INPUT = '/mobile-pilot/input'
    /** 面板任务面：列虚拟屏任务 / 搬迁 / 释放（tab 走查询参数，token 不出 Node）。 */
    var ROUTE_TASKS = '/mobile-pilot/tasks'
    var ROUTE_MOVE = '/mobile-pilot/move'
    var ROUTE_RELEASE = '/mobile-pilot/release'
    /** 旋转（宿主先停流再 resize；屏上任务保留）。 */
    var ROUTE_ROTATE = '/mobile-pilot/rotate'
    /** sourceopen 的等待上限：MSE 建了却不 open（帧坏/实现挂）要给明确错误。 */
    var SOURCE_OPEN_TIMEOUT_MS = 10_000
    /**
     * init 门控：sourceopen 后这么久还没等到可识别的 init 段（ftyp+moov/avcC）就收场。
     * 8 秒不是拍的：宿主送首帧前有固定的黑帧地板（2s）+ /start 往返 + 1280×1032 首帧
     * 编码 + loopback 转发，3 秒余量会把"正常空屏"误杀成失败。测试可经 controller
     * deps 的 initWaitTimeoutMs 注入短值。
     */
    var INIT_WAIT_TIMEOUT_MS = 8000
    /** init 门控的段数上限：非 init 段连续灌到这个数还没等到 init，同样收场。阈值
     * 远高于正常流的前导盒数（styp/sidx/free 各一两条），避免病态前导提前误杀——
     * 真正的兜底是 8 秒超时。 */
    var INIT_WAIT_MAX_SEGMENTS = 400

    /**
     * 失败响应里的原因：JSON 的 reason/error 优先，其次纯文本（截断）。
     * 读不到就回空串 —— 面板据此只显示 HTTP 码，不编原因。
     * 回 `{reason, code}`；code 是宿主的结构化拒绝码（如 protected_app）。
     */
    function readFailureReason (res) {
      if (!res) return Promise.resolve({ reason: '', code: null })
      var read = null
      if (typeof res.text === 'function') {
        read = res.text()
      } else if (typeof res.json === 'function') {
        // 只有 json() 的宿主（测试替身）：先把对象摊成字符串再走同一条解析。
        read = res.json().then(function (body) {
          if (body && typeof body === 'object') return JSON.stringify(body)
          return typeof body === 'string' ? body : ''
        })
      }
      if (!read) return Promise.resolve({ reason: '', code: null })
      return Promise.resolve(read).then(function (raw) {
        if (typeof raw !== 'string') return { reason: '', code: null }
        var trimmed = raw.trim()
        if (!trimmed) return { reason: '', code: null }
        var body = null
        try { body = JSON.parse(trimmed) } catch (e) { body = null }
        if (body && typeof body === 'object') {
          var reason = body.reason || body.error
          var code = (typeof body.code === 'string' && body.code) ? body.code : null
          if (typeof reason === 'string' && reason) return { reason: reason, code: code }
          // 是 JSON 却没有原因字段：没有可读原因，不把整段 JSON 当原因显示。
          return { reason: '', code: code }
        }
        return { reason: trimmed.length > 200 ? trimmed.slice(0, 200) + '…' : trimmed, code: null }
      }, function () { return { reason: '', code: null } })
    }

    /**
     * 面板控制器：MSE 能力探测、路由发现、start/attach/stop 的全部逻辑。
     * 与 React 解耦（deps 注入 fetch/MediaSource/URL），测试用假体驱动。
     *
     * 生命周期四条硬规则：
     * 1. **disposed 优先**：unmount 后任何在飞的 start 链都不再落地（abort + 不写状态）；
     * 2. start 在飞期间再点/卸载都安全（startInFlight 门 + AbortController）；
     * 3. stop 的判据是"发过 start 就要通知 stop"，不依赖 tabToken 是否还存在；
     * 4. 重挂即新控制器实例（旧实例 disposed），不共享任何可变状态。
     */
    /**
     * fMP4 盒边界重组：`reader.read()` 的任意分片不能直接 appendBuffer —— 分片跨
     * `ftyp/moov` 或 `moof/mdat` 边界时，ISO BMFF 解析器只缓存半截盒子、不报错，
     * 流继续传但画面不前进（宿主流 189s/49MB、面板停在首帧）。
     *
     * - 初始化段：等 `ftyp` + `moov` 都完整，合成一段输出一次；
     * - 分片：等 `moof` + 紧跟的 `mdat` 都完整，合成一段输出；
     * - 其它顶层盒（styp/sidx/emsg/free）原样单段输出；EOF 的残缺尾巴丢弃。
     */
    function createSegmentAssembler () {
      var pending = new Uint8Array(0)
      var initDone = false

      function concat (a, b) {
        if (a.length === 0) return b
        if (b.length === 0) return a
        var out = new Uint8Array(a.length + b.length)
        out.set(a, 0)
        out.set(b, a.length)
        return out
      }

      function readBox (buf, at) {
        if (buf.length - at < 8) return null
        var view = new DataView(buf.buffer, buf.byteOffset + at)
        var size = view.getUint32(0)
        var type = String.fromCharCode(buf[at + 4], buf[at + 5], buf[at + 6], buf[at + 7])
        var header = 8
        if (size === 1) {
          if (buf.length - at < 16) return null
          var hi = view.getUint32(8)
          var lo = view.getUint32(12)
          size = hi * 4294967296 + lo
          header = 16
        } else if (size === 0) {
          size = buf.length - at
        }
        if (size < header) return { invalid: true, size: header, type: type }
        if (at + size > buf.length) return null
        return { size: size, type: type }
      }

      return {
        push: function (chunk) {
          pending = concat(pending, chunk)
          var out = []
          while (true) {
            if (!initDone) {
              var first = readBox(pending, 0)
              if (!first) break
              if (first.invalid) { pending = pending.slice(first.size); continue }
              if (first.type === 'moov') {
                out.push(pending.slice(0, first.size))
                pending = pending.slice(first.size)
                initDone = true
                continue
              }
              if (first.type !== 'ftyp') {
                // 不是标准 init 开头：原样单段输出，交给 MSE 判断。
                out.push(pending.slice(0, first.size))
                pending = pending.slice(first.size)
                continue
              }
              var second = readBox(pending, first.size)
              if (!second) break
              if (second.invalid) { pending = pending.slice(first.size + second.size); continue }
              if (second.type === 'moov') {
                out.push(pending.slice(0, first.size + second.size))
                pending = pending.slice(first.size + second.size)
                initDone = true
              } else {
                out.push(pending.slice(0, first.size))
                pending = pending.slice(first.size)
              }
              continue
            }
            var head = readBox(pending, 0)
            if (!head) break
            if (head.invalid) { pending = pending.slice(head.size); continue }
            if (head.type === 'moof') {
              var data = readBox(pending, head.size)
              if (!data) break
              if (data.type === 'mdat') {
                out.push(pending.slice(0, head.size + data.size))
                pending = pending.slice(head.size + data.size)
              } else {
                // moof 后不是 mdat：单段输出（畸形流交给 MSE），继续。
                out.push(pending.slice(0, head.size))
                pending = pending.slice(head.size)
              }
              continue
            }
            out.push(pending.slice(0, head.size))
            pending = pending.slice(head.size)
          }
          return out
        },
        // EOF：残缺尾巴不能 append（那正是“半截盒子”场景），丢弃。
        flush: function () {
          pending = new Uint8Array(0)
          return []
        },
      }
    }

    /**
     * 从 init 段字节里解 avcC（avcDecoderConfigRecord），拼出与编码器实际输出逐字节
     * 一致的 codec 串。1280 长边下 H.264 Level 会到 4.0（420028），写死 42001F 会被
     * MSE 以 SourceBuffer 错误拒绝整段流 —— 只能以 init 段为准。
     *
     * 布局：`i` 指向 ASCII 'avcC' 的 'a'，则 i+4 是 configurationVersion，
     * i+5/i+6/i+7 是 profile/compat/level。找不到回 null —— init 门控会继续等下一段，
     * 绝不拿写死的低 level codec 兜底（E fail-closed）。
     */
    function codecFromInit (segment) {
      if (!segment || segment.length < 8) return null
      var n = segment.length
      for (var i = 0; i + 7 < n; i += 1) {
        if (segment[i] === 0x61 && segment[i + 1] === 0x76 &&
          segment[i + 2] === 0x63 && segment[i + 3] === 0x43) {
          var hex2 = function (b) { return ('0' + ((b & 0xFF).toString(16))).slice(-2).toUpperCase() }
          return 'video/mp4; codecs="avc1.' + hex2(segment[i + 5]) + hex2(segment[i + 6]) + hex2(segment[i + 7]) + '"'
        }
      }
      return null
    }

    /**
     * D：屏幕坐标 → 视口归一化 0..1。rect 是 **video 元素**的 getBoundingClientRect()，
     * videoWidth/videoHeight 是视频内容尺寸：按等比缩放扣掉信箱边（object-fit 的黑边）
     * 再归一化，出界夹回 0..1。视频尺寸未知（0）时退化为整元素（与旧行为一致）。
     * 纯函数，测试直调。
     */
    function normalizePoint (rect, clientX, clientY, videoWidth, videoHeight) {
      var rectW = (rect && (rect.width || (rect.right - rect.left))) || 1
      var rectH = (rect && (rect.height || (rect.bottom - rect.top))) || 1
      var left = rect ? rect.left : 0
      var top = rect ? rect.top : 0
      var width = rectW
      var height = rectH
      if (videoWidth > 0 && videoHeight > 0) {
        var scale = Math.min(rectW / videoWidth, rectH / videoHeight)
        width = videoWidth * scale
        height = videoHeight * scale
        left += (rectW - width) / 2
        top += (rectH - height) / 2
      }
      var x = (clientX - left) / width
      var y = (clientY - top) / height
      return { x: Math.min(1, Math.max(0, x)), y: Math.min(1, Math.max(0, y)) }
    }

    /** tap 判定的位移阈（归一化）：终点落在起点周围这一圈内都算点按，与时长无关。 */
    var TAP_SLOP = 0.02

    /**
     * 空态预览区的撑底高度（px）。未 playing 时 video 没有内容尺寸，
     * videoWrap 用它撑出深灰空态；静态哨与运行时断言共用这一个常量，不再各写一个数。
     */
    var EMPTY_PREVIEW_MIN_HEIGHT = 180

    /**
     * 预览的 CSS aspect-ratio。优先取 /start 回包 metadata 的源尺寸
     * （sourceWidth/sourceHeight，宿主按虚拟屏真实分辨率写），取不到退回 video 元素
     * 的 videoWidth/videoHeight；两者都没有（未起会话）回 null —— 渲染处不给
     * aspect-ratio，也不写死 16/9。纯函数，测试直调。
     */
    function previewAspectRatio (metadata, videoWidth, videoHeight) {
      var w = (metadata && typeof metadata.sourceWidth === 'number') ? metadata.sourceWidth : 0
      var h = (metadata && typeof metadata.sourceHeight === 'number') ? metadata.sourceHeight : 0
      if (!(w > 0 && h > 0)) {
        w = typeof videoWidth === 'number' ? videoWidth : 0
        h = typeof videoHeight === 'number' ? videoHeight : 0
      }
      return (w > 0 && h > 0) ? (w + ' / ' + h) : null
    }

    /**
     * D：手势分类。位移 < TAP_SLOP → tap（用终点坐标，**不看时长**——慢点按也是
     * 点按，不再变零距离 swipe）；否则 swipe（起止点 + 实际时长夹到 50..1000ms，
     * 长按拖动即长 swipe）。纯函数，测试直调。
     */
    function classifyGesture (start, end) {
      var dx = end.x - start.x
      var dy = end.y - start.y
      var dt = Math.max(1, end.t - start.t)
      if (dx * dx + dy * dy < TAP_SLOP * TAP_SLOP) {
        return { kind: 'tap', x: end.x, y: end.y }
      }
      return {
        kind: 'swipe',
        x1: start.x, y1: start.y, x2: end.x, y2: end.y,
        durationMs: Math.min(1000, Math.max(50, dt)),
      }
    }

    function createStreamPanelController (deps) {
      var fetchImpl = deps.fetch
      var MediaSourceCtor = deps.MediaSource
      var urlCreate = deps.urlCreate
      var urlRevoke = deps.urlRevoke
      var getVideo = deps.getVideo || function () { return null }
      var onChange = deps.onChange || function () {}
      var sourceOpenTimeoutMs = typeof deps.sourceOpenTimeoutMs === 'number' ? deps.sourceOpenTimeoutMs : SOURCE_OPEN_TIMEOUT_MS
      // init 门控等待：默认 8 秒（空屏的黑帧地板 + 首帧编码余量）；测试注入短值。
      var initWaitTimeoutMs = typeof deps.initWaitTimeoutMs === 'number' ? deps.initWaitTimeoutMs : INIT_WAIT_TIMEOUT_MS
      var state = {
        phase: 'probing', message: '', tabToken: null,
        reader: null, mediaSource: null, objectUrl: null,
        abort: null, startInFlight: false, startedOnce: false, disposed: false,
        // 首错保留：本届会话是否已经落过一次失败原因（见 failSession）。
        failed: false,
        // /start 或 /stream 的 HTTP 状态：refresh 的 502 排水重试按状态码判，
        // 不靠中英文文案正则。
        lastFailureStatus: 0,
        // 用户/面板主动中止的标记：只有这种中止才静默，其余 AbortError 是异常中止。
        userAbort: false,
        // 首帧 append 完成前不 play：MediaSource 还没数据时 play() 会失败，之后没人重试，
        // 视频就停在首帧（宿主流跑 189 秒/49MB，画面不动）。
        firstAppend: false,
        // 本届会话实际建 SourceBuffer 用的 codec 串（init 段 avcC 现解，见 appendNext）。
        codec: null,
        // /start 回包里的 metadata（宿主写：mime/codec/width/height/sourceWidth/sourceHeight）。
        metadata: null,
        // 最近一次面板输入的可见结论：成功清空，失败在这里说原因（状态卡显示）。
        inputNote: '',
        // 精修 4：最近一次任务/输入失败的技术细节（HTTP 状态等），只进诊断折叠。
        httpDetail: '',
        // 旋转进行中（面板防连点）。
        rotatePending: false,
        // T1：刷新预览进行中（stop → 排水重试 → start）。
        refreshPending: false,
        // 释放进行中（面板防连点）——发起时 true，成功/失败完成时
        // false。必须进控制器 state 并经 onChange 透传，否则面板的释放按钮读不到复位，
        // 会永久停在"正在释放…"。
        releasePending: false,
        // 面板任务面（仅内部取数，不再渲染列表；加载/过期
        // 两个只写不读的死状态已删）：宿主 /tasks 透传、"移到主屏"的可见结论。
        tasks: null,
        tasksError: '',
        taskNote: '',
        // 最近一次被"移到主屏"的任务号——"移回虚拟屏"按钮搬的就是它。
        lastMovedTaskId: null,
        // 受保护前台拒绝过一次开流后置位——自动开流不再重试（手动 start 仍可）。
        protectedBlocked: false,
        // init 门控的超时哨：sourceopen 后 init 迟迟不到时收场（句柄进 state，
        // 让 failSession → cleanupLocal 一并清掉）。
        initWaitTimer: null,
        detachCanPlay: null,
        // 面板诊断行：段数 / buffered / currentTime / paused / readyState。
        diag: '',
        diagTimer: null,
        playRetryTimer: null,
        // T1 排水重试定时器：dispose/cleanup 必须清掉，避免旧刷新在卸载后继续拉流。
        drainTimer: null,
        // 流会话代际：每次 cleanup/start 递增。旧读循环、旧计时器、旧 fetch 回调
        // 必须先比对代际，不能把新一届会话的 reader/abort/metadata 清掉。
        epoch: 0,
      }

      function patch (next) {
        var key
        for (key in next) state[key] = next[key]
        onChange(state)
        return state
      }

      function isCurrent (epoch) {
        return !state.disposed && epoch === state.epoch
      }

      /** 真正开始播放：MediaSource 没数据时的 play() 会拒绝，canplay/首帧 append 后会重试。 */
      function ensurePlaying () {
        var video = getVideo()
        if (!video || typeof video.play !== 'function') return
        try { video.muted = true; video.autoplay = true } catch (e) { /* 属性不可写则忽略 */ }
        try {
          var played = video.play()
          if (played && typeof played.catch === 'function') {
            played.catch(function () {
              if (state.disposed || state.playRetryTimer) return
              state.playRetryTimer = setTimeout(function () {
                state.playRetryTimer = null
                var v = getVideo()
                if (!state.disposed && v && v.paused) ensurePlaying()
              }, 500)
            })
          }
        } catch (e) {
          if (!state.disposed && !state.playRetryTimer) {
            state.playRetryTimer = setTimeout(function () {
              state.playRetryTimer = null
              var v = getVideo()
              if (!state.disposed && v && v.paused) ensurePlaying()
            }, 500)
          }
        }
      }

      /** MSE 能力：只探 MediaSource 在不在。具体 codec 等 init 段到手再判（appendNext）。 */
      function capability () {
        if (typeof MediaSourceCtor !== 'function') {
          return { ok: false, reason: '此运行环境没有 MediaSource（不支持 MSE），无法播放实时预览' }
        }
        return { ok: true }
      }

      /** 路由发现：Node 半没挂上路由（runtime 无 webServer 服务）时如实报告。 */
      function discover () {
        if (state.disposed) return Promise.resolve(state)
        if (typeof fetchImpl !== 'function') {
          return Promise.resolve(patch({ phase: 'unavailable', message: '此运行环境没有 fetch，无法取流' }))
        }
        var discoverAbort = new AbortController()
        state.abort = discoverAbort
        return fetchImpl(ROUTE_STATUS, { signal: discoverAbort.signal }).then(function (res) {
          if (state.disposed) return state
          if (res && res.ok) return patch({ phase: 'ready', message: '' })
          return patch({
            phase: 'unavailable',
            httpDetail: 'discover: HTTP ' + (res ? res.status : '?'),
            message: 'DSH runtime 未提供实时流路由：本版本的面板无法取流；' +
              '手机助手工具与 CLI 不受影响',
          })
        }, function () {
          if (state.disposed) return state
          return patch({
            phase: 'unavailable',
            message: 'DSH runtime 未提供实时流路由：本版本的面板无法取流；手机助手工具与 CLI 不受影响',
          })
        })
      }

      /** 释放本端的全部流资源（abort 读取、撤 objectURL、清 video 元素）。 */
      function cleanupLocal () {
        // 先换代会：旧 reader/fetch/计时器即使晚一拍回来，也不得再碰新会话。
        state.epoch += 1
        if (state.abort) { try { state.abort.abort() } catch (e) { /* 已断则忽略 */ } }
        if (state.reader) { try { state.reader.cancel() } catch (e) { /* 已断则忽略 */ } }
        if (state.detachCanPlay) {
          try { state.detachCanPlay() } catch (e) { /* 已摘则忽略 */ }
          state.detachCanPlay = null
        }
        if (state.diagTimer) { clearInterval(state.diagTimer); state.diagTimer = null }
        if (state.initWaitTimer) { clearTimeout(state.initWaitTimer); state.initWaitTimer = null }
        if (state.playRetryTimer) { clearTimeout(state.playRetryTimer); state.playRetryTimer = null }
        if (state.drainTimer) { clearTimeout(state.drainTimer); state.drainTimer = null }
        if (state.clearOpenTimer) { state.clearOpenTimer(); state.clearOpenTimer = null }
        if (state.detachVideoError) { state.detachVideoError(); state.detachVideoError = null }
        state.codec = null
        // 上一届的 metadata 一并清：失败重启后诊断行不得显示上一届的 src/enc。
        state.metadata = null
        if (state.objectUrl && typeof urlRevoke === 'function') {
          try { urlRevoke(state.objectUrl) } catch (e) { /* 已撤则忽略 */ }
        }
        var video = getVideo()
        if (video) {
          try { video.pause() } catch (e) { /* 未在播则忽略 */ }
          video.removeAttribute('src')
          try { video.load() } catch (e) { /* 无 src 时忽略 */ }
        }
        state.reader = null
        state.mediaSource = null
        state.objectUrl = null
        state.abort = null
      }

      /**
       * 停流：发过 start（startedOnce）就必须通知 /stop —— 不拿 tabToken 当唯一判据
       * （start 失败也可能已消耗一次 start）。disposed 后仍要通知（unmount 中途的
       * stop 是最后一次机会让设备侧停编码）。
       */
      /**
       * 会话级失败：cancel 读取、通知 /stop（"发过 start 就要配平 stop"）、清本端
       * 资源、落错误状态。sourceopen 超时/video.error/SourceBuffer 错误/appendBuffer
       * 抛错/读取失败全部走这里 —— 任何错误都能停编码。
       *
       * **首错保留**：失败清理里的 reader.cancel() 会让挂起的 read() 以
       * 「BodyStreamBuffer was aborted」拒绝 —— 那是**同一次失败的尾巴**，不是第二个
       * 原因。因此第一次失败的原因与文案就此定格，后续失败只做收尾，不再覆盖。
       * 面板读到的 message 始终是第一条失败原因。
       */
      function failSession (message, epoch) {
        if (epoch != null && !isCurrent(epoch)) return
        var first = !state.failed
        state.failed = true
        if (first) state.lastError = message
        var notify = state.startedOnce
        var tab = state.tabToken
        if (state.clearOpenTimer) state.clearOpenTimer()
        if (state.detachVideoError) state.detachVideoError()
        cleanupLocal()
        state.tabToken = null
        state.startedOnce = false
        if (!first || state.userAbort) {
          // 二次失败（或用户主动停引发的收尾）：文案与相位都不动（首条原因仍在），
          // 只把资源收干净；metadata 一并清并通知渲染 —— 非 playing 时不得残留上一届
          // 的 aspect-ratio。
          patch({ metadata: null })
          return
        }
        // 落在 **stopped**（消息保留）：start 从 stopped 可重试 —— 错误可见但不锁死。
        patch({ phase: 'stopped', message: message, metadata: null })
        if (!notify || typeof fetchImpl !== 'function') return
        try {
          var suffix = tab ? '?tab=' + encodeURIComponent(tab) : ''
          fetchImpl(ROUTE_STOP + suffix, { method: 'POST', keepalive: true }).then(function () {}, function () {})
        } catch (e4) { /* 通知失败由宿主 watchdog 兜底 */ }
      }

      function stop () {
        var notify = state.startedOnce
        var tab = state.tabToken
        // 用户主动停：在飞的取流被中止是预期动作，不是失败。
        state.userAbort = true
        cleanupLocal()
        state.tabToken = null
        state.startedOnce = false
        if (state.phase === 'playing' || notify) patch({ phase: 'ready', message: '' })
        if (!notify || typeof fetchImpl !== 'function') return Promise.resolve()
        var suffix = tab ? '?tab=' + encodeURIComponent(tab) : ''
        return fetchImpl(ROUTE_STOP + suffix, { method: 'POST', keepalive: true }).then(function () {}, function () {})
      }

      /**
       * 刷新预览：只重启流会话，虚拟屏与屏上任务不动。先 stop 再 start；
       * /stop 排水期可能回 "already running (or still draining)" 502，做有界重试。
       */
      function refresh () {
        if (state.disposed || state.refreshPending || state.releasePending || state.rotatePending) {
          return Promise.resolve(state)
        }
        if (state.phase !== 'playing') {
          return Promise.resolve(patch({ taskNote: '刷新预览仅在播放中可用' }))
        }
        patch({ refreshPending: true, taskNote: '正在刷新预览…' })
        return stop().then(function () {
          if (state.disposed) return state
          return startWithDrainRetry(3, { preserveTaskLedger: true })
        }).then(function () {
          if (state.disposed) return state
          return patch({
            refreshPending: false,
            taskNote: state.phase === 'playing' ? '预览已刷新' : '刷新失败，可重试',
          })
        }, function (error) {
          if (state.disposed) return state
          return patch({
            refreshPending: false,
            taskNote: '刷新失败，可重试' + ((error && error.message) ? '：' + error.message : ''),
          })
        })
      }

      /** start 的排水重试：上一届 /stop 未完成时，宿主会短暂回 502。 */
      function startWithDrainRetry (attemptsLeft, options) {
        return start(options).then(function () {
          if (state.disposed || state.phase === 'playing') return state
          var draining = state.lastFailureStatus === 502 ||
            /already running|still draining|HTTP 502/.test(state.message || '')
          if (attemptsLeft <= 0 || !draining) return state
          return new Promise(function (resolve) {
            state.drainTimer = setTimeout(function () {
              state.drainTimer = null
              resolve(startWithDrainRetry(attemptsLeft - 1, options))
            }, 700)
          })
        })
      }

      /**
       * 面板输入：tap/swipe/back/home → Node 半 /input → 宿主 /input。
       * 会话不在（无 tabToken）时给可读结论而不是静默；失败把原因写进
       * state.inputNote（状态卡显示），成功清空且**不改变播放状态**。
       */
      function sendInput (payload) {
        if (state.disposed) return Promise.resolve()
        var tab = state.tabToken
        if (!tab || typeof fetchImpl !== 'function') {
          patch({ inputNote: '输入不可用：没有进行中的预览会话' })
          return Promise.resolve()
        }
        var body = { tab: tab }
        for (var key in payload) body[key] = payload[key]
        return fetchImpl(ROUTE_INPUT, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        }).then(function (res) {
          if (state.disposed) return state
          if (res && res.ok) {
            patch({ inputNote: '' })
            return state
          }
          return readFailureReason(res).then(function (failure) {
            if (state.disposed) return state
            var reason = failure && failure.reason
            patch({
              inputNote: '输入失败' + (reason ? '：' + reason : '（宿主没有给出原因）'),
              httpDetail: 'input: HTTP ' + (res ? res.status : '?') +
                (reason ? ' — ' + reason : ''),
            })
            return state
          })
        }, function (error) {
          if (state.disposed || state.userAbort) return state
          patch({ inputNote: '输入请求失败：' + ((error && error.message) || error) })
          return state
        })
      }

      /**
       * 面板任务面：任务搬迁到主屏/虚拟屏。tab 走查询参数；失败原因写进
       * state.taskNote（状态卡显示），成功写一句可见结论并按需刷新任务列表。
       *
       * "移到主屏"成功后记 [lastMovedTaskId] —— "移回虚拟屏"按钮搬的就是它
       * （宿主在主屏列表里认根，本端只负责记住哪条被搬走了）。
       */
      function sendMove (taskId, direction) {
        if (state.disposed) return Promise.resolve()
        var tab = state.tabToken
        if (!tab || typeof fetchImpl !== 'function') {
          patch({ taskNote: '任务搬迁不可用：没有进行中的预览会话' })
          return Promise.resolve()
        }
        return fetchImpl(ROUTE_MOVE + '?tab=' + encodeURIComponent(tab), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ taskId: taskId, direction: direction }),
        }).then(function (res) {
          if (state.disposed) return state
          if (res && res.ok) {
            if (direction === 'main') {
              state.lastMovedTaskId = taskId
            } else {
              // 已搬回虚拟屏：这条不再欠着"移回"。
              state.lastMovedTaskId = null
            }
            patch({
              taskNote: direction === 'main' ? '已移到主屏' : '已移回虚拟屏',
            })
            // 搬迁成功后立刻内部刷新一次（loadTasks 读的是对面那块屏，供下一次取数）。
            return loadTasks()
          }
          return readFailureReason(res).then(function (failure) {
            if (state.disposed) return state
            patch({
              taskNote: '任务搬迁失败，可重试' +
                ((failure && failure.reason) ? '：' + failure.reason : '（宿主没有给出原因）'),
              httpDetail: 'task move: HTTP ' + (res ? res.status : '?') +
                ((failure && failure.reason) ? ' — ' + failure.reason : ''),
            })
            return state
          })
        }, function (error) {
          if (state.disposed || state.userAbort) return state
          patch({ taskNote: '任务搬迁请求失败：' + ((error && error.message) || error) })
          return state
        })
      }

      /**
       * 旋转：面板第四动作。走 Node 半 /mobile-pilot/rotate → 宿主 /rotate
       * （宿主先停流再 resize，屏上任务保留）；成功后停流复位到可开始预览，状态卡写
       * "已旋转，点开始预览"；失败写人话，HTTP/技术细节进诊断折叠。防连点由
       * rotatePending 承担（进行中按钮禁用）。
       */
      function rotateDisplay () {
        if (state.disposed) return Promise.resolve()
        if (state.rotatePending || state.refreshPending || state.releasePending) return Promise.resolve()
        var tab = state.tabToken
        if (!tab || typeof fetchImpl !== 'function') {
          patch({ taskNote: '旋转不可用：没有进行中的预览会话' })
          return Promise.resolve()
        }
        patch({ rotatePending: true, taskNote: '' })
        return fetchImpl(ROUTE_ROTATE + '?tab=' + encodeURIComponent(tab), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({}),
        }).then(function (res) {
          if (state.disposed) return state
          if (res && res.ok) {
            // 宿主已停流并旋转：本端照 release 同款收干净，面板回 ready 可再开。
            cleanupLocal()
            state.tabToken = null
            state.startedOnce = false
            var dims = ''
            // 宿主回包带新尺寸（{"dimensions":{"width":…,"height":…}}）：读出来给提示。
            return Promise.resolve(res.json ? res.json() : null).then(function (body) {
              var d = body && body.dimensions
              if (d && typeof d.width === 'number' && typeof d.height === 'number') {
                dims = '（新尺寸 ' + d.width + '×' + d.height + '）'
              }
              patch({
                phase: 'ready',
                message: '',
                taskNote: '',
                inputNote: '',
                tasks: null,
                tasksError: '',
                rotatePending: false,
              })
              // 旋转成功后自动拉起预览（第一次 90° 的残影/裁切也靠这次重启
              // 编码器收口）；任务不因旋转丢失，“移回”的账要保留。
              patch({ taskNote: '已旋转' + dims + '，正在重新连接预览…' })
              return start({ preserveTaskLedger: true }).then(function () {
                var after = state
                if (!after.disposed && after.phase !== 'playing') {
                  patch({ taskNote: '已旋转' + dims + '，点开始预览' })
                }
                return state
              })
              /* unreachable: 原"点开始预览"提示由上面的 start 失败分支给出 */
            }, function () {
              patch({
                phase: 'ready', message: '', taskNote: '已旋转，点开始预览',
                inputNote: '', tasks: null, tasksError: '', rotatePending: false,
              })
              return state
            })
          }
          return readFailureReason(res).then(function (failure) {
            patch({
              // 人话 + 可重试；HTTP/技术细节进诊断折叠（state.httpDetail）。
              taskNote: '旋转失败，可重试' +
                ((failure && failure.reason) ? '：' + failure.reason : '（宿主没有给出原因）'),
              rotatePending: false,
              httpDetail: 'rotate: HTTP ' + (res ? res.status : '?') +
                ((failure && failure.reason) ? ' — ' + failure.reason : ''),
            })
            return state
          })
        }, function (error) {
          if (state.disposed || state.userAbort) return state
          patch({ taskNote: '旋转请求失败：' + ((error && error.message) || error), rotatePending: false })
          return state
        })
      }

      /**
       * "移回虚拟屏"按钮：把上一次被"移到主屏"的那条搬回来。
       * 没有记录（本届会话还没搬过）时给可读提示，不发请求。
       */
      function moveBackToVirtual () {
        if (state.disposed) return Promise.resolve()
        var taskId = state.lastMovedTaskId
        if (taskId === null || taskId === undefined) {
          patch({ taskNote: '还没有移到主屏的任务：先点「移到主屏」' })
          return Promise.resolve()
        }
        return sendMove(taskId, 'virtual')
      }

      /**
       * 释放虚拟屏（面板三动作之三）：**屏上应用会被关闭**——调用方 UI 已二次
       * 确认。成功后停流复位（Node 半在宿主 /release 里先停流，这里只复位本地账）。
       */
      function releaseDisplay () {
        if (state.disposed || state.releasePending || state.refreshPending || state.rotatePending) {
          return Promise.resolve()
        }
        var tab = state.tabToken
        if (!tab || typeof fetchImpl !== 'function') {
          patch({ taskNote: '释放不可用：没有进行中的预览会话' })
          return Promise.resolve()
        }
        patch({ releasePending: true })
        return fetchImpl(ROUTE_RELEASE + '?tab=' + encodeURIComponent(tab), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({}),
        }).then(function (res) {
          if (state.disposed) return state
          if (res && res.ok) {
            // 宿主已停流并收屏：本端资源照 stop 同款收干净，面板回 ready 可再开。
            cleanupLocal()
            state.tabToken = null
            state.startedOnce = false
            patch({
              phase: 'ready', message: '', taskNote: '', tasks: null, tasksError: '',
              releasePending: false,
            })
            return state
          }
          return readFailureReason(res).then(function (failure) {
            if (state.disposed) return state
            patch({
              taskNote: '释放失败，可重试' +
                ((failure && failure.reason) ? '：' + failure.reason : '（宿主没有给出原因）'),
              httpDetail: 'release: HTTP ' + (res ? res.status : '?') +
                ((failure && failure.reason) ? ' — ' + failure.reason : ''),
              releasePending: false,
            })
            return state
          })
        }, function (error) {
          if (state.disposed || state.userAbort) return state
          patch({
            taskNote: '释放请求失败：' + ((error && error.message) || error),
            releasePending: false,
          })
          return state
        })
      }

      /**
       * 虚拟屏任务面（内部取数专用，不再有列表 UI）：拉一次宿主
       * /tasks（Node 半透传 displayId/tasks），结果进 state（tasks/tasksError）供
       * "移到主屏"取第一条真实应用任务；失败原因不携带任何 token 或端点原文，
       * HTTP 状态等技术细节只进诊断折叠（state.httpDetail）。
       */
      function loadTasks () {
        if (state.disposed) return Promise.resolve(state)
        var tab = state.tabToken
        if (!tab || typeof fetchImpl !== 'function') {
          patch({ tasks: null, tasksError: '没有进行中的预览会话' })
          return Promise.resolve(state)
        }
        return fetchImpl(ROUTE_TASKS + '?tab=' + encodeURIComponent(tab), {}).then(function (res) {
          if (state.disposed) return state
          if (res && res.ok) {
            return Promise.resolve(res.json ? res.json() : null).then(function (body) {
              if (state.disposed) return state
              var tasks = (body && Array.isArray(body.tasks)) ? body.tasks : []
              patch({
                tasks: tasks,
                tasksError: tasks.length === 0 ? '虚拟屏上当前没有任务' : '',
              })
              return state
            }, function () {
              if (state.disposed) return state
              patch({ tasks: null, tasksError: '任务列表回包无法解析' })
              return state
            })
          }
          return readFailureReason(res).then(function (failure) {
            if (state.disposed) return state
            patch({
              tasks: null,
              tasksError: '任务列表获取失败，可重试' +
                ((failure && failure.reason) ? '：' + failure.reason : '（宿主没有给出原因）'),
              httpDetail: 'tasks: HTTP ' + (res ? res.status : '?') +
                ((failure && failure.reason) ? ' — ' + failure.reason : ''),
            })
            return state
          })
        }, function (error) {
          if (state.disposed || state.userAbort) return state
          patch({
            tasks: null,
            tasksError: '任务列表请求失败：' + ((error && error.message) || error),
          })
          return state
        })
      }

      /** 把流响应接进 MSE：sourceopen（带超时）→ SourceBuffer → 逐块 append，EOF 收尾。 */
      function attachStream (response, epoch) {
        if (!isCurrent(epoch)) return
        var ms = new MediaSourceCtor()
        var objectUrl = typeof urlCreate === 'function' ? urlCreate(ms) : null
        var video = getVideo()
        if (objectUrl && video) video.src = objectUrl
        state.mediaSource = ms
        state.objectUrl = objectUrl
        var reader = response.body.getReader()
        state.reader = reader

        var settled = false
        var openTimer = setTimeout(function () {
          if (settled || !isCurrent(epoch)) return
          settled = true
          failSession('MSE sourceopen 超时：媒体源没有就绪', epoch)
        }, sourceOpenTimeoutMs)
        state.clearOpenTimer = function () { clearTimeout(openTimer) }

        function onVideoError () {
          if (settled || !isCurrent(epoch)) return
          settled = true
          clearTimeout(openTimer)
          var mediaError = null
          try { mediaError = video && video.error } catch (e2) { mediaError = null }
          failSession('视频错误：' + ((mediaError && mediaError.message) || '未知'), epoch)
        }
        if (video && typeof video.addEventListener === 'function') {
          video.addEventListener('error', onVideoError)
          state.detachVideoError = function () {
            try { video.removeEventListener('error', onVideoError) } catch (e2) { /* 已摘则忽略 */ }
          }
          // 数据够播放时再试一次 play：首帧数据到手前 play() 会失败，且没有人会重试。
          video.addEventListener('canplay', ensurePlaying)
          state.detachCanPlay = function () {
            try { video.removeEventListener('canplay', ensurePlaying) } catch (e2) { /* 已摘则忽略 */ }
          }
        }

        function finishOpen () {
          if (settled) return
          settled = true
          clearTimeout(openTimer)
        }

        ms.addEventListener('sourceopen', function () {
          if (!isCurrent(epoch)) return
          finishOpen()
          // buffer 延后到 **可识别的 init 段**（ftyp+moov 带 avcC）到手才建：codec 串
          // 要从 avcC 现解（1280 长边下 Level 是 4.0/420028，旧 720 档是 3.1/42001F），
          // 且**绝不回退到写死的低 level 候选** —— 用 42001F 建 buffer 后再吃到 4.0
          // 的 init，MSE 会以 SourceBuffer 错误拒整段流。
          var buffer = null
          // init 门控：非 init 段（styp/sidx/free、或 moov 没有 avcC 的残缺流）先丢弃
          // 计数，等真正的 init；超时或超段数都如实收场，不用兜底 codec 硬撑。
          var skippedSegments = 0
          // 两种超时要分开说：一个字节都没到（连接死/宿主没开写）与到了字节但 init/avcC
          // 迟迟不来（空屏在等黑帧地板，或残缺流）—— 文案混用会把正常空屏说成坏流。
          var receivedBytes = false
          state.initWaitTimer = setTimeout(function () {
            if (buffer || !isCurrent(epoch)) return
            failSession(receivedBytes
              ? '收到了流数据，但没有可识别的 fMP4 初始化段（ftyp+moov/avcC）'
              : '没有收到任何流字节（面板连接已建立）', epoch)
          }, initWaitTimeoutMs)
          var assembler = createSegmentAssembler()
          var queue = []
          var ended = false
          var diagSegs = 0
          var diagBytes = 0

          function updateDiag () {
            if (!isCurrent(epoch)) return
            var video = getVideo()
            var ranges = []
            try {
              if (buffer) {
                for (var i = 0; i < buffer.buffered.length; i += 1) {
                  ranges.push(buffer.buffered.start(i).toFixed(2) + '-' + buffer.buffered.end(i).toFixed(2))
                }
              }
            } catch (e) { /* buffered 不可读则显示空 */ }
            var codecLabel = 'none'
            if (state.codec) {
              var m = /avc1\.([0-9A-Fa-f]{6})/.exec(state.codec)
              codecLabel = m ? m[1] : state.codec
            }
            // src/enc 来自 /start 回包的 metadata：源尺寸与实际编码尺寸分开看
            // （1280 编码器配不上回退 720 时两者不同，糊不糊要能对着日志分辨）。
            var md = state.metadata
            var dims = function (w, h) {
              return (typeof w === 'number' && typeof h === 'number' && w > 0 && h > 0) ? w + 'x' + h : '-'
            }
            var t = video ? video.currentTime : -1
            var paused = video ? video.paused : null
            var ready = video ? video.readyState : -1
            patch({
              diag: 'src=' + dims(md && md.sourceWidth, md && md.sourceHeight) +
                ' enc=' + dims(md && md.width, md && md.height) +
                ' codec=' + codecLabel + ' segs=' + diagSegs + ' bytes=' + diagBytes +
                ' buffered=[' + ranges.join(',') + ']' +
                ' t=' + (typeof t === 'number' ? t.toFixed(2) : t) +
                ' paused=' + paused + ' ready=' + ready,
            })
          }
          state.diagTimer = setInterval(updateDiag, 1000)
          updateDiag()

          function finishEnded () {
            if (!isCurrent(epoch)) return
            try { ms.endOfStream() } catch (e2) { /* 已收则忽略 */ }
            if (state.clearOpenTimer) state.clearOpenTimer()
            if (state.detachVideoError) state.detachVideoError()
            // EOF 后清掉旧 MediaSource/objectURL：长会话重开时不复用死源。
            cleanupLocal()
            patch({ phase: 'stopped', message: '流已结束' })
          }

          /**
           * init 段到手才建 SourceBuffer，fail-closed：dynamic 是 avcC 现解的唯一
           * 候选 —— 不支持就收场并点名 codec，不试 42001F/42E01F/42E01E。
           * 回 true = buffer 已就绪可 append；false = 本段已处理（丢弃或已失败）。
           */
          function ensureBuffer (segment) {
            var dynamic = codecFromInit(segment)
            if (!dynamic) {
              skippedSegments += 1
              if (skippedSegments >= INIT_WAIT_MAX_SEGMENTS) {
                failSession('没有收到可识别的 fMP4 初始化段（ftyp+moov/avcC）', epoch)
              }
              return false
            }
            var supported = false
            try { supported = !!MediaSourceCtor.isTypeSupported(dynamic) } catch (e0) { supported = false }
            if (!supported) {
              failSession('此渲染引擎不支持预览所需的 ' + dynamic + '；请降低预览分辨率或更换渲染引擎', epoch)
              return false
            }
            try { buffer = ms.addSourceBuffer(dynamic) } catch (e) {
              failSession('MSE 初始化失败：' + ((e && e.message) || e), epoch)
              return false
            }
            // SourceBuffer 自身的错误也是一等的失败场景；文案带上实际 codec。
            buffer.addEventListener('error', function () {
              if (!isCurrent(epoch)) return
              failSession('SourceBuffer 错误（codec=' + (state.codec || dynamic) + '）', epoch)
            })
            state.codec = dynamic
            if (state.initWaitTimer) { clearTimeout(state.initWaitTimer); state.initWaitTimer = null }
            return true
          }

          function appendNext () {
            if (!isCurrent(epoch)) return
            if (queue.length === 0) {
              if (ended) finishEnded()
              else pump()
              return
            }
            var segment = queue.shift()
            if (!buffer) {
              if (!ensureBuffer(segment)) {
                // 非 init 段被丢弃（或会话已失败）：不计数不 append，等下一段。
                if (!state.failed && !ended) pump()
                return
              }
            }
            diagSegs += 1
            diagBytes += segment.length
            // updateend 监听必须在 appendBuffer 之前挂上：事件在追加完成后的
            // 微任务里发，晚挂一拍就会漏掉一次、整条泵链断流。
            var onEnd = function () {
              buffer.removeEventListener('updateend', onEnd)
              if (!isCurrent(epoch)) return
              if (!state.firstAppend) {
                state.firstAppend = true
              }
              var video = getVideo()
              if (video && video.paused) ensurePlaying()
              updateDiag()
              appendNext()
            }
            buffer.addEventListener('updateend', onEnd)
            try {
              buffer.appendBuffer(segment)
            } catch (e3) {
              buffer.removeEventListener('updateend', onEnd)
              failSession('MSE 追加失败：' + ((e3 && e3.message) || e3), epoch)
            }
          }

          function pump () {
            reader.read().then(function (chunk) {
              if (!isCurrent(epoch)) return
              if (chunk.done) {
                ended = true
                var tail = assembler.flush()
                if (tail.length) queue.push.apply(queue, tail)
                appendNext()
                return
              }
              if (chunk.value && chunk.value.length > 0) receivedBytes = true
              var segments = assembler.push(chunk.value)
              if (segments.length) queue.push.apply(queue, segments)
              appendNext()
            }, function (err) {
              if (!isCurrent(epoch)) return
              failSession('流读取失败：' + ((err && err.message) || err), epoch)
            })
          }
          appendNext()
        })
      }

      function start (options) {
        if (state.disposed || state.startInFlight) return Promise.resolve()
        if (state.phase !== 'ready' && state.phase !== 'stopped') return Promise.resolve()
        var cap = capability()
        if (!cap.ok) {
          patch({ phase: 'unsupported', message: cap.reason })
          return Promise.resolve()
        }
        var preserveTaskLedger = !!(options && options.preserveTaskLedger)
        var epoch = ++state.epoch
        patch({ phase: 'starting', message: '' })
        state.startInFlight = true
        state.startedOnce = true
        // 新一届会话：首错重新计（上一届的原因不该压住这一届）。
        state.failed = false
        state.lastError = ''
        state.lastFailureStatus = 0
        state.userAbort = false
        state.firstAppend = false
        state.codec = null
        state.metadata = null
        state.inputNote = ''
        // 新一届会话：任务列表清账（上一届的列表对这一届的屏毫无意义）。
        state.tasks = null
        state.tasksError = ''
        state.taskNote = ''
        if (!preserveTaskLedger) state.lastMovedTaskId = null
        state.rotatePending = false
        state.releasePending = false
        var abort = new AbortController()
        state.abort = abort
        return fetchImpl(ROUTE_START, { method: 'POST', signal: abort.signal }).then(function (res) {
          if (!isCurrent(epoch)) return null
          if (!res.ok) {
            state.lastFailureStatus = res.status
            // 失败响应体里带原因（宿主侧原因最有用）：只显示 HTTP 码等于没有场景。
            return readFailureReason(res).then(function (failure) {
              var reason = failure && failure.reason
              if (failure && failure.code === 'protected_app') {
                // 数据面守卫：受保护应用前台不给面板预览/输入。给出明确文案，
                // 记 protectedBlocked——自动开流不再重试，直到用户手动 start。
                state.protectedBlocked = true
                throw new Error('受保护应用不支持面板预览' + (reason ? '：' + reason : ''))
              }
              throw new Error('预览开不起来' + (reason ? '：' + reason : '（宿主没有给出原因）'))
            })
          }
          return res.json()
        }).then(function (body) {
          if (!isCurrent(epoch) || body === null) return null
          if (!body || body.ok !== true || typeof body.tabToken !== 'string') {
            throw new Error('start 回包形状不对')
          }
          state.tabToken = body.tabToken
          // 宿主的会话元数据（mime/codec/width/height/sourceWidth/sourceHeight）：
          // 诊断行用它区分"源多大"与"实际编码多大"。
          state.metadata = (body.metadata && typeof body.metadata === 'object') ? body.metadata : null
          return fetchImpl('/mobile-pilot/stream?tab=' + encodeURIComponent(body.tabToken), { signal: abort.signal })
        }).then(function (res) {
          if (!isCurrent(epoch) || res === null) return null
          if (!res.ok || !res.body) {
            state.lastFailureStatus = res.status
            // 409（会话已被占用）/502（上游拒绝）都带原因：读出来一并显示。
            return readFailureReason(res).then(function (failure) {
              var reason = failure && failure.reason
              throw new Error('流连接失败' + (reason ? '：' + reason : '（宿主没有给出原因）'))
            })
          }
          attachStream(res, epoch)
          return null
        }).then(function () {
          if (!isCurrent(epoch)) return
          state.protectedBlocked = false  // 会话真起来了：受保护挡流的假设失效
          patch({ phase: 'playing', message: '' })
          // 此时 MediaSource 可能还没数据；真正开播由 canplay / 首帧 updateend 触发。
          ensurePlaying()
        }, function (error) {
          if (!isCurrent(epoch)) return
          // 面板被卸载或用户主动停：静默收场，不写文案（那不是失败）。
          if (state.userAbort) {
            cleanupLocal()
            return
          }
          // 异常中止（不是本模块发起的 AbortError）：给一句可读文案，
          // 别让面板停在「正在连接」却什么都不说。
          if (error && error.name === 'AbortError') {
            failSession('取流被中止（AbortError）：请求被中断', epoch)
            return
          }
          // 失败也配平 stop：Node 半可能已开了会话（比如 attach 断的）。
          failSession((error && error.message) || String(error), epoch)
        }).then(function () {
          state.startInFlight = false
        })
      }

      /** unmount/隐藏的唯一入口：置 disposed、abort 在飞请求、停流并通知 /stop。 */
      function dispose () {
        var wasStarted = state.startedOnce
        var tab = state.tabToken
        state.disposed = true
        state.userAbort = true
        state.startInFlight = false
        state.refreshPending = false
        state.releasePending = false
        state.rotatePending = false
        cleanupLocal()
        state.tabToken = null
        state.startedOnce = false
        patch({ phase: 'stopped', message: '' })
        if (!wasStarted || typeof fetchImpl !== 'function') return Promise.resolve()
        var suffix = tab ? '?tab=' + encodeURIComponent(tab) : ''
        return fetchImpl(ROUTE_STOP + suffix, { method: 'POST', keepalive: true }).then(function () {}, function () {})
      }

      return {
        capability: capability,
        discover: discover,
        start: start,
        stop: stop,
        refresh: refresh,
        sendInput: sendInput,
        sendMove: sendMove,
        moveBackToVirtual: moveBackToVirtual,
        rotateDisplay: rotateDisplay,
        /** 面板任务面的可见结论：走 patch/onChange 同一管线（不改播放状态）。 */
        note: function (text) {
          if (state.disposed) return state
          return patch({ taskNote: text })
        },
        releaseDisplay: releaseDisplay,
        loadTasks: loadTasks,
        dispose: dispose,
        state: function () { return state },
      }
    }

    /**
     * 流面板正文：MSE 能力探测 → 路由发现 → 「开始预览」→ <video> 播放 + 输入层
     * +  任务动作（三动作按钮；任务列表 UI 已删，loadTasks 只服务
     * "移到主屏"的内部取数）。
     *
     * 布局：头部与预览固定在滚动容器顶部（root flex + 下方独立
     * overflow-y:auto 的滚动区），只有动作/诊断内容滚动；预览宽高比按会话 metadata
     * 的源尺寸自适应（previewAspectRatio），不写死 16/9。
     *
     * 生命周期：keepMounted:false 下隐藏即卸载，unmount 是唯一的收口 —— 控制器
     * stop() 释放本端资源（abort 读取/撤 objectURL/清 video）并通知 Node 半
     * /stop（它再转发宿主 /stop）；服务端与宿主 watchdog 继续兜底。
     * 输入：pointer 监听挂**原生事件**（覆盖层 touchAction:none + preventDefault
     * 阻止页面滚动），不走 React 合成事件 props —— 面板元素树上除了按钮的 onClick
     * 仍然没有任何 Touch/Pointer/Mouse 类 handler；请求面只有 /mobile-pilot/stream*、
     * /mobile-pilot/input 与 /mobile-pilot/tasks|move|release。
     */
    function VirtualDisplayBody () {
      var view = react.useState({
        phase: 'probing', message: '', diag: '', inputNote: '', canInput: false,
        // 任务列表不再渲染（loadTasks 只服务"移到主屏"的内部取数），
        // 面板只留可见结论 taskNote 与释放确认。
        taskNote: '',
        // 预览自适应：/start 回包的源尺寸（aspect-ratio 动态计算用）。
        metadata: null,
        confirmRelease: false,
        // 诊断详情默认折叠；溢出菜单已删（入口唯一=底部卡）。
        // 旋转进行中（防连点）。
        diagOpen: false, releasePending: false, rotatePending: false, refreshPending: false,
      })
      var snapshot = view[0]
      var setSnapshot = view[1]
      var videoHolder = react.useRef(null)
      var overlayHolder = react.useRef(null)
      var controllerHolder = react.useRef(null)
      // 精修：释放模态的焦点管理 —— 取消/释放两键（Tab 循环）、打开即聚焦取消、
      // 关闭后焦点回到触发按钮（触屏/键盘用户不丢焦点位置）。
      var cancelBtnHolder = react.useRef(null)
      var confirmBtnHolder = react.useRef(null)
      var releaseTriggerHolder = react.useRef(null)

      react.useEffect(function () {
        var controller = createStreamPanelController({
          fetch: (typeof fetch === 'function') ? fetch : null,
          MediaSource: (typeof MediaSource !== 'undefined') ? MediaSource : null,
          urlCreate: (typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function')
            ? URL.createObjectURL.bind(URL) : null,
          urlRevoke: (typeof URL !== 'undefined' && typeof URL.revokeObjectURL === 'function')
            ? URL.revokeObjectURL.bind(URL) : null,
          getVideo: function () { return videoHolder.current },
          onChange: function (next) {
            // 函数式更新：面板自己的 UI 态（释放确认卡 confirmRelease、诊断折叠
            // diagOpen）与控制器读数各写各的字段 —— 控制器的
            // onChange 不得覆盖它们（并发到达的读数会晚于用户的点击，覆盖式写会把
            // 展开中的确认卡悄悄收走）。
            setSnapshot(function (prev) {
              return Object.assign({}, prev, {
                phase: next.phase, message: next.message, diag: next.diag,
                inputNote: next.inputNote || '',
                // 未 playing / 无 tabToken 时输入整体禁用（会话不在，输入必然失败）。
                canInput: next.phase === 'playing' && !!next.tabToken,
                // 任务列表不再进快照（无 UI）；可见结论照抄。
                taskNote: next.taskNote || '',
                // 预览自适应：源尺寸进快照，aspect-ratio 随之更新。
                metadata: (next.metadata && typeof next.metadata === 'object') ? next.metadata : null,
                // 精修 4：技术细节（HTTP 状态等）只进诊断折叠。
                httpDetail: next.httpDetail || '',
                // 旋转进行中（面板防连点）。
                rotatePending: !!next.rotatePending,
                // T1：刷新预览进行中透传（按钮 disabled/文案读它）。
                refreshPending: !!next.refreshPending,
                // 释放进行中透传（按钮 disabled/文案读它）。
                releasePending: !!next.releasePending,
                // 真实失败记号（空态红/中性判据用；初始引导不上红）。
                failed: !!next.failed,
              })
            })
          },
        })
        controllerHolder.current = controller
        // 进入页面即尝试开流，不再要求用户先点「开始预览」。先探测路由，再延迟 300ms
        // 且只在还没人发起过 start 时动作：用户/测试抢先点了按钮，就交给那次 start。
        // 上一届 dispose 的 /stop 可能还在排水，自动 start 会短时回 502 already running。
        var autoTimer = null
        controller.discover().then(function () {
          if (controller.state().disposed || controller.state().startedOnce ||
              controller.state().protectedBlocked) return
          autoTimer = setTimeout(function () {
            if (controller.state().disposed || controller.state().startedOnce ||
                controller.state().protectedBlocked) return
            controller.start().then(function () {
              var after = controller.state()
              if (!after.disposed && !after.protectedBlocked && after.phase !== 'playing' &&
                  (after.lastFailureStatus === 502 ||
                    /already running|still draining|HTTP 502|502/.test(after.message || ''))) {
                setTimeout(function () {
                  if (!controller.state().disposed && !controller.state().protectedBlocked) controller.start()
                }, 700)
              }
            })
          }, 300)
        })
        return function () {
          clearTimeout(autoTimer)
          // unmount/隐藏（keepMounted:false）= dispose：abort 在飞请求 + 停流 + 通知 /stop。
          controllerHolder.current = null
          controller.dispose()
        }
      }, [])

      // 输入层：pointerdown 记起点（带 pointerId），pointerup 归一化终点并按
      // classifyGesture 分 tap/swipe；会话门禁在这里先吞一道（sendInput/Node/宿主
      // 再各自把一道），canInput=false 时多指/慢点按都不产生请求。
      react.useEffect(function () {
        var el = overlayHolder.current
        if (!el || typeof el.addEventListener !== 'function') return undefined
        var down = null
        // 归一化用 **video 的 rect 与内容尺寸**（扣信箱黑边），video 缺席退化用覆盖层。
        function pointOf (event) {
          var video = videoHolder.current
          var rect = (video && typeof video.getBoundingClientRect === 'function'
            ? video : el).getBoundingClientRect()
          return normalizePoint(rect, event.clientX, event.clientY,
            video ? video.videoWidth : 0, video ? video.videoHeight : 0)
        }
        // 未 playing / 无 tabToken：输入整体吞掉（不只禁按钮）。
        function inputEnabled () {
          var controller = controllerHolder.current
          if (!controller) return false
          var state = controller.state()
          return !state.disposed && state.phase === 'playing' && !!state.tabToken
        }
        function onDown (event) {
          if (down || !inputEnabled()) return
          if (event.pointerType === 'mouse' && event.button !== 0) return
          try {
            if (typeof el.setPointerCapture === 'function') el.setPointerCapture(event.pointerId)
          } catch (e0) { /* 捕获不了不影响判据：move/up 仍落在覆盖层上 */ }
          var point = pointOf(event)
          down = { pointerId: event.pointerId, x: point.x, y: point.y, t: Date.now() }
          if (typeof event.preventDefault === 'function') event.preventDefault()
        }
        function onMove (event) {
          if (!down || event.pointerId !== down.pointerId) return
          if (typeof event.preventDefault === 'function') event.preventDefault()
        }
        function onUp (event) {
          if (!down || event.pointerId !== down.pointerId) return
          var start = down
          down = null
          var end = pointOf(event)
          end.t = Date.now()
          var gesture = classifyGesture(start, end)
          var payload = { action: gesture.kind }
          var keys = gesture.kind === 'tap' ? ['x', 'y'] : ['x1', 'y1', 'x2', 'y2', 'durationMs']
          for (var i = 0; i < keys.length; i += 1) payload[keys[i]] = gesture[keys[i]]
          var controller = controllerHolder.current
          if (controller) controller.sendInput(payload)
        }
        // pointercancel/lostpointercapture：系统打断（来电、手势导航）时废掉半截手势，
        // 不把卡住的 down 留给下一次。
        function onCancel (event) {
          if (down && event.pointerId === down.pointerId) down = null
        }
        el.addEventListener('pointerdown', onDown)
        el.addEventListener('pointermove', onMove)
        el.addEventListener('pointerup', onUp)
        el.addEventListener('pointercancel', onCancel)
        el.addEventListener('lostpointercapture', onCancel)
        return function () {
          el.removeEventListener('pointerdown', onDown)
          el.removeEventListener('pointermove', onMove)
          el.removeEventListener('pointerup', onUp)
          el.removeEventListener('pointercancel', onCancel)
          el.removeEventListener('lostpointercapture', onCancel)
        }
      }, [])

      function onStart () {
        // 挂给主按钮的入口；playing 时它是"停止预览"。重新 start 由控制器按 phase
        // 门禁（非 ready/stopped 不动作），停止由 stop 配平（发过 start 就要通知）。
        var controller = controllerHolder.current
        if (!controller) return
        if (controller.state().phase === 'playing') controller.stop()
        else controller.start()
      }

      // 释放模态的焦点副作用：打开 → 聚焦"取消"（破坏性动作的默认落点）；
      // 关闭 → 焦点回到触发按钮。DOM 不可用（测试假体/无环境）时静默跳过。
      react.useEffect(function () {
        if (!snapshot.confirmRelease) {
          var trigger = releaseTriggerHolder.current
          if (trigger && typeof trigger.focus === 'function') {
            try { trigger.focus() } catch (e0) { /* 聚焦失败不影响语义 */ }
            releaseTriggerHolder.current = null
          }
          return undefined
        }
        var cancelBtn = cancelBtnHolder.current
        if (cancelBtn && typeof cancelBtn.focus === 'function') {
          try { cancelBtn.focus() } catch (e1) { /* 聚焦失败不影响语义 */ }
        }
        return undefined
      }, [snapshot.confirmRelease])
      function sendKey (action) {
        var controller = controllerHolder.current
        if (controller) controller.sendInput({ action: action })
      }

      // ──  面板三动作的 UI 入口 ──
      //
      // "移到主屏"语义 = 当前虚拟屏前台的顶层任务搬去主屏：loadTasks 内部刷新后的
      // 第一条就是前台任务（宿主按 dumpsys 顺序给），列表为空/失败时给可读结论不发
      // 请求。任务列表 UI 已删，loadTasks 只服务这一次内部取数。
      // "移回虚拟屏"语义 = 把上一次被"移到主屏"的那条搬回来（sendMove 成功记
      // lastMovedTaskId），没有记录时提示"先移到主屏"。

      /** 点"刷新预览"（动作栏按钮）：只重启流，不动虚拟屏/任务。 */
      function onRefresh () {
        var controller = controllerHolder.current
        if (controller) controller.refresh()
      }

      /** 点"移到主屏"（动作栏按钮）：内部取任务列表第一条（=前台任务）搬去主屏。 */
      function onMoveTopTaskToMain () {
        var controller = controllerHolder.current
        if (!controller) return
        controller.loadTasks().then(function () {
          var after = controller.state()
          var tasks = after.tasks
          if (!tasks || tasks.length === 0) {
            // 失败原因（tasksError）是人话且可重试；空列表是中性提示。
            controller.note(after.tasksError || '虚拟屏上当前没有任务')
            return
          }
          controller.sendMove(tasks[0].taskId, 'main')
        })
      }

      /** 点"移回虚拟屏"（动作栏按钮）：搬回上一次移去主屏的那条。 */
      function onMoveBackToVirtual () {
        var controller = controllerHolder.current
        if (controller) controller.moveBackToVirtual()
      }

      /** 点"旋转"（动作栏按钮）：防连点由 rotatePending 承担；成功后停流复位。 */
      function onRotate () {
        var controller = controllerHolder.current
        if (controller) controller.rotateDisplay()
      }

      // ── 面板自己的 UI 态开关（函数式更新，不覆盖并发到达的控制器读数）──

      /** 释放的二次确认模态（Esc/点遮罩取消；确认后进行中防连点）。打开时记录触发按钮。 */
      function onReleaseRequest (event) {
        // 触发按钮留给关闭后恢复焦点：event 兜不了（合成事件异步），直接读当前活动的
        // 元素；测试假体经 releaseTriggerHolder 注入。
        if (typeof document !== 'undefined' && document.activeElement &&
            typeof document.activeElement.getAttribute === 'function' &&
            document.activeElement.getAttribute('data-mobile-pilot-action') === 'release-virtual') {
          releaseTriggerHolder.current = document.activeElement
        }
        setSnapshot(function (prev) {
          var next = {}
          for (var key in prev) next[key] = prev[key]
          next.confirmRelease = true
          return next
        })
      }
      function onReleaseCancel () {
        setSnapshot(function (prev) {
          if (!prev.confirmRelease && !prev.releasePending) return prev
          var next = {}
          for (var key in prev) next[key] = prev[key]
          next.confirmRelease = false
          // 请求在飞时不要被 Esc/取消解禁；完成回调会按控制器回包复位。
          if (!prev.releasePending) next.releasePending = false
          return next
        })
      }
      function onReleaseConfirmed () {
        // 防连点：release 在飞时再次确认不重复发请求。
        if (snapshot.releasePending) return
        var controller = controllerHolder.current
        if (!controller) return
        setSnapshot(function (prev) {
          var next = {}
          for (var key in prev) next[key] = prev[key]
          next.confirmRelease = false
          next.releasePending = true
          return next
        })
        controller.releaseDisplay()
      }
      /** 诊断折叠卡的开关（唯一入口 = 底部折叠卡，溢出菜单已删）。 */
      function toggleDiag () {
        setSnapshot(function (prev) {
          var next = {}
          for (var key in prev) next[key] = prev[key]
          next.diagOpen = !prev.diagOpen
          return next
        })
      }

      // ── 派生读数（渲染期计算，不改状态）──
      var pill = PHASE_PILLS[snapshot.phase] || { tone: styles.colorPrimary, text: snapshot.phase }
      var playing = snapshot.phase === 'playing'
      // 主按钮语义：playing → 停止预览；starting 期间防连点。
      var startLabel = playing ? '停止预览' : '开始预览'
      var startDisabled = snapshot.phase === 'starting' || snapshot.refreshPending ||
        snapshot.releasePending || snapshot.rotatePending
      // 预览卡三态：空（未 playing 且无错误）/加载（starting）/错误。
      // 错误态 = 用户操作后的真实失败（控制器 failed 记号/释放与旋转失败），
      // 初始的 probing/unavailable 引导走中性空态，不上红。
      // 刷新期间 stop/start 的中间态不是失败，不闪红色错误卡。
      var hasError = !playing && !snapshot.refreshPending && !!snapshot.message && !!snapshot.failed
      // 预览宽高比 —— metadata 源尺寸优先，video 元素内容尺寸兜底；
      // 都没有就不给 aspect-ratio（未起会话时 videoWrap 临时给 minHeight 撑空态）。
      var videoEl = videoHolder.current
      var aspect = previewAspectRatio(
        snapshot.metadata,
        videoEl ? videoEl.videoWidth : 0,
        videoEl ? videoEl.videoHeight : 0)
      var videoStyle = aspect ? Object.assign({}, styles.video, { aspectRatio: aspect }) : styles.video
      var wrapStyle = playing
        ? styles.videoWrap
        : Object.assign({}, styles.videoWrap, { minHeight: EMPTY_PREVIEW_MIN_HEIGHT })
      // Esc 取消：挂面板根。判读走函数式 prev（闭包里的 snapshot 是上一渲染的，
      // 用户按键时模态可能已由并发读数展开），取消本身幂等。
      function onRootKeyDown (event) {
        if (event && event.key === 'Escape') {
          setSnapshot(function (prev) {
            if (!prev.confirmRelease) return prev
            var next = {}
            for (var key in prev) next[key] = prev[key]
            next.confirmRelease = false
            if (!prev.releasePending) next.releasePending = false
            return next
          })
        }
      }

      return react.createElement('div', {
        style: styles.root,
        'data-mobile-pilot-panel': 'virtual-display',
        onKeyDown: onRootKeyDown,
      },
        // 面板自持的样式注入（不依赖宿主样式表）：spinner keyframes、键盘焦点环、
        // 减弱动态偏好下关闭过渡/动画（精修 5）。选择器都带 mp- 前缀防撞。
        react.createElement('style', { key: 'mp-style' },
          '@keyframes mobilePilotSpin{to{transform:rotate(360deg)}}' +
          '[data-mobile-pilot-panel] button:focus-visible{outline:2px solid var(--dsw-alias-brand-primary,#1f6feb);outline-offset:2px}' +
          '@media (prefers-reduced-motion: reduce){' +
          '[data-mobile-pilot-panel] *{animation:none!important;transition:none!important}}'),

        // ── 头部：标题 + 一行副标题 + 状态胶囊（固定区，不随内容滚动）──
        react.createElement('div', { style: styles.header },
          react.createElement('div', { style: styles.headerTexts },
            react.createElement('h3', { style: styles.title }, '虚拟屏'),
            react.createElement('p', { style: styles.subtitle },
              '手机助手 · 实时预览与任务收纳')),
          react.createElement('span', {
            style: Object.assign({}, styles.pill, { color: pill.tone }),
            'data-mobile-pilot-status-pill': snapshot.phase,
            role: 'status',
            'aria-live': 'polite',
          },
            react.createElement('span', { style: Object.assign({}, styles.pillDot, { background: pill.tone }) }),
            pill.text)),

        // ── 预览卡：黑底圆角、空态/加载/错误三态 + <video> + 输入层（固定区）──
        react.createElement('div', { style: wrapStyle },
          react.createElement('video', {
            'data-mobile-pilot-video': 'stream',
            ref: function (el) { videoHolder.current = el },
            style: videoStyle,
            playsInline: true,
            muted: true,
            autoPlay: true,
          }),
          // 透明输入层：盖在 video 上接 pointer；监听在 effect 里挂原生事件。
          // touchAction 恒为 none：预览已固定，手指在视频上滑动不再带动右侧栏滚动。
          react.createElement('div', {
            'data-mobile-pilot-input': 'overlay',
            ref: function (el) { overlayHolder.current = el },
            style: {
              position: styles.inputOverlay.position,
              left: styles.inputOverlay.left, top: styles.inputOverlay.top,
              right: styles.inputOverlay.right, bottom: styles.inputOverlay.bottom,
              touchAction: 'none',
            },
          }),
          !playing
            ? react.createElement('div', {
                style: styles.emptyState,
                'data-mobile-pilot-preview-empty': '1',
                'data-mobile-pilot-preview-error': hasError ? '1' : '0',
                'aria-busy': snapshot.phase === 'starting',
              },
                // 空态不放任何图标（空态不使用大图标），中性底色 +
                // 一句引导；红色只用于用户操作后的真实错误（state.failed 记号）。
                hasError
                  ? react.createElement('p', {
                      style: styles.errorText,
                      'data-mobile-pilot-error-main': '1',
                      role: 'alert',
                      'aria-live': 'polite',
                    },
                      snapshot.message)
                  : react.createElement('p', { style: styles.emptyText },
                      snapshot.refreshPending
                        ? '正在刷新预览…'
                        : (snapshot.phase === 'starting' ? '正在连接虚拟屏…' : '虚拟屏未在预览')),
                react.createElement('p', { style: styles.emptyHint },
                  hasError ? '可在下方诊断详情里查看技术原因' : '点「开始预览」查看手机上的画面'),
                snapshot.phase === 'starting' || snapshot.refreshPending
                  ? react.createElement('span', { style: styles.spinner, 'data-mobile-pilot-spinner': '1' })
                  : null)
            : null),

        // ── 固定区下方的独立滚动区：只有动作/诊断/提示在这里滚 ──
        // （标记名避开 scroll/pointer 等词：静态哨会扫描元素树 props 上的输入类关键词。）
        react.createElement('div', { style: styles.scrollBody, 'data-mobile-pilot-body': '1' },

          // ── 主操作：单一主按钮（开始/停止预览），与任务动作不同级 ──
          button(playing ? 'Secondary' : 'Primary', {
            'data-mobile-pilot-action': 'start-preview',
            onClick: onStart,
            disabled: startDisabled,
            style: styles.btnFull,
          }, startLabel),

          // ── 预览工具：刷新/旋转，两列；刷新只重启流，不动虚拟屏与任务 ──
          react.createElement('div', { style: styles.toolRow, 'data-mobile-pilot-preview-actions': '1' },
            button('Secondary', {
              'data-mobile-pilot-action': 'refresh-preview',
              onClick: onRefresh,
              disabled: !snapshot.canInput || snapshot.refreshPending ||
                snapshot.releasePending || snapshot.rotatePending,
              'aria-busy': !!snapshot.refreshPending,
              style: styles.btnHalf,
            }, snapshot.refreshPending ? '刷新中…' : '刷新预览'),
            button('Secondary', {
              'data-mobile-pilot-action': 'rotate-virtual',
              onClick: onRotate,
              disabled: !snapshot.canInput || snapshot.rotatePending ||
                snapshot.refreshPending || snapshot.releasePending,
              'aria-busy': !!snapshot.rotatePending,
              style: styles.btnHalf,
            }, snapshot.rotatePending ? '正在旋转…' : '旋转')),

          // ── 任务动作：移到主屏 / 移回虚拟屏，两列 ──
          react.createElement('div', { style: styles.toolRow, 'data-mobile-pilot-task-actions': '1' },
            button('Secondary', {
              'data-mobile-pilot-action': 'move-to-main',
              onClick: onMoveTopTaskToMain,
              disabled: !snapshot.canInput || snapshot.refreshPending ||
                snapshot.releasePending || snapshot.rotatePending,
              style: styles.btnHalf,
            }, '移到主屏'),
            button('Secondary', {
              'data-mobile-pilot-action': 'move-to-virtual',
              onClick: onMoveBackToVirtual,
              disabled: !snapshot.canInput || snapshot.refreshPending ||
                snapshot.releasePending || snapshot.rotatePending,
              style: styles.btnHalf,
            }, '移回虚拟屏')),

          // ── 释放：危险色单独一行，放在最后避免误触 ──
          button('Danger', {
            'data-mobile-pilot-action': 'release-virtual',
            onClick: onReleaseRequest,
            disabled: !snapshot.canInput || snapshot.releasePending ||
              snapshot.refreshPending || snapshot.rotatePending,
            'aria-busy': !!snapshot.releasePending,
            style: styles.btnFull,
          }, snapshot.releasePending ? '正在释放…' : '释放虚拟屏'),

          // 可见结论（输入/任务面）：成功清空、失败说明原因，不静默。
          snapshot.inputNote
            ? react.createElement('p', { style: styles.note, 'data-mobile-pilot-input-note': '1' }, snapshot.inputNote)
            : null,
          snapshot.taskNote
            ? react.createElement('p', { style: styles.note, 'data-mobile-pilot-task-note': '1' }, snapshot.taskNote)
            : null,

          // ── 虚拟屏按键：发给虚拟屏的系统键（"桌面"是 HOME 键，不是任务动作）──
          // 改名"虚拟屏桌面"避免与"移到主屏"混淆；与任务动作视觉分组。
          react.createElement('div', { style: styles.card, 'data-mobile-pilot-keys': 'group' },
            react.createElement('div', { style: styles.cardHead },
              react.createElement('h4', { style: styles.cardTitle }, '虚拟屏按键')),
            react.createElement('div', { style: styles.toolRow },
              button('Secondary', {
                'data-mobile-pilot-action': 'input-back',
                onClick: function () { sendKey('back') },
                disabled: !snapshot.canInput,
              }, '返回'),
              button('Secondary', {
                'data-mobile-pilot-action': 'input-home',
                onClick: function () { sendKey('home') },
                disabled: !snapshot.canInput,
              }, '虚拟屏桌面'))),

          // ── 诊断详情：底部独立折叠卡（唯一入口，右上角溢出菜单已删）；
          // 展开后 monospace 小字 + 内部滚动，诊断原文不进主视觉 ──
          react.createElement('div', {
            style: styles.card,
            'data-mobile-pilot-diag': snapshot.diagOpen ? 'open' : 'collapsed',
          },
            react.createElement('div', { style: styles.cardHead },
              react.createElement('h4', { style: styles.cardTitle }, '诊断详情'),
              button('Secondary', {
                'data-mobile-pilot-action': 'toggle-diag',
                onClick: toggleDiag,
                // 折叠卡的展开态与受控区域对读屏可见。
                'aria-expanded': !!snapshot.diagOpen,
                'aria-controls': 'mp-diag-body',
              }, snapshot.diagOpen ? '收起' : '展开')),
            snapshot.diagOpen && snapshot.message
              ? react.createElement('p', { style: styles.hint, 'data-mobile-pilot-diag-message': '1' },
                  snapshot.message)
              : null,
            snapshot.diagOpen && snapshot.httpDetail
              ? react.createElement('p', { style: styles.hint, 'data-mobile-pilot-diag-http': '1' },
                  snapshot.httpDetail)
              : null,
            snapshot.diagOpen
              ? react.createElement('pre', {
                  id: 'mp-diag-body',
                  style: styles.diagBody,
                  'data-mobile-pilot-diag-body': '1',
                }, snapshot.diag || '（暂无诊断读数）')
              : null),

          react.createElement('p', { style: styles.hint },
            '旋转/刷新会中断并重连预览（任务保留）；释放会关闭虚拟屏上的应用，不会自动搬回主屏，' +
            '主屏前台不因释放而切换；关闭面板或切走即停止传输与输入（输入随会话失效）。')),

        // ── 释放模态：面板内卡片（Esc/点遮罩取消；确认后进行中防连点）──

        // ── 释放模态：面板内卡片（Esc/点遮罩取消；确认后进行中防连点）──
        // 可访问性：role=dialog + aria-modal + 标题关联；打开即聚焦"取消"（破坏性
        // 动作的默认落点），Tab 在取消/释放两键间循环，关闭后焦点回到触发按钮。
        snapshot.confirmRelease
          ? react.createElement('div', {
              style: styles.modalMask,
              'data-mobile-pilot-release-confirm': '1',
              role: 'dialog',
              'aria-modal': 'true',
              'aria-labelledby': 'mp-release-title',
              onClick: function (event) {
                // 点遮罩（mask 本体）= 取消；点卡片内部不冒泡到这里。
                if (event && event.target === event.currentTarget) onReleaseCancel()
              },
              onKeyDown: function (event) {
                // Tab 循环：焦点只在取消/释放两键之间（进行中时释放禁用，焦点留在取消）。
                // 焦点目标优先取 ref（React 挂的 DOM），ref 缺席时从 document 查询
                // （测试假体走这条；真实 DOM 上 ref 永远在场）。
                if (!event || event.key !== 'Tab') return
                var focusables = [cancelBtnHolder.current, confirmBtnHolder.current]
                  .filter(function (el) { return el && typeof el.focus === 'function' && !el.disabled })
                if (focusables.length < 2 && typeof document !== 'undefined' &&
                    typeof document.querySelectorAll === 'function') {
                  var cancelEl = document.querySelector(
                    '[data-mobile-pilot-action="release-cancel"]')
                  var confirmEl = document.querySelector(
                    '[data-mobile-pilot-action="release-confirm"]')
                  focusables = [cancelEl, confirmEl]
                    .filter(function (el) { return el && typeof el.focus === 'function' && !el.disabled })
                }
                if (focusables.length < 2) return
                event.preventDefault()
                var index = focusables.indexOf(
                  (typeof document !== 'undefined' && document.activeElement) || null)
                var next = event.shiftKey
                  ? (index <= 0 ? focusables.length - 1 : index - 1)
                  : (index < 0 || index === focusables.length - 1 ? 0 : index + 1)
                focusables[next].focus()
              },
            },
              react.createElement('div', { style: styles.modalCard },
                react.createElement('h4', { id: 'mp-release-title', style: styles.modalTitle }, '释放虚拟屏？'),
                react.createElement('p', { style: styles.paragraph },
                  '会关闭虚拟屏上的应用并关闭虚拟屏，预览会停止；释放不会把应用搬到主屏，' +
                  '主屏前台不因释放而切换。要保留某个应用，请先「移到主屏」。'),
                react.createElement('div', { style: styles.modalActions },
                  button('Secondary', {
                    'data-mobile-pilot-action': 'release-cancel',
                    ref: function (el) { cancelBtnHolder.current = el },
                    onClick: onReleaseCancel,
                  }, '取消'),
                  button('Danger', {
                    'data-mobile-pilot-action': 'release-confirm',
                    ref: function (el) { confirmBtnHolder.current = el },
                    onClick: onReleaseConfirmed,
                    disabled: snapshot.releasePending,
                  }, snapshot.releasePending ? '正在释放…' : '释放'))))
          : null)
    }
    /** 浏览器半边的名字：与包名一致（tab 系统里的实现身份就是它）。 */
    exports.name = '@local/mobile-pilot'
    exports.createSegmentAssembler = createSegmentAssembler
    exports.codecFromInit = codecFromInit
    exports.createStreamPanelController = createStreamPanelController
    exports.normalizePoint = normalizePoint
    exports.classifyGesture = classifyGesture
    exports.previewAspectRatio = previewAspectRatio
    exports.EMPTY_PREVIEW_MIN_HEIGHT = EMPTY_PREVIEW_MIN_HEIGHT

    /**
     * 需要的浏览器服务：tab 类型注册表与席位框架。
     *
     * loader 只在 inject 全部满足时才调 apply —— 旧 runtime 缺这两个服务时本回调
     * 根本不会被调到，客户端半边安静地不激活（入口不出现），宿主半边的工具与 CLI
     * 不受影响。apply 里的缺服务分支只是防御性覆盖：宿主形态异常、或某天服务被
     * 中途摘除时仍能体面退出，不靠它做版本门控。
     */
    exports.inject = ['slots', 'sidebarRightTabs']

    /**
     * 注册 tab 类型（第一：静态脸面）与正文席位（第二）。
     *
     * 两段各挂一个 ctx.effect，且回调**必须返回注册方给出的 disposer**：注册与
     * 创建它的插件同生共死，插件重载/停用时 loader 调 disposer 撤销注册；漏掉
     * 返回值的话旧 id 会一直占着，下次注册 duplicate id 抛错，入口就此消失。
     * effect 路径每段各自 try/catch：右侧栏为增强项，注册失败只报告，不向
     * 加载期冒泡。
     */
    exports.apply = function apply (ctx) {
      if (!ctx) return
      if (react === null) {
        note('浏览器半边未注册：拿不到 react，画不出右侧栏面板；工具与 CLI 不受影响')
        return
      }
      if (!ctx.sidebarRightTabs || typeof ctx.sidebarRightTabs.register !== 'function' ||
          !ctx.slots || typeof ctx.slots.inject !== 'function' || typeof ctx.slots.register !== 'function') {
        note('当前 DSH 没有右侧栏 API（sidebarRightTabs / slots），「虚拟屏」入口不注册；' +
          '工具与 CLI 不受影响。右侧栏需要 0.2.2-rc.1 及以后的 DSH。')
        return
      }

      // 第一：类型声明。title 与 guide 的文案是 thunk，每次使用重读。
      function registerType () {
        return ctx.sidebarRightTabs.register({
          id: TAB_ID,
          kind: TAB_KIND,
          // 隐藏即卸载：面板不驻留，生命周期收口只有 unmount 一处。
          keepMounted: false,
          title: function () { return '虚拟屏' },
          guide: [{
            id: 'virtual-display',
            order: 10,
            title: function () { return '虚拟屏' },
            description: function () { return '手机助手虚拟屏面板' },
          }],
        })
      }

      // 第二：正文席位。key 必须是 definition 的 id，席位按它派发正文。
      function registerBody () {
        return ctx.slots.inject(SLOT_PANE_TAB, function () {
          return ctx.slots.register({ name: SLOT_PANE_TAB, key: TAB_ID }, VirtualDisplayBody)
        })
      }

      if (typeof ctx.effect === 'function') {
        try {
          ctx.effect(registerType, 'mobile-pilot: virtual-display type')
        } catch (error) {
          note('右侧栏类型注册失败：' + ((error && error.message) || error))
        }
        try {
          ctx.effect(registerBody, 'mobile-pilot: virtual-display tab body')
        } catch (error) {
          note('右侧栏正文席位注册失败：' + ((error && error.message) || error))
        }
        return
      }
      // effect 服务不在（异常的宿主形态）时直接注册并靠它自己返回的 disposer 兜底；
      // 任一段失败都只报告不抛出 —— 右侧栏为增强项，不能拖垮加载期。
      try {
        registerType()
        registerBody()
      } catch (error) {
        note('右侧栏注册失败：' + ((error && error.message) || error))
      }
    }

    return module.exports
  },
})
