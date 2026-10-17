/**
 * Browser half of @local/dsh-mobile-adapt.
 *
 * IMPORTANT — dsh uses CSS Modules: at runtime every class is HASH-PREFIXED
 * (e.g. `FDk7aW_centerCol`, `TLRkAa_trailing`, `vUZ1hq_trigger`). A bare
 * `.centerCol` / `.trailing` selector NEVER matches. We therefore target via
 * substring attribute selectors `[class*="centerCol"]`, structural selectors
 * (`> nav`, `:scope > div`), and stable `data-*` / `id` hooks. This survives
 * dsh rebuilds (the hash prefix changes but the substring stays).
 */
window.__ModuleLoader__.load({
  id: '@local/dsh-mobile-adapt',
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    exports.name = 'mobile-adapt';
    exports.inject = ['layout', 'slots'];

    // =====================================================================
    // 加载诊断面包屑。
    // apply() 是多段线性执行、无隔离：任何一段抛异常，其后所有段
    // （含汉堡、键盘抑制、设置面板）都会不执行，表现为"整插件消失、刷新恢复"。
    // 面包屑记录最近 5 条启动事件/异常，无需 devtools 即可事后定位。
    // =====================================================================
    function bootLog(msg) {
      try {
        var arr = JSON.parse(localStorage.getItem('dsh-mobile-boot-log') || '[]');
        arr.unshift(String(msg) + ' @' + Date.now() + ' rs=' + document.readyState);
        localStorage.setItem('dsh-mobile-boot-log', JSON.stringify(arr.slice(0, 5)));
      } catch (e) { /* 诊断绝不反噬功能 */ }
    }

    // =====================================================================
    // 0) WebView 兼容补丁：非标准 scheme 的 URL host 解析
    // =====================================================================
    // 背景：dsh 的 `dsh-client-resources` 用
    //   `protocolOf(address)` 从资源地址取协议名，实现依赖 `URL.hostname`：
    //     const u = new URL('dsh-resource://file/session/<id>/<path>');
    //     if (u.protocol !== 'dsh-resource:') return undefined;
    //     return u.hostname === '' ? undefined : u.hostname.toLowerCase();
    //   旧内核（如 WebView 126）对**非特殊 scheme** 不解析 authority：
    //     hostname === ''            pathname === '//file/session/…'
    //   → 取不到 'file' → provider 匹配失败 → 右栏预览显示「文件资源服务不可用」。
    //   新内核（WebView 138）按标准解析：hostname === 'file'，一切正常。
    //
    // 做法：**运行期修正 URL 解析行为**，不改 dsh 源码（与硬链接垫片同一哲学）。
    //   仅当探测确认内核有此缺陷时才安装；修正范围严格限定为
    //   「hostname 为空**且** pathname 以 `//` 开头」的地址——即缺陷表现本身。
    //   特殊 scheme（http/https/…）与无 authority 的 scheme（mailto: 等）原样放行。
    // 整个垫片包置于 try/catch 内：抛异常会杀死工厂求值 = 全插件消失。
    try { (function installUrlHostCompat() {
      var NativeURL = window.URL;
      if (typeof NativeURL !== 'function') return;

      // 特性探测：内核若能正确解析，则**完全不介入**（新内核零改动）。
      try {
        if (new NativeURL('dsh-resource://file/x').hostname === 'file') return;
      } catch (e) {
        return; // 连构造都抛异常的内核不在此补丁的职责范围
      }

      /**
       * 把「authority 被并入 pathname」的解析结果还原为标准形态。
       * 返回修正后的 URL 对象；地址不符合该缺陷特征时原样返回。
       *
       * 注意：**必须从传入实例读 pathname**（而不是内部再 new 一个），
       * 否则读到的是底层原生结果而非该内核对外暴露的视图——
       * 缺陷恰恰体现在「对外视图」上，从内部读会让修复逻辑完全不生效。
       */
      function repair(orig) {
        var pn;
        try { pn = orig.pathname; } catch (e) { return orig; }
        if (typeof pn !== 'string' || pn.slice(0, 2) !== '//') return orig;
        var rest = pn.slice(2);
        var slash = rest.indexOf('/');
        var authority = slash < 0 ? rest : rest.slice(0, slash);
        if (authority === '') return orig;
        var fixedPath = slash < 0 ? '/' : rest.slice(slash);
        // 用 Proxy 覆盖三个受影响的只读属性，其余（协议、查询、方法…）原样透传。
        // 函数属性需绑定到原生对象，否则 brand check 会失败（URL 方法要求 this 是真 URL）。
        var overrides = {
          hostname: authority,
          host: (orig.port ? authority + ':' + orig.port : authority),
          pathname: fixedPath,
        };
        return new Proxy(orig, {
          get: function (t, p) {
            if (Object.prototype.hasOwnProperty.call(overrides, p)) return overrides[p];
            var v = t[p];
            return typeof v === 'function' ? v.bind(t) : v;
          },
        });
      }

      function CompatURL(input, base) {
        var orig = arguments.length > 1 ? new NativeURL(input, base) : new NativeURL(input);
        return repair(orig);
      }
      // 静态成员（createObjectURL / revokeObjectURL / canParse / parse …）原样继承。
      Object.getOwnPropertyNames(NativeURL).forEach(function (k) {
        if (k === 'prototype' || k === 'length' || k === 'name') return;
        try { CompatURL[k] = NativeURL[k]; } catch (e) { /* 只读属性忽略 */ }
      });
      CompatURL.prototype = NativeURL.prototype; // 保证 instanceof 语义不变
      window.URL = CompatURL;
      try {
        console.warn('[DSHBox] URL host compat shim installed (legacy WebView detected)');
      } catch (e) { /* noop */ }
    })(); } catch (e) { bootLog('url-shim:' + (e && e.message || e)); }

    // =====================================================================
    // 0b) 键盘抑制：只在用户点输入区时才弹软键盘（多路分层 + 观测自适应）
    // =====================================================================
    // 现象：新建对话、从侧栏切会话、点「+」、点模型选择，都会误弹软键盘。
    // 根因：dsh 客户端是桌面优先（只有 data-platform="darwin" 一个平台特例，没有
    //   touch/mobile 分支），「焦点永远留在 composer」这套交互在 WebView 上被原样
    //   执行，而这里可编辑元素获得焦点就等于弹起 IME。四个场景分别来自：会话挂载/
    //   切换时 shell 主动聚焦 composer、点「+」时按钮回焦 composer、命令候选列表与
    //   模型菜单自动聚焦各自的搜索框/行。
    //
    // 处置：不改 dsh 源码，运行期按「多路分层 + 观测自适应」处理；层号按设备缓存在
    //   localStorage，只升不降（升降会抖动，降级会让已修好的场景复发）：
    //     L0 拦聚焦 —— 拦掉非用户手势发起的 focus()。全版本可用、无副作用，默认层。
    //     L1 加闸门 —— 焦点仍落到目标上时给它打 inputmode="none"，覆盖不经 focus() 的
    //        路径（如选区重建的隐式聚焦）。开闸必须走「用户按下时先摘；若已在焦点上
    //        则 blur→focus 让内核重评估」，否则用户点不出键盘（真机实测过）。
    //     L2 收焦点 —— 闸门也拦不住时直接收掉焦点（全版本可用）。
    //   升级依据是**观测到的结果**而非猜内核能力：本 App 是 edge-to-edge，键盘由原生
    //   层量出来并显式改 WebView 高度，页面必然收到 resize；视口被压小且焦点仍在
    //   目标上，即判定「不该弹却弹了」，升一级并就地补救。
    //
    // 两条硬约束：
    //   ① 用户点输入区永远放行 —— 原生聚焦不经过这里，闸门也在按下时先摘。
    //   ② 正在输入时不要把它按下去 —— dsh 的「+」带 onMouseDown keepFocus（preventDefault
    //      后回焦 composer）；若把这次回焦也拦掉，编辑器会真的失焦、键盘收起、原生层
    //      随之改 WebView 高度 → 页面跳动 → 手指抬起时按钮已移位 → click 丢失。
    //      故「手势落在 composer 卡片内、且手势前 composer 已在焦点上」一律放行。
    //      另外 dsh 的模型菜单在 drill/选中时会主动把焦点移到菜单行或触发按钮（都在卡片
    //      之外），编辑器因此失焦、键盘落下；对此在卡片内非可编辑控件上按下后，把焦点
    //      要回编辑器（用 Lexical 自己的 focus，光标不跳）。
    try { (function installKeyboardSuppression() {
      var nativeFocus = HTMLElement.prototype.focus;
      if (typeof nativeFocus !== 'function' || nativeFocus.__dshboxKbGuard === true) return;

      var TARGET_SEL = '[data-composer-input], input[role="searchbox"], [role="searchbox"], [data-menu-material] input';
      var GESTURE_GRACE_MS = 1000;
      var OBSERVE_WINDOW_MS = 500;
      var KB_MIN_SHRINK_PX = 120;
      var LEVEL_KEY = 'dsh-mobile-kb-level';
      var MAX_LEVEL = 2;

      var level = 0;
      try {
        var stored = parseInt(localStorage.getItem(LEVEL_KEY), 10);
        if (stored > 0) level = stored > MAX_LEVEL ? MAX_LEVEL : stored;
      } catch (e) { /* 读不到按 0 走，仅失去跨刷新记忆 */ }

      function adaptOn() {
        try {
          if (localStorage.getItem('dsh-mobile-enabled') === '0') return false;
          return localStorage.getItem('dsh-mobile-kb-off') !== '1';
        } catch (e) { return true; }
      }
      function isTarget(el) {
        if (!el || typeof el.getAttribute !== 'function') return false;
        if (el.getAttribute('data-composer-input') !== null) return true;
        if (typeof el.closest !== 'function') return false;
        try { return el.closest(TARGET_SEL) !== null; } catch (e) { return false; }
      }
      /** 该元素本身是不是「用户要输入的地方」——是则绝不抢它的焦点。 */
      function isEditableLike(el) {
        if (!el || typeof el.tagName !== 'string') return false;
        if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT') return true;
        if (typeof el.getAttribute !== 'function') return false;
        if (el.getAttribute('contenteditable') === 'true') return true;
        var role = el.getAttribute('role');
        return role === 'textbox' || role === 'searchbox';
      }

      // 手势记录：Pointer Events 是 Chrome 55+，老内核只认 touchstart/mousedown，三个都挂。
      var gestureEl = null;
      var gestureAt = 0;
      var composerFocusedAtGesture = false;
      // 手势是否落在弹出面板（data-menu-material）内 —— 用来区分「在菜单里点选/下钻」
      // 与「点菜单外收起」：只有前者才在面板关闭后把焦点交回编辑器。
      var gestureInMenu = false;
      function markGesture(e) {
        var target = e.target;
        // 空白区（body/html）不算「点在控件上」：它的 contains 会命中一切元素，
        // 等于给程序化聚焦全开绿灯。
        if (!target || target === document.body || target === document.documentElement) {
          gestureEl = null;
          gestureAt = 0;
          composerFocusedAtGesture = false;
          gestureInMenu = false;
          return;
        }
        gestureEl = target;
        gestureAt = Date.now();
        composerFocusedAtGesture = isTarget(document.activeElement);
        gestureInMenu = insideMenuSurface(target);
      }
      document.addEventListener('pointerdown', markGesture, true);
      document.addEventListener('touchstart', markGesture, true);
      document.addEventListener('mousedown', markGesture, true);

      function insideComposerCard(el) {
        return typeof el.closest === 'function' && el.closest('[data-composer-card]') !== null;
      }
      /** 是否落在某个弹出面板（primitives MenuSurface）内 —— 菜单行挂在 portal 里，不在卡片内。 */
      function insideMenuSurface(el) {
        return typeof el.closest === 'function' && el.closest('[data-menu-material]') !== null;
      }
      function fromUserGesture(el) {
        if (gestureEl === null || Date.now() - gestureAt > GESTURE_GRACE_MS) return false;
        if (el === gestureEl) return true;
        if (typeof el.contains === 'function' && el.contains(gestureEl)) return true;
        if (typeof el.closest === 'function' && typeof gestureEl.closest === 'function') {
          var panel = el.closest('[data-menu-material]');
          if (panel !== null && panel === gestureEl.closest('[data-menu-material]')) return true;
        }
        // 手势落在 composer 卡片内、且手势前 composer 已在焦点上：保住已有焦点，
        // 键盘不会因失焦而收起，页面也不会因 WebView 高度变化而跳动。
        if (composerFocusedAtGesture && insideComposerCard(el) && insideComposerCard(gestureEl)) return true;
        return false;
      }

      // ── L1 闸门 ──
      function setGate(el) {
        try { el.setAttribute('inputmode', 'none'); } catch (e) { /* noop */ }
      }
      function clearGate(el) {
        try {
          if (typeof el.hasAttribute !== 'function' || !el.hasAttribute('inputmode')) return;
          el.removeAttribute('inputmode');
          if (document.activeElement === el) {
            el.blur();
            nativeFocus.call(el, { preventScroll: true });
          }
        } catch (e) { /* noop */ }
      }

      // ── 观测：聚焦没被拦住时，看键盘有没有真的弹出来 ──
      var observeEl = null;
      var observeAt = 0;
      var observeHeight = 0;
      function viewportHeight() {
        var root = document.documentElement;
        return (root && root.clientHeight) || window.innerHeight || 0;
      }
      function armObserve(el) {
        observeEl = el;
        observeAt = Date.now();
        observeHeight = viewportHeight();
      }
      function escalate() {
        if (level >= MAX_LEVEL) return;
        level += 1;
        try { localStorage.setItem(LEVEL_KEY, String(level)); } catch (e) { /* noop */ }
        bootLog('kb-escalate:' + level);
      }
      function onViewportChange() {
        if (observeEl === null) return;
        if (Date.now() - observeAt > OBSERVE_WINDOW_MS || document.activeElement !== observeEl) {
          observeEl = null;
          return;
        }
        if (observeHeight - viewportHeight() < KB_MIN_SHRINK_PX) return;
        escalate();
        try { observeEl.blur(); } catch (e) { /* 就地补救已弹出的键盘 */ }
        observeEl = null;
      }
      window.addEventListener('resize', onViewportChange);

      // 我们自己发起的回焦要放行（见下面 restoreEditorFocus）：给一个短窗口，
      // 覆盖 Lexical focus() 内部延迟到微任务的那次 root.focus()。
      var allowFocusUntil = 0;

      function guardedFocus(opts) {
        try {
          if (Date.now() > allowFocusUntil && adaptOn() && document.activeElement !== this) {
            // ① 拦掉非用户手势发起的程序化聚焦（会话挂载/切换、菜单搜索框等）。
            if (isTarget(this) && !fromUserGesture(this)) return;
            // ② 手势之后（含点菜单行）不许把焦点从编辑器上抢走。dsh 的模型菜单在 show()
            //    里就 triggerRef.focus()，drill 与选中时还会聚焦菜单行/触发按钮；焦点一旦
            //    离开编辑器：键盘落下 → 原生层改 WebView 高度 → 页面跳动；模型菜单自己的
            //    onBlur 也会判定焦点离开而 close()。
            //    闸口与目标都要放宽：菜单行挂在 portal 里、位于 composer 卡片之外，只认
            //    「卡片内」会漏掉 drill / 点菜单行 / 选中这三条。故两侧都取「卡片内 或
            //    同一弹出面板（data-menu-material）内」；非可编辑元素才拦，搜索框不受影响。
            //    菜单仍能正常关闭：另有 document mousedown 的外部关闭路径（model-selection
            //    的 closeOutside）与选中、再点 trigger 三条，均不依赖菜单内焦点。
            //    手势加时效上限：过期后不再拦，避免把菜单的悬停/聚焦高亮一并冻住。
            if (composerFocusedAtGesture && Date.now() - gestureAt <= GESTURE_GRACE_MS &&
                !isEditableLike(this) &&
                (insideComposerCard(this) || insideMenuSurface(this)) &&
                (insideComposerCard(gestureEl) || insideMenuSurface(gestureEl))) return;
          }
        } catch (e) { bootLog('kb-focus-guard:' + (e && e.message || e)); }
        return nativeFocus.call(this, opts);
      }
      guardedFocus.__dshboxKbGuard = true;
      HTMLElement.prototype.focus = guardedFocus;

      // ── 面板关闭后把焦点交回编辑器 ──
      // dsh 的模型菜单在选中/关闭时会把焦点交给触发按钮（close(true) 的 queueMicrotask），
      // 或在有搜索框时因搜索框随面板卸载而让焦点落到 body —— 两种都使编辑器失焦、键盘
      // 落下、WebView 高度变化。这里在**弹出面板从「有」变「无」的那一刻**（面板已消失，
      // 不可能再触发模型菜单的 onBlur→close）把焦点交回编辑器。
      // 必须晚于 dsh 自己的收尾（微任务聚焦触发按钮），故排进宏任务。
      var RESTORE_WINDOW_MS = 5000; // 从手势到面板关闭之间允许的时长（覆盖慢 RPC 的选中）
      var menuSurfaceCount = 0;
      function countMenuSurfaces() {
        try { return document.querySelectorAll('[data-menu-material]').length; } catch (e) { return 0; }
      }
      function restoreEditorFocus() {
        try {
          var host = document.querySelector('[data-composer-input]');
          if (!host || document.activeElement === host) return;
          // 落点本身是可编辑的（用户要在那里输入）→ 不抢。
          if (isEditableLike(document.activeElement)) return;
          allowFocusUntil = Date.now() + 600;
          var editor = host.__lexicalEditor;
          if (editor && typeof editor.focus === 'function') editor.focus();
          else nativeFocus.call(host, { preventScroll: true });
        } catch (err) { /* noop */ }
      }
      function onMenusMaybeClosed(records) {
        // 早退：本批没有任何节点增删与弹出面板相关时，不做全文档查询。
        // （面板本体或其 portal 容器都带 [data-menu-material]，故只需看本批增删的节点。）
        try {
          var relevant = false;
          for (var i = 0; i < records.length && !relevant; i++) {
            var added = records[i].addedNodes;
            var removed = records[i].removedNodes;
            var j;
            for (j = 0; j < added.length; j++) {
              if (added[j].nodeType === 1 &&
                  (added[j].matches && added[j].matches('[data-menu-material]') ||
                   (added[j].querySelector && added[j].querySelector('[data-menu-material]') !== null))) {
                relevant = true;
                break;
              }
            }
            for (j = 0; j < removed.length && !relevant; j++) {
              if (removed[j].nodeType === 1 && removed[j].matches &&
                  (removed[j].matches('[data-menu-material]') ||
                   (removed[j].querySelector && removed[j].querySelector('[data-menu-material]') !== null))) {
                relevant = true;
              }
            }
          }
          if (!relevant) return;
        } catch (e) { /* 判断失败就继续按计数处理 */ }
        var now = countMenuSurfaces();
        if (menuSurfaceCount > 0 && now === 0 &&
            adaptOn() && composerFocusedAtGesture && gestureInMenu &&
            Date.now() - gestureAt <= RESTORE_WINDOW_MS) {
          // 只在「在菜单里点选/下钻之后面板关闭」时回焦；点菜单外收起不回焦
          // （否则用户想收起键盘却被重新叫起）。
          setTimeout(restoreEditorFocus, 0);
        }
        menuSurfaceCount = now;
      }
      try {
        new MutationObserver(onMenusMaybeClosed).observe(document.documentElement, {
          childList: true, subtree: true,
        });
      } catch (e) { /* 观察器装不上时退化为不回焦（键盘可能落，但不影响其它行为） */ }
      menuSurfaceCount = countMenuSurfaces();

      document.addEventListener('focusin', function (e) {
        try {
          // 我们自己发起的回焦（restoreEditorFocus）要放行 —— 否则会被这里当场打闸/收焦点，
          // 修复在升层设备（L1/L2）上直接失效。放行只针对 composer 宿主本身、且只在
          // 放行窗口内，不放松其它元素的闸门。
          if (Date.now() <= allowFocusUntil &&
              e.target === document.querySelector('[data-composer-input]')) return;
          var el = e.target;
          if (!adaptOn() || !isTarget(el) || fromUserGesture(el)) return;
          if (level >= 1) setGate(el);
          armObserve(el);
          if (level >= 2) el.blur();
        } catch (err) { bootLog('kb-focusin-fail:' + (err && err.message || err)); }
      }, true);

      document.addEventListener('pointerdown', function (e) {
        try {
          if (!e.target || typeof e.target.closest !== 'function') return;
          var el = e.target.closest(TARGET_SEL);
          if (el !== null) clearGate(el);
        } catch (err) { /* noop */ }
      }, true);

      // 注：这里**不做事后「抢回焦点」**。dsh 的模型菜单 root 挂有 onBlur —— 一旦判定焦点
      // 离开菜单就 close()；事后回焦会把刚展开的面板关掉。正确做法是在 guardedFocus 里
      // 拦住那次夺焦（见上面 ②），让焦点压根不离开编辑器。

      bootLog('kb-suppress:level' + level);
    })(); } catch (e) { bootLog('kb-suppress-fail:' + (e && e.message || e)); }

    // =====================================================================
    // 0c) 「+」改成真正的开合（读面板 DOM 锚 + 走 dsh 自己的关闭路径）
    // =====================================================================
    // 现象：点「+」弹出命令候选列表后，再点「+」收不回去；点过模型选择之后尤其容易失灵，
    //   而页面刚打开、第一次就点「+」则正常。
    // 根因（dsh 客户端源码，两条叠加）：
    //   ① 「+」开出的是 input-trigger 的候选列表 MenuView（根节点带 data-trigger-menu，
    //      关闭即卸载），**不是** commands 的参数面板；而 MenuView 的「点外部即关」守卫
    //      明确豁免 [data-composer-card]（input-trigger 的守卫里就有这一句）。「+」在卡片
    //      内，所以点「+」时没有任何东西会把它关掉。
    //   ② 关闭只能靠 toggle：toggleSource 的关闭分支要求 launcher==="command"，而 track()
    //      在每次光标/草稿更新时第一步就 clearLauncher()。点模型选择会回焦编辑器 → 触发
    //      光标更新 → launcher 被清 → 此后 toggle 只会「再开一次」。这就是「点过模型选择
    //      后必失灵、第一次却正常」的原因。
    // 处置：判据用**关闭即卸载**的面板 DOM 锚（不碰 launcher / aria-expanded），关闭改走
    //   dsh 自己的「点外部即关」——向卡片之外派发一次真实的 pointerdown 事件，
    //   与用户点空白处完全同路，不直接调用 dsh 内部服务、也不重写它的 onClick。
    //   面板本来就没开时一律不介入，退回上游行为。
    try { (function installCommandToggleFix() {
      // 幂等闸：工厂万一被求值两次也不重复挂监听（与 0b 的 __dshboxKbGuard 同式）。
      if (document.__dshboxCmdToggleFix === true) return;
      document.__dshboxCmdToggleFix = true;
      var COMMAND_TRIGGER_SEL = '[data-composer-card] button[aria-haspopup="listbox"]';
      // 候选列表（「+」直接开的那层）：input-trigger 的 MenuView，关闭即卸载。
      var MENU_VIEW_SEL = '[data-trigger-menu]';
      var pressedButton = null;

      function adaptOn() {
        try { return localStorage.getItem('dsh-mobile-enabled') !== '0'; } catch (e) { return true; }
      }
      function commandTrigger(target) {
        if (!target || typeof target.closest !== 'function') return null;
        try { return target.closest(COMMAND_TRIGGER_SEL); } catch (e) { return null; }
      }
      /**
       * 参数面板（选中带选项的命令后才开）是否开着：commands 的 PopupSelectView 是**唯一**
       * 一个「MenuSurface 内既有 [role="listbox"] 又有无 role 文本输入框」的弹层 ——
       * 候选列表 MenuView 有 listbox 但没有输入框；模型菜单的输入框带 role="searchbox"。
       * 两者都在关闭时整棵卸载，故「DOM 里在不在」是可靠的开启判据。
       */
      function paletteViewOpen() {
        try {
          var surfaces = document.querySelectorAll('[data-menu-material]');
          for (var i = 0; i < surfaces.length; i++) {
            if (surfaces[i].querySelector('[role="listbox"]') !== null &&
                surfaces[i].querySelector('input:not([role])') !== null) return true;
          }
        } catch (e) { /* noop */ }
        return false;
      }
      function panelOpen() {
        try { if (document.querySelector(MENU_VIEW_SEL) !== null) return true; } catch (e) { /* noop */ }
        return paletteViewOpen();
      }
      /**
       * 触发 dsh 自己的「点外部即关」：向 composer 卡片之外派发一次 pointerdown。
       * 与用户点空白处同路 —— MenuView 的守卫只豁免卡片内部，落在卡片外即 dismiss。
       */
      function dismissByOutsidePointer() {
        try {
          document.body.dispatchEvent(new MouseEvent('pointerdown', {
            bubbles: true, cancelable: true, view: window,
          }));
        } catch (e) { /* noop */ }
      }

      // 本监听注册于插件加载时，早于面板打开时才挂上的那些关闭监听（同为目标 document、
      // 同为捕获阶段，按注册顺序执行）。
      document.addEventListener('pointerdown', function (e) {
        try {
          pressedButton = null;
          if (!adaptOn()) return;
          var button = commandTrigger(e.target);
          if (button === null) return;
          if (!panelOpen()) return; // 面板没开：交给 dsh 打开
          // 只记住「这一次按下的是哪个按钮」；click 时校验是同一个按钮才动手，
          // 多指、按下后取消等场景不会误吞到别的点击。
          pressedButton = button;
        } catch (err) { bootLog('cmd-toggle-down:' + (err && err.message || err)); }
      }, true);

      document.addEventListener('click', function (e) {
        try {
          var expected = pressedButton;
          pressedButton = null;
          if (expected === null) return;
          if (commandTrigger(e.target) !== expected) return;
          // 拦掉 dsh 的 toggle（launcher 被 track() 清掉后它只会「再开一次」），
          // 改为走 dsh 自己的关闭路径。
          e.preventDefault();
          e.stopPropagation();
          if (typeof e.stopImmediatePropagation === 'function') e.stopImmediatePropagation();
          dismissByOutsidePointer();
        } catch (err) { bootLog('cmd-toggle-click:' + (err && err.message || err)); }
      }, true);
    })(); } catch (e) { bootLog('cmd-toggle-fix:' + (e && e.message || e)); }

    var SAFE_TOP = 'calc(env(safe-area-inset-top, 0px) + 12px)';


    var MOBILE_CSS = [
      '/* === dsh-mobile-adapt: 移动端浮层抽屉 + 设置两步式 === */',
      '@media (max-width: 1024px) {',
      '  /* 全宽内容：侧栏使用脱离文档流的抽屉，侧栏轨道宽度为 0。',
      '     中栏填满剩余宽度，第三条轨道由 DSH 按内容确定宽度。',
      '     右栏面板使用绝对定位，auto 轨道同时允许其他内容申请宽度。',
      '     centerCol 保留在文档流中，',
      '     以便浏览器在键盘弹出时',
      '     自动将输入框滚动到可见区域。 */',
      '  [class*="frame"] { grid-template-columns: 0px minmax(0, 1fr) auto !important; }',
      '  [class*="frame"][data-sidebar-collapsed] [class*="sidebarCol"] {',
      '    display: none !important;',
      '  }',
      '  /* 右侧列（上游 0.1.1 名 detailsCol，0.1.5 起同一模块内改名 rightbarCol；两个类名都匹配）。',
      '     ⚠️ 不得写成 `display: none !important`：',
      '     dsh 的右栏本身是「零宽网格项」（CSS：min-width:0; position:relative; overflow:visible），',
      '     它的面板贴着该列右缘悬挂到中栏之上——列宽为 0 是**设计如此**。',
      '     把整列 display:none 会连同面板一起干掉：用户点「打开右侧栏」时列被隐藏，',
      '     表现为「按钮点了没反应/面板打不开」。',
      '     正确做法：保留该列参与布局（只钉到第 3 条轨道、不许撑开），让 dsh 自己控制显隐。',
      '     注意必须显式钉 grid-column:3 —— 因为 sidebarCol 被我们改成 position:fixed',
      '     脱离了网格流，自动放置会把右栏排到第 1 条轨道（宽度 0，看不见）。 */',
      '  [class*="detailsCol"], [class*="rightbarCol"] {',
      '    grid-column: 3 !important;',
      '    min-width: 0 !important;',
      '  }',
      '  [class*="centerCol"] {',
      '    grid-column: 2 !important; /* sidebar/details 被隐藏后不占 grid 位，需显式钉在第2列 */',
      '    min-width: 0 !important;',
      '    display: flex !important; flex-direction: column;',
      '  }',
      '  #root { height: 100dvh !important; }',
      '  /* 折叠态：侧栏移出屏幕左侧 */',
      '  [class*="frame"][data-sidebar-collapsed] [class*="sidebarCol"] {',
      '    position: fixed; top: 0; left: 0;',
      '    width: 100%; height: 100dvh;',
      '    transform: translateX(-100%);',
      '    transition: transform .25s var(--ds-ease-in-out, ease);',
      '    z-index: 1000; box-shadow: none;',
      '    overflow-y: auto; overscroll-behavior: contain;',
      '  }',
      '  /* 展开态：侧栏全屏抽屉滑入 */',
      '  [class*="frame"]:not([data-sidebar-collapsed]) [class*="sidebarCol"] {',
      '    position: fixed; top: 0; left: 0;',
      '    width: 100%; height: 100dvh;',
      '    transform: translateX(0);',
      '    transition: transform .25s var(--ds-ease-in-out, ease);',
      '    z-index: 1000;',
      '    box-shadow: 2px 0 24px rgba(0, 0, 0, .35);',
      '    overflow-y: auto; overscroll-behavior: contain;',
      '  }',
      '  /* 抽屉内容撑满全宽：SidebarRoot 的根节点自带**内联 width**',
      '     = dsh 算出的侧栏轨道宽（窄屏展开态 sidebarPreference = 280px）。',
      '     内联样式优先于样式表 → 只把外壳 sidebarCol 撑成 100% 的话外壳变宽，',
      '     内容仍停在左侧 280px，右侧留一大片同色空白。',
      '     唯一办法：在**同一个元素**上以 !important 覆盖该内联值。',
      '     选择器只取侧栏列的直接子级/孙级且类名含 root —— CSS Modules 的哈希前缀',
      '     随构建变、`root` 子串不变；限深可避免波及列表行等其它同名元素。',
      '     必须保持 .root 自带的 box-sizing:border-box，否则 width:100% 会把它那',
      '     12px 的左右内边距额外加出去、重现横向溢出。 */',
      '  [class*="frame"]:not([data-sidebar-collapsed]) [class*="sidebarCol"] > [class*="root"],',
      '  [class*="frame"]:not([data-sidebar-collapsed]) [class*="sidebarCol"] > * > [class*="root"] {',
      '    width: 100% !important;',
      '    max-width: none !important;',
      '  }',
      '  /* 触屏不需要拖拽手柄 */',
      '  [class*="handle"] { display: none !important; }',
      '  /* 汉堡按钮：下移 8px 避让「对话」标题，外框 34px，',
      '     背景高透明白 ~35% 不遮挡下方内容；旧内核不支持 color-mix，自动落回上一行实底。 */',
      '  /* 汉堡按钮贴边定位：left 6px */',
      '  #dsh-mobile-menu {',
      '    position: fixed; top: calc(env(safe-area-inset-top, 0px) + 64px); left: 6px; z-index: 1100;',
      '    width: 34px; height: 34px;',
      '    display: flex; align-items: center; justify-content: center;',
      '    font-size: 17px; line-height: 1;',
      '    border: 1px solid var(--dsw-alias-border-l1, rgba(0, 0, 0, .12));',
      '    border-radius: 9px;',
      '    background: var(--dsw-alias-bg-float, #fff);',
      '    background: color-mix(in srgb, var(--dsw-alias-bg-float, #fff) 35%, transparent);',
      '    color: var(--dsw-alias-text-primary, #111);',
      '    cursor: pointer; box-shadow: 0 1px 4px rgba(0, 0, 0, .08);',
      '    padding: 0;',
      '  }',
      '  /* 遮罩 */',
      '  .dsh-mobile-backdrop {',
      '    position: fixed; inset: 0; background: rgba(0, 0, 0, .4);',
      '    z-index: 999; display: none;',
      '  }',
      '  body.dsh-mobile-drawer-open .dsh-mobile-backdrop { display: block; }',
      '',
      '  /* ===== 设置面板：全屏铺满可见视口（dvh 防地址栏撑爆） =====',
      '     仅对带 nav 的真实设置面板生效，不劫持首屏/命令面板 ===== */',
      '  [role="dialog"][aria-modal="true"]:has(> nav) {',
      '    position: fixed !important; inset: 0 !important;',
      '    width: 100% !important; height: 100dvh !important;',
      '    max-width: none !important; max-height: none !important;',
      '    margin: 0 !important;',
      '    border-radius: 0 !important; overflow: hidden !important;',
      '    overscroll-behavior: contain;',
      '    background: var(--dsw-alias-bg-layer-2, #fff);',
      '    display: flex !important;',
      '    z-index: 1100 !important;',
      '  }',
      '',
      '  /* 两步式 · 第一步：导航全屏铺满、靠上、留安全区；内容区隐藏。',
      '     导航列表保持 dsh 原样(左对齐)，不做居中 —— 用户要求的是"内容页"居中 */',
      '  [role="dialog"][aria-modal="true"]:has(> nav) > nav {',
      '    width: 100% !important; flex: none !important;',
      '    height: 100dvh;',
      '    overflow-y: auto !important;',
      '    background: var(--dsw-alias-bg-layer-2, #fff);',
      '    padding-top: ' + SAFE_TOP + ' !important;',
      '    padding-left: max(24px, env(safe-area-inset-left, 0px)) !important;',
      '    padding-right: max(24px, env(safe-area-inset-right, 0px)) !important;',
      '    justify-content: flex-start !important;',
      '  }',
      '  [role="dialog"][aria-modal="true"]:has(> nav) > div {',
      '    display: none !important;',
      '  }',
      '',
      '  /* 两步式 · 第二步：内容全屏覆盖，导航隐藏。',
      '     注意：content 本身不加左右 padding —— dsh 原生 .options 自带 0 24px，',
      '     叠加会导致左边距 48/右边距 24，内容整体偏右(靠右根因)。 */',
      '  body.dsh-settings-content-open [role="dialog"][aria-modal="true"]:has(> nav) > nav {',
      '    display: none !important;',
      '  }',
      '  body.dsh-settings-content-open [role="dialog"][aria-modal="true"]:has(> nav) > div {',
      '    display: flex !important;',
      '    position: fixed !important; inset: 0 !important;',
      '    width: 100% !important; height: 100dvh !important;',
      '    z-index: 20; overflow-y: auto !important;',
      '    overscroll-behavior: contain;',
      '    background: var(--dsw-alias-bg-layer-2, #fff);',
      '    padding-left: 0 !important; padding-right: 0 !important;',
      '  }',
      '  /* 内容页(options 区)：自适应填满视口宽，不限制死；换任何宽度手机都自动适配。',
      '     四层防线杜绝横向滑动：content 禁横向滚动 → options 禁横向滚动 →',
      '     内容块允许收缩(min-width:0,max-width:100%) → html/body 禁横向滚动。',
      '     options 用 nth-child(2) 定位（dsh 源码 content 固定 [header, options]），',
      '     左下角返回键内嵌为 content 列第 3 子节点，无需再为浮层留 120px 避让。 */',
      '  body.dsh-settings-content-open [role="dialog"][aria-modal="true"]:has(> nav) > div {',
      '    overflow-x: hidden !important; overflow-y: auto !important;',
      '  }',
      '  body.dsh-settings-content-open [role="dialog"][aria-modal="true"]:has(> nav) > div > div:nth-child(2) {',
      '    width: 100% !important;',
      '    /* 内容页偏右/被裁的根因：dsh 无全局 border-box 重置，.VOzbGW_options',
      '       自身未声明 box-sizing → content-box；上面 width:100% 只算内容盒，',
      '       原生 0 24px 的左右 padding 额外加出去 → 内容整体右移、右缘越屏 ~24px',
      '       （权限/语言下拉、外观卡、字号值全被裁）。补 border-box 让 100% 含进',
      '       padding → 双侧对称 24px，内容自然左右居中；max-width 为防御性上限。 */',
      '    box-sizing: border-box !important;',
      '    max-width: 100vw !important;',
      '    overflow-x: hidden !important; overflow-y: auto !important;',
      '    padding-bottom: 24px !important;',
      '  }',
      '  body.dsh-settings-content-open [role="dialog"][aria-modal="true"]:has(> nav) > div > div:nth-child(2) > * {',
      '    width: 100% !important;',
      '    max-width: 100% !important;',
      '    min-width: 0 !important;',
      '    margin: 0 !important;',
      '  }',
      '  /* 内容页所有元素一律收缩到容器内(不裁切、不超出右侧) */',
      '  body.dsh-settings-content-open [role="dialog"][aria-modal="true"]:has(> nav) > div > div:nth-child(2) * {',
      '    max-width: 100% !important;',
      '    min-width: 0 !important;',
      '    box-sizing: border-box !important;',
      '  }',
      '  body.dsh-settings-content-open [role="dialog"][aria-modal="true"]:has(> nav) > div > div:nth-child(2) [class*="row"],',
      '  body.dsh-settings-content-open [role="dialog"][aria-modal="true"]:has(> nav) > div > div:nth-child(2) [class*="item"],',
      '  body.dsh-settings-content-open [role="dialog"][aria-modal="true"]:has(> nav) > div > div:nth-child(2) [class*="cell"] {',
      '    width: 100% !important;',
      '    flex-wrap: wrap !important;',
      '  }',
      '  /* 防横向滑动：任何元素不得撑破视口宽 */',
      '  html, body { overflow-x: hidden !important; }',
      '  /* 内容页头部吸顶+安全区，左右留白让叉号不贴边 */',
      '  body.dsh-settings-content-open [role="dialog"][aria-modal="true"]:has(> nav) > div > div:first-child {',
      '    padding-top: ' + SAFE_TOP + ' !important;',
      '    padding-left: 18px !important; padding-right: 18px !important;',
      '    position: sticky; top: 0; z-index: 2;',
      '    background: var(--dsw-alias-bg-layer-2, #fff);',
      '  }',
      '  /* 叉号加大点按区并内移 */',
      '  [role="dialog"][aria-modal="true"]:has(> nav) [data-dsh-close] {',
      '    min-width: 38px !important; min-height: 38px !important;',
      '    margin-right: 6px !important;',
      '  }',
      '  /* 内嵌左下角返回键的 hover 反馈（对齐 dsh navCell 观感） */',
      '  #dsh-mobile-back-inline:hover {',
      '    background: var(--dsw-specific-sidebar-nav-item-hover, rgba(0, 0, 0, .05)) !important;',
      '  }',
      '',
      '  /* 汉堡显隐不放 CSS 隐藏规则：body-has 嵌套选择器与 dialogObs 的内联显隐',
      '     属冗余双机制，且在 :has() 失效的旧内核 WebView 上会永久锁死汉堡',
      '     （内联 flex 输给 !important，表现：切换适配后返回页面汉堡消失、刷新才恢复）。',
      '     隐藏通道只保留 dialogObs 一处，按实时 DOM 计算，不存在失效残留。 */',
      '',
      '  /* 触屏友好：输入框不缩放 */',
      '  textarea, input { font-size: 16px; }',
      '',
      '  /* ===== 主页输入框：模型选择紧贴发送键，菜单弹出不出屏 =====',
      '     结构：.row(tools:加号+权限 | trailing:模型+发送)。',
      '     方案：trailing 内联靠右(不换行)；模型控件宽度自适应、紧贴发送键左侧；',
      '     下拉菜单允许纵向滚动(overflow:hidden 曾导致内容被截/屏外)。 */',
      '  [data-composer-card] [class*="row"] {',
      '    row-gap: 8px;',
      '  }',
      '  /* trailing 内联靠右，模型+发送在同一行 */',
      '  [data-composer-card] [class*="trailing"] {',
      '    margin-left: auto !important;',
      '    flex-wrap: nowrap !important;',
      '    gap: 10px;',
      '    justify-content: flex-end !important;',
      '  }',
      '  /* 模型控件宽度自适应(内容宽)，紧贴发送键左侧 */',
      '  [data-composer-card] [class*="trailing"] [class*="trigger"] {',
      '    flex: 0 0 auto !important;',
      '    min-width: 0 !important;',
      '    max-width: 50vw !important;',
      '  }',
      '  /* 发送键保持最右 */',
      '  [data-composer-card] [class*="trailing"] [class*="primary"] {',
      '    flex: none !important;',
      '    margin-left: 0 !important;',
      '  }',
      '  /* 下拉菜单：内容超高时纵向滚动，避免被 overflow:hidden 截断/弹出屏外 */',
      '  [data-composer-card] [class*="menu"] {',
      '    overflow-y: auto !important;',
      '    max-height: min(360px, 40dvh) !important;',
      '    z-index: 30 !important;',
      '  }',
      '}',
    ].join('\n');

    exports.apply = function apply(ctx) {
      if (!ctx || !ctx.layout) return;

      // body-ready 守卫：本插件脚本注入在 <head>，若模块系统抢在 <body> 解析
      // 完成前调用 apply()，段 2/3/7/8 的 document.body 操作会抛 TypeError 并
      // 连累其后所有段（表现为汉堡+设置面板一起消失、刷新又好）。
      // 未就绪则挂 DOMContentLoaded 重入一次；重入安全（所有创建均幂等：
      // getElementById 判存 + __dsh_mobile_nav_bound 标志）。
      if (!document.body) {
        bootLog('apply-deferred(no-body)');
        document.addEventListener('DOMContentLoaded', function () {
          try { apply(ctx); } catch (e) { bootLog('apply-retry-fail:' + (e && e.message || e)); }
        }, { once: true });
        return;
      }
      bootLog('apply');

      // JS 探测不用 :has()：目标内核的 :has() 失效缓存有 bug —— 设置面板卸载后
      // querySelector(':has(> nav)') 仍可能返回已脱离文档的 dialog，使所有实时
      // 通道误判"面板还开着"、汉堡维持隐藏（刷新才恢复）。
      // 用 querySelectorAll 纯属性匹配（不走 :has 缓存）再逐个检查直接子节点
      // tagName==='NAV'，语义与 :has(> nav) 完全等价。
      function settingsDialog() {
        var list = document.querySelectorAll('[role="dialog"][aria-modal="true"]');
        for (var i = 0; i < list.length; i++) {
          var kids = list[i].children;
          for (var k = 0; k < kids.length; k++) {
            if (kids[k].tagName === 'NAV') return list[i];
          }
        }
        return null;
      }

      // 1) Inject the global stylesheet (idempotent).
      var style = document.querySelector('style[data-plugin="@local/dsh-mobile-adapt"]');
      if (style === null) {
        style = document.createElement('style');
        style.setAttribute('data-plugin', '@local/dsh-mobile-adapt');
        style.textContent = MOBILE_CSS;
        document.head.appendChild(style);
      }

      // 1b) Mobile-adapt on/off switch (hot toggle, persisted).
      //     开关内嵌在设置标题(navTitle)旁，做成手机图标 chip，不做底部悬浮浮层。
      //     chip 用行内样式（不依赖 MOBILE_CSS），官方原版
      //     模式下依然可见可点，能切回。dsh 结构（源码 dsh-client-ui-settings-general
      //     SettingsRoot）：dialog[role=dialog] > nav > div.navTitle(纯文本"设置")
      //     —— 在 navTitle 末尾 append 一个 inline-flex <button>，天然跟在标题文字
      //     之后，中英文标题长度不同都不受影响。
      // localStorage 在部分 WebView 配置下会抛 SecurityError，裸读会杀死本段及
      // 其后全部段——读失败按默认开处理，仅失去跨刷新持久化。
      var enabled = true;
      try { enabled = localStorage.getItem('dsh-mobile-enabled') !== '0'; } catch (e) { bootLog('ls-read-fail'); }
      // 清理遗留的底部浮层节点（同页面从旧 bundle 热切换过来时可能残留）
      var stalePill = document.getElementById('dsh-mobile-toggle');
      if (stalePill && stalePill.parentNode) stalePill.parentNode.removeChild(stalePill);

      function chipCopy() {
        var l = (document.documentElement.getAttribute('lang') || navigator.language || 'en').toLowerCase();
        if (l.indexOf('zh') === 0) return { on: '开', off: '关', label: '移动端适配', back: '← 返回', backAria: '返回上级', refresh: '请刷新页面' };
        return { on: 'On', off: 'Off', label: 'Mobile adaptation', back: '← Back', backAria: 'Go back', refresh: 'Please refresh the page' };
      }
      // 切换适配后，顶部居中**悬浮**提示"请刷新页面"5 秒。
      // 全行内样式，不依赖 MOBILE_CSS（汉堡实时显隐在该内核上不可靠：其定位/尺寸
      // 依赖 style.disabled 生效，存在"存在但未定位=隐形"的风险通道），悬浮件免疫。
      var adaptToastEl = null;
      function showAdaptToast() {
        try {
          var copy = chipCopy();
          if (adaptToastEl === null) {
            adaptToastEl = document.createElement('div');
            adaptToastEl.id = 'dsh-adapt-toast';
          }
          if (!adaptToastEl.isConnected) document.body.appendChild(adaptToastEl);
          adaptToastEl.textContent = copy.refresh;
          // 磨玻璃观感：浅底深字、纵向 padding 压扁、字号 12、
          // 半透明白 + backdrop-filter 磨砂、显示 3 秒后 3 秒渐隐。
          // 淡入要求瞬时：先 transition:none 置 1，退场才挂 3s 过渡——两段式控制。
          adaptToastEl.style.cssText = [
            'position:fixed',
            'top:calc(env(safe-area-inset-top,0px) + 12px)',
            'left:50%', 'transform:translateX(-50%)',
            'z-index:2147483000',
            'padding:5px 14px', 'border-radius:14px',
            'background:rgba(255, 255, 255, .5)',
            'color:#111',
            '-webkit-backdrop-filter:blur(14px) saturate(1.5)',
            'backdrop-filter:blur(14px) saturate(1.5)',
            'border:1px solid rgba(255, 255, 255, .45)',
            'box-shadow:0 4px 16px rgba(0, 0, 0, .12)',
            'font-size:12px', 'font-weight:500', 'line-height:1.35', 'white-space:nowrap',
            'pointer-events:none',
            'opacity:1', 'transition:none',
          ].join(';');
          clearTimeout(adaptToastEl.__dshToastTimer);
          adaptToastEl.__dshToastTimer = setTimeout(function () {
            adaptToastEl.style.transition = 'opacity 3s ease';
            adaptToastEl.style.opacity = '0';
          }, 3000);
        } catch (e) { bootLog('adapt-toast-fail:' + (e && e.message || e)); }
      }
      // 12px 手机图标，stroke 走 currentColor，深浅主题自动反色
      var PHONE_SVG = '<svg width="12" height="12" viewBox="0 0 16 16" fill="none" ' +
        'stroke="currentColor" stroke-width="1.6" stroke-linecap="round" aria-hidden="true">' +
        '<rect x="4.2" y="1.6" width="7.6" height="12.8" rx="2"/>' +
        '<line x1="6.9" y1="12.1" x2="9.1" y2="12.1"/></svg>';
      function styleChipBase(chip) {
        // 贴合 navTitle 的 24px 行高：20px 高的小胶囊，跟在文字后
        chip.style.cssText = [
          'display:inline-flex', 'align-items:center', 'gap:4px',
          'vertical-align:middle', 'margin-left:8px',
          'height:20px', 'padding:0 8px',
          'border:none', 'border-radius:10px',
          'font-family:inherit', 'font-size:11px', 'font-weight:600', 'line-height:1',
          'cursor:pointer', 'white-space:nowrap',
          '-webkit-tap-highlight-color:transparent', 'touch-action:manipulation',
        ].join(';');
      }
      function renderChip(chip) {
        var copy = chipCopy();
        chip.dataset.state = enabled ? '1' : '0'; // 幂等哨兵：防 dialogObs 重建循环
        chip.setAttribute('aria-label', copy.label);
        chip.title = copy.label;
        styleChipBase(chip);
        // 开=深色实底(与 web 主题反色自动适配)，关=浅灰底次要文字
        chip.style.background = enabled
          ? 'var(--dsw-alias-text-primary,#111)'
          : 'var(--dsw-alias-interactive-bg-hover,#e5e5e5)';
        chip.style.color = enabled
          ? 'var(--dsw-alias-bg-float,#fff)'
          : 'var(--dsw-alias-label-secondary,#666)';
        chip.textContent = '';
        var icon = document.createElement('span');
        icon.style.cssText = 'display:inline-flex;align-items:center';
        icon.innerHTML = PHONE_SVG;
        var txt = document.createElement('span');
        txt.textContent = enabled ? copy.on : copy.off;
        chip.appendChild(icon);
        chip.appendChild(txt);
      }
      // 注入点：dialog > nav > 第一个 div（即 navTitle，React useId 的 aria-labelledby 目标）
      function ensureChip(dlg) {
        var nav = dlg.querySelector(':scope > nav');
        if (!nav) return;
        var titleBox = nav.firstElementChild;
        if (!titleBox) return;
        var chip = titleBox.querySelector('#dsh-mobile-chip');
        if (chip !== null) {
          // 仅当状态/语言与已渲染不符才重建（重建=DOM 变更，会再触发 observer，
          // 不符判断保证收敛，杜绝观察循环）
          var want = enabled ? '1' : '0';
          if (chip.dataset.state !== want) renderChip(chip);
          return;
        }
        chip = document.createElement('button');
        chip.id = 'dsh-mobile-chip';
        chip.type = 'button';
        chip.addEventListener('click', function (ev) {
          ev.stopPropagation();
          setEnabled(!enabled);
        });
        renderChip(chip);
        titleBox.appendChild(chip);
      }
      function refreshChips() {
        var chips = document.querySelectorAll('#dsh-mobile-chip');
        for (var i = 0; i < chips.length; i++) renderChip(chips[i]);
      }

      function setEnabled(v) {
        enabled = !!v;
        try { localStorage.setItem('dsh-mobile-enabled', enabled ? '1' : '0'); } catch (e) { /* 存不下也要当场生效 */ }
        if (style) style.disabled = !enabled; // 整张移动 CSS 热切换
        // 清理残留状态
        document.body.classList.remove('dsh-settings-content-open');
        // 汉堡/返回键/遮罩随模式显隐（返回键还受设置面板状态控制，由 dialogObs 统一处理）
        if (menu) menu.style.display = (!enabled || settingsOpen) ? 'none' : 'flex';
        if (backdrop) backdrop.style.display = 'none';
        // 键盘修复的 transform 清理
        if (window.__dsh_kb_lift) { window.__dsh_kb_lift.style.transform = ''; window.__dsh_kb_lift = null; }
        // brand 文本随模式切换
        applyBrand();
        // 已注入的 chip 全部刷新（可能同时存在多实例：重开面板未卸载时）
        refreshChips();
        // 兜底再同步：下一个任务里按"当下"的 DOM 重算汉堡显隐，
        // 覆盖任何错过/迟到的 observer 拍（dialogObs 仍是主通道，这里只是保险）。
        setTimeout(function () {
          try {
            var d = settingsDialog();
            if (menu) menu.style.display = (d || !enabled) ? 'none' : 'flex';
          } catch (e) { /* noop */ }
        }, 0);
        // 悬浮提示"请刷新页面"（5 秒淡出）。开/关两个方向都提示，
        // 因为布局热切换在该内核上不可靠，刷新才是确定性生效路径。
        // 关模式要确定性把已压的返回哨兵吃净（见段 7b）：不能只靠 toast 挂载产生的
        // 突变把对账"顺带"带起来——toast 整段在 try/catch 里，失败就漏吃。
        reconcileBackSentinels();
        showAdaptToast();
      }
      var settingsOpen = false;

      // 2) Hamburger button (created once, on <body>).
      var menu = null;
      try {
        menu = document.getElementById('dsh-mobile-menu');
        if (menu === null) {
          menu = document.createElement('button');
          menu.id = 'dsh-mobile-menu';
          menu.textContent = '☰';
          menu.setAttribute('aria-label', '切换侧边栏');
          menu.addEventListener('click', function () {
            try { ctx.layout.toggleSidebar(); } catch (e) { /* noop */ }
          });
          document.body.appendChild(menu);
        }
      } catch (e) { bootLog('menu-fail:' + (e && e.message || e)); }

      // 3) Backdrop to close the drawer by tapping outside.
      var backdrop = null;
      try {
        backdrop = document.querySelector('.dsh-mobile-backdrop');
        if (backdrop === null) {
          backdrop = document.createElement('div');
          backdrop.className = 'dsh-mobile-backdrop';
          backdrop.addEventListener('click', function () {
            try { ctx.layout.toggleSidebar(); } catch (e) { /* noop */ }
          });
          document.body.appendChild(backdrop);
        }
      } catch (e) { bootLog('backdrop-fail:' + (e && e.message || e)); }

      // 4) 左下角「返回」键：**内嵌**在 nav/content 列内，不做 position:fixed 悬浮胶囊——
      //    第一步(导航页)：nav 列尾子节点，margin-top:auto 贴底左下角，观感对齐 dsh navCell；
      //    第二步(内容页)：content 列尾子节点(第 3 子)，options 已改 nth-child(2) 定位不受波及。
      //    官方原版模式下不注入（dsh 原生 X 即可关闭），dialogObs 统一驱动。
      var staleBack = document.getElementById('dsh-settings-back-lb');
      if (staleBack && staleBack.parentNode) staleBack.parentNode.removeChild(staleBack);
      var backInline = null;
      function ensureBack(dlg) {
        if (!enabled) { hideBack(); return; }
        if (backInline === null) {
          backInline = document.createElement('button');
          backInline.id = 'dsh-mobile-back-inline';
          backInline.type = 'button';
          var c0 = chipCopy();
          backInline.textContent = c0.back;
          backInline.setAttribute('aria-label', c0.backAria);
          backInline.style.cssText = [
            'display:flex', 'align-items:center', 'gap:8px', 'flex:none',
            'width:auto', 'min-width:88px', 'max-width:100%',
            'height:40px', 'padding:0 12px',
            'margin-top:auto', // 贴 flex 列底部（nav 列高 100dvh；content 列 options 已 flex:1）
            'margin-bottom:calc(env(safe-area-inset-bottom,0px) + 14px)',
            'border:none', 'border-radius:12px',
            'background:transparent',
            'color:var(--dsw-alias-label-primary,#111)',
            'font-family:inherit', 'font-size:14px', 'font-weight:400', 'line-height:22px',
            'text-align:left', 'cursor:pointer',
            '-webkit-tap-highlight-color:transparent', 'touch-action:manipulation',
          ].join(';');
          backInline.addEventListener('click', function (ev) {
            ev.stopPropagation();
            if (document.body.classList.contains('dsh-settings-content-open')) {
              goToNav();
            } else {
              closeSettings();
            }
          });
        }
        // 语言可能已切换：仅当文案不符才更新（textContent 赋值即使等值也会产生
        // childList 突变，会再触发 observer——等值判断保证收敛，杜绝循环）
        var c1 = chipCopy();
        if (backInline.textContent !== c1.back) backInline.textContent = c1.back;
        var host = document.body.classList.contains('dsh-settings-content-open')
          ? dlg.querySelector(':scope > div')   // content 列（第二步）
          : dlg.querySelector(':scope > nav');  // nav 列（第一步）
        if (!host) return;
        // 已在位则零 DOM 变更（appendChild 会跨父移动并触发 observer，必须幂等守卫）
        if (host.lastElementChild !== backInline) host.appendChild(backInline);
      }

      // 5) Close the settings panel by invoking dsh's real onClose.
      var closing = false;
      function closeSettings() {
        closing = true;
        var dlg = document.querySelector('[role="dialog"][aria-modal="true"]');
        if (!dlg) { closing = false; return; }
        var content = dlg.querySelector(':scope > div');
        if (content) {
          var header = content.firstElementChild;
          if (header) {
            var btns = header.querySelectorAll('button');
            var btn = btns[btns.length - 1];
            if (btn) {
              btn.removeAttribute('data-dsh-close');
              btn.click();
              setTimeout(function () { closing = false; }, 400);
              return;
            }
          }
        }
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
        setTimeout(function () { closing = false; }, 400);
      }

      function showBack() {
        var dlg = settingsDialog();
        if (dlg) { try { ensureBack(dlg); } catch (e) { /* noop */ } }
      }
      function hideBack() {
        if (backInline && backInline.parentNode) backInline.parentNode.removeChild(backInline);
      }

      function goToContent() {
        document.body.classList.add('dsh-settings-content-open');
        showBack();
        // 内容列是独立一层（见段 7b）：类名变化不产生 childList 突变，dialogObs 收不到，
        // 这里显式对账哨兵，不依赖内嵌返回键换父节点那次副产物。
        reconcileBackSentinels();
      }
      function goToNav() {
        document.body.classList.remove('dsh-settings-content-open');
        showBack();
        reconcileBackSentinels();
      }

      // 6) Two-step settings navigation + sidebar session-row handling.
      if (!document.__dsh_mobile_nav_bound) {
        document.__dsh_mobile_nav_bound = true;
        document.addEventListener('click', function (e) {
          if (closing) return;

          // 点侧栏里的"对话记录"→ dsh 切换会话后自动收起侧栏，回到对话主界面。
          // 行内交互子树（⋯菜单钮/重命名输入框/菜单项等）不得排定收起。
          //   dsh 三点钮就在 sessionRow 内（row > span.rowActions > Menu anchor），
          //   其 React stopPropagation 只挡 React 合成层，拦不住本 capture 监听——
          //   不排行的话点⋯→350ms 后抽屉收起→菜单锚点被 display:none 吞→菜单闪现即灭。
          var sbRow = e.target && e.target.closest ? e.target.closest('[class*="sessionRow"]') : null;
          if (sbRow && enabled) {
            if (e.target.closest('[class*="rowActions"], button, input, [role="menu"], [role="menuitem"]')) return;
            setTimeout(function () {
              // 定时器到点二次核查——任一行的菜单仍打开
              // （dsh 源码确认：菜单打开时行加 YDXeBa_menuOpen 类）或存在菜单 portal
              // 时跳过本次收起，兜住"排定后菜单才被打开"的竞态。
              if (document.querySelector('[class*="menuOpen"]') || document.querySelector('[role="menu"]')) return;
              var f = document.querySelector('[class*="frame"]');
              if (f && !f.hasAttribute('data-sidebar-collapsed')) {
                try { ctx.layout.toggleSidebar(); } catch (err) { /* noop */ }
              }
            }, 350); // 等 dsh 完成会话切换再收起，避免竞争
            return;
          }

          // 点侧栏顶部的「全局面板行」（插件 / 自动化任务 …）→ 同样要自动收起抽屉。
          // 根因：这两项不是链接/路由，PanelRow 的 onClick 只调
          //   ctx.layout.selectPanel(id)，而该动作**只写 panelInfo.activePanelId**
          //   （ui-layout stores：selectPanel 不碰 narrowExpanded/sidebar），
          //   于是目标页被换到**中栏 main 插槽**；本插件在 ≤1024px 又把侧栏改成
          //   全屏 fixed 抽屉(z-index:1000)，正好把中栏整个盖住 —— 用户看到的是
          //   「点了没反应、不会自己跳转」。面板行与会话行一样，必须由本插件
          //   主动收起抽屉。（官方原版不做此收起：原生侧栏是窄轨道，不遮挡中栏）
          // 注意：面板行本身就是 <button>，不能沿用会话行那套 `button` 排除项，
          //   只排除行内真正独立的交互子树（将来若加行内菜单/开关）。
          var sbPanel = e.target && e.target.closest ? e.target.closest('[class*="panelRow"]') : null;
          if (sbPanel && enabled) {
            if (e.target.closest('[class*="rowActions"], input, [role="menu"], [role="menuitem"]')) return;
            setTimeout(function () {
              // 二次核查只判"抽屉此刻是否仍开着"，不查 [role="menu"]：
              //   面板行没有行内菜单，而 dsh 的下拉/命令面板都可能
              //   短暂挂着 role="menu" 节点，查它会让收起被无关菜单永久抑制。
              var f = document.querySelector('[class*="frame"]');
              if (f && !f.hasAttribute('data-sidebar-collapsed')) {
                try { ctx.layout.toggleSidebar(); } catch (err) { /* noop */ }
              }
            }, 180); // selectPanel 是同步 store 写入，180ms 足够其完成一帧提交，再收起不会看到闪跳
            return;
          }

          if (!enabled) return; // 官方原版：设置面板保持 dsh 原生，不干预
          var dlg = document.querySelector('[role="dialog"][aria-modal="true"]');
          if (!dlg) return;
          var navBtn = e.target && e.target.closest ? e.target.closest('nav button') : null;
          if (navBtn && dlg.contains(navBtn)) {
            // 内嵌 chip/返回键同样位于 nav 内，但语义不是「进入分类」。
            // 不放行则 capture 的 goToContent 与控件自身逻辑互相抵消（返回键失灵、
            // 表现为整行 tap 高亮残留、页面无反应）。放行给目标自身的 handler。
            if (navBtn.id === 'dsh-mobile-chip' || navBtn.id === 'dsh-mobile-back-inline') return;
            goToContent();
            return;
          }
          var closeBtn = e.target && e.target.closest ? e.target.closest('[data-dsh-close]') : null;
          if (closeBtn && dlg.contains(closeBtn)) {
            if (document.body.classList.contains('dsh-settings-content-open')) {
              e.preventDefault();
              e.stopPropagation();
              goToNav();
            }
            return;
          }
        }, true);
      }

      // 7) Watch the dialog: tag native close, keep the back key synced.
      function tagCloseButton(dlg) {
        var content = dlg.querySelector(':scope > div');
        if (!content) return;
        var header = content.firstElementChild;
        if (!header) return;
        var btns = header.querySelectorAll('button');
        var btn = btns[btns.length - 1];
        if (btn && !btn.hasAttribute('data-dsh-close')) {
          btn.setAttribute('data-dsh-close', '1');
        }
      }

      var dialogObs = new MutationObserver(function () {
        // 仅当"真实设置面板"(含 NAV 直接子节点的 dialog)存在时才隐藏汉堡、显示返回键/开关
        var dlg = settingsDialog();
        settingsOpen = !!dlg;
        // 自愈：宿主重渲染若摘除过我们挂在 body 的节点，下一拍补挂
        try {
          if (menu && !menu.isConnected) document.body.appendChild(menu);
          if (backdrop && !backdrop.isConnected) document.body.appendChild(backdrop);
        } catch (e) { /* noop */ }
        if (menu) menu.style.display = (dlg || !enabled) ? 'none' : 'flex';
        // 开关 chip 内嵌到设置标题旁。两个模式都要注入（官方原版下也能切回）；
        // 任何异常只吞掉 chip，不影响本回调其余逻辑（tagCloseButton/返回键等）。
        if (dlg) {
          try { ensureChip(dlg); } catch (e) { /* noop */ }
        }
        if (!dlg) {
          document.body.classList.remove('dsh-settings-content-open');
          hideBack();
          reconcileBackSentinels();
          return;
        }
        if (!enabled) {
          // 官方原版：设置面板保持 dsh 原生（不做两步式）
          hideBack();
          reconcileBackSentinels();
          return;
        }
        tagCloseButton(dlg);
        showBack();
        reconcileBackSentinels();
      });
      try { dialogObs.observe(document.body, { childList: true, subtree: true }); }
      catch (e) { bootLog('dialogObs-fail:' + (e && e.message || e)); }

      // 7b) 返回键层级：哨兵历史条目 + popstate 分发。
      //     DSH 前端不使用浏览历史（无 pushState、无路由），页面内各级浮层也不是历史
      //     条目，于是原生返回键的 goBack() 在 DSH 内部无处可退。这里在"打开一层"时
      //     向历史栈压一条哨兵条目：原生返回键弹出它时本页收到 popstate，按层级关掉
      //     对应浮层——"逐级返回"就借原生既有链路完成，原生侧无需任何改动。
      //     层级（自上而下）：设置内容列 → 设置面板整体 → 侧栏抽屉 → 右栏 → 全局中央面板；
      //     一层都没有时不压哨兵（DSH 根 / 会话页的返回交原生宿主处理）。
      //     自己关层（内嵌返回键、汉堡、遮罩、DSH 自己的关闭按钮）时必须把对应哨兵
      //     一并吃掉，否则留下幽灵条目，下一次按返回会空转一次。
      //     右栏（停靠呈现的浏览器 / 终端 / 工作区文件等）用官方服务面判定与收起，且
      //     必须**软读取**：`ctx.get('sidebarRight')`。不能把服务名写进本 bundle 的
      //     `exports.inject`——cordis 的 inject 没有可选形态，写进去会让整插件在缺该
      //     服务的 DSH 版本上永远 PENDING（汉堡/设置一起失效）；而反射层明确提供
      //     "无需 inject 的读取"，未提供时返回 undefined 而不抛（官方 chat / conversation
      //     两个插件就是用 ctx.get 读可选服务的）。
      //     判据：`isExpanded()`（类型声明原文 "true while expanded; false while collapsed
      //     to its rail"）；服务读不到时退回 DOM 语义属性 `[data-sidebar-right-open]`
      //     （展开时才渲染该属性，官方自己也这么判）。动作：`toggleExpanded()`
      //     （"Collapse the column…"，非破坏性——折叠成 rail，tab 与内容都保留）；服务
      //     不可用时退回点它自己的收起按钮 `[data-sidebar-right-toggle]`（同一动作）。
      //     仍未覆盖（有意）：右栏各面板**内部**的层级（内置浏览器/预览在 iframe 里，
      //     其导航属 iframe 自己的历史，主框架历史没有条目）；以及只有调用方显式
      //     `float()` 才会出现的浮动面板（它在列收起时仍渲染、且不属内置入口路径）。
      //     状态挂在 window 上并只绑一次 popstate：本插件支持同页面热切换（重复
      //     apply），实例换了但历史条目还在，共享状态才不会算错条数。
      var backState = window.__dshboxBackState ||
        (window.__dshboxBackState = { pushed: 0, swallow: 0, lastSelfPopAt: 0, mark: 0 });
      function panelActive() {
        try {
          var obs = ctx.layout.panelInfo;
          var snap = obs && typeof obs.getSnapshot === 'function' ? obs.getSnapshot() : null;
          return !!(snap && snap.activePanelId);
        } catch (e) { return false; }
      }
      // 展开中的右栏面板元素；仅服务面读不到时用作判据与动作锚点。`data-sidebar-right-open`
      // 展开时才渲染，且排除藏在 [hidden]/[aria-hidden] 下的残留面板（官方同一写法）。
      function rightbarPanelEl() {
        try {
          var panel = document.querySelector('[data-sidebar-right-open]');
          return panel && !panel.closest('[hidden], [aria-hidden="true"]') ? panel : null;
        } catch (e) { return null; }
      }
      // 判据以官方服务面为准：它读 store，与官方动作同源同步；DOM 属性是渲染结果，
      // 只在服务读不到（旧内核 / 未就绪）时兜底。两者不混用——否则刚收起时若 DOM
      // 落后半拍，写后校验会把"已关成功"误判成失败。
      // 注意浮动形态（官方 `float()`）**有意不计入**：服务 `isExpanded()` 只反映列的
      // expanded，浮动面板在列收起时仍渲染（见段首"未覆盖"说明），这里从"偶然撞上"
      // 改为"确定不算"，行为可预测。
      function rightbarOpen() {
        try {
          var sr = typeof ctx.get === 'function' ? ctx.get('sidebarRight') : null;
          if (sr && typeof sr.isExpanded === 'function') return !!sr.isExpanded();
        } catch (e) { /* 落到 DOM 兜底 */ }
        return !!rightbarPanelEl();
      }
      function drawerOpen() {
        return document.body.classList.contains('dsh-mobile-drawer-open');
      }
      function openLayerDepth() {
        if (!enabled) return 0;
        var dlg = settingsDialog();
        var depth = 0;
        if (dlg && document.body.classList.contains('dsh-settings-content-open')) depth += 1;
        if (dlg) depth += 1;
        if (drawerOpen()) depth += 1;
        if (rightbarOpen()) depth += 1;
        if (panelActive()) depth += 1;
        return depth;
      }
      function closeTopLayer() {
        if (settingsDialog() && document.body.classList.contains('dsh-settings-content-open')) {
          goToNav();
          return true;
        }
        if (settingsDialog()) { closeSettings(); return true; }
        if (drawerOpen()) {
          try { ctx.layout.toggleSidebar(); } catch (e) { /* noop */ }
          return true;
        }
        if (rightbarOpen()) {
          // 官方服务面优先；服务不可用（旧内核 / 未就绪）时退回点它自己的收起按钮——
          // 按钮的 onClick 就是官方的 actions.toggleExpanded(sessionId)，同一动作。
          // 兜底按面板元素定位按钮：页面上可能同时挂多个会话的 surface，文档序第一个
          // 按钮未必属于当前展开的这个（会关错对象），找不到就明确失败不吃这层。
          var closed = false;
          try {
            var sr = typeof ctx.get === 'function' ? ctx.get('sidebarRight') : null;
            if (sr && typeof sr.toggleExpanded === 'function') {
              sr.toggleExpanded();
              closed = !rightbarOpen();
            } else {
              var rbPanel = rightbarPanelEl();
              var rbToggle = rbPanel ? rbPanel.querySelector('button[data-sidebar-right-toggle]') : null;
              if (!rbToggle) {
                // 面板子树内没有则退回全文档，但只认唯一匹配（多 surface 时宁可不吃，
                // 也不误关别的会话）。随包版本里 chrome 由 DockLayout 渲染在面板内，
                // 这里只是防未来版本把按钮移出面板后兜底路径恒失败。
                var rbToggles = document.querySelectorAll('button[data-sidebar-right-toggle]');
                if (rbToggles.length === 1) rbToggle = rbToggles[0];
              }
              if (rbToggle) {
                rbToggle.click();
                closed = !rightbarOpen();
              } else {
                bootLog('rightbar-close-no-anchor');
              }
            }
          } catch (e) {
            // 无在屏会话时官方 require() 会抛；维持 false，让对账保留这层的哨兵——
            // 右栏仍开着时不动下一层，宁可不吃也不错层。
            bootLog('rightbar-close-fail:' + (e && e.message || e));
          }
          return closed;
        }
        if (panelActive()) {
          try { ctx.layout.selectPanel(null); } catch (e) { /* noop */ }
          return true;
        }
        return false;
      }
      function reconcileBackSentinels() {
        try {
          // 热切换（重复 apply）后旧实例的观察者仍在跑：只让最新实例动账，否则两个
          // 实例各自按自己的 enabled 压/吃哨兵，条数会与真实历史栈脱节。
          if (window.__dshboxCloseTopLayer && window.__dshboxCloseTopLayer !== closeTopLayer) return;
          window.__dshboxCloseTopLayer = closeTopLayer;
          if (!history || typeof history.pushState !== 'function') return;
          var depth = openLayerDepth();
          if (depth > backState.pushed) {
            // 合法层级最深 5（设置内容列 + 设置面板 + 抽屉 + 右栏 + 中央面板）；到 6 说明
            // 记账已经跑飞（例如某层关不掉又被反复补压）。此时停止补压，让返回键能继续
            // 弹真实条目直到退出 DSH——原生侧 goBack() 之外没有兜底，锁死不可接受。
            if (backState.pushed >= 6) { bootLog('back-repush-cap'); return; }
            for (var i = backState.pushed; i < depth; i++) {
              history.pushState({ dshboxBack: i + 1 }, '');
            }
            backState.pushed = depth;
            backState.mark = depth;
          } else if (depth < backState.pushed) {
            var extra = backState.pushed - depth;
            backState.pushed = depth;
            backState.mark = depth;
            backState.swallow += extra;
            backState.lastSelfPopAt = Date.now();
            for (var j = 0; j < extra; j++) history.back();
          }
        } catch (e) { bootLog('back-reconcile-fail:' + (e && e.message || e)); }
      }
      window.__dshboxCloseTopLayer = closeTopLayer;
      window.__dshboxReconcileBack = reconcileBackSentinels;
      if (!window.__dshboxBackBound) {
        window.__dshboxBackBound = true;
        window.addEventListener('popstate', function (event) {
          try {
            // 落点标记：是我们压的哨兵条目则 >0，原始文档为 0。
            var dest = (event && event.state && event.state.dshboxBack) || 0;
            // 自己发起的回退（关层时吃哨兵）：只销账、不再关层。窗口收紧到 500ms
            // 且要求落点仍在我们标记范围内——否则当作真实回退，不吃（否则会吞掉
            // 紧随其后的一次合法返回，表现为"按了没反应"）。
            if (backState.swallow > 0 &&
                Date.now() - backState.lastSelfPopAt < 500 &&
                dest <= backState.mark) {
              backState.swallow -= 1;
              backState.mark = dest;
              return;
            }
            backState.swallow = 0;
            if (dest < backState.mark) {
              // 真实回退：声明层数减一，再关掉最上面那层。
              backState.mark = dest;
              if (backState.pushed > 0) backState.pushed -= 1;
              if (typeof window.__dshboxCloseTopLayer === 'function') window.__dshboxCloseTopLayer();
            } else {
              // 前进，或落点在哨兵范围之外（旧文档条目等）：不动层。
              backState.mark = dest;
            }
          } catch (e) { bootLog('back-pop-fail:' + (e && e.message || e)); }
          // 关层会改 DOM/状态，但 dialogObs 与 syncUI 的触发不保证覆盖所有路径；
          // 这里无条件补一次对账，避免 pushed 与真实层数漂移后"按返回空转一次"。
          if (typeof window.__dshboxReconcileBack === 'function') window.__dshboxReconcileBack();
        });
      }
      // 右栏开合的唯一对账触发源：面板常驻挂载，开/收只是给它加/摘
      // `data-sidebar-right-open` 并靠 CSS 滑出滑入，不产生 childList 变更，
      // dialogObs 与 syncUI（只订 data-sidebar-collapsed）都收不到——不订这一条，
      // 打开右栏时哨兵不会压，返回键便直接穿透到原生宿主。
      // 订 body 的这条属性而不是订面板元素：面板随会话 surface 增删，body 才不丢生命周期。
      // 整棵面板子树被卸载时本观察器收不到（脱离观察子树），那个方向由 dialogObs 的
      // childList 通道兜住——两端各管一向，不要改成订面板元素或加 childList。
      // 本观察器有意不做 teardown：它必须活过热切换，回调经 window 转发到最新实例的
      // 对账函数（仍受"最新实例动账"守卫），按实例 teardown 会让右栏通道在热切换后失效。
      if (!window.__dshboxRightObsBound) {
        window.__dshboxRightObsBound = true;
        try {
          new MutationObserver(function () {
            if (typeof window.__dshboxReconcileBack === 'function') window.__dshboxReconcileBack();
          }).observe(document.body, {
            attributes: true,
            subtree: true,
            attributeFilter: ['data-sidebar-right-open']
          });
        } catch (e) { bootLog('rightbar-obs-fail:' + (e && e.message || e)); }
      }

      // 8) Sync drawer-open state onto <body> so CSS shows the backdrop.
      //    Use a hash-tolerant selector for the frame container.
      function syncUI(frame) {
        if (!enabled) {
          document.body.classList.remove('dsh-mobile-drawer-open');
          reconcileBackSentinels();
          return;
        }
        var collapsed = frame.hasAttribute('data-sidebar-collapsed');
        document.body.classList.toggle('dsh-mobile-drawer-open', !collapsed);
        // 抽屉开合是纯 body 类名变化（不产生 childList 变更），dialogObs 收不到，
        // 所以哨兵条数在这里对账：抽屉开 → 压一条；关 → 吃掉一条。
        reconcileBackSentinels();
      }
      function whenFrameReady(cb) {
        var frame = document.querySelector('[class*="frame"]');
        if (frame) { cb(frame); return; }
        var obs = new MutationObserver(function () {
          var f = document.querySelector('[class*="frame"]');
          if (f) { obs.disconnect(); cb(f); }
        });
        obs.observe(document.body, { childList: true, subtree: true });
      }
      try { whenFrameReady(function (frame) {
        syncUI(frame);
        var attrObs = new MutationObserver(function () { syncUI(frame); });
        attrObs.observe(frame, { attributes: true, attributeFilter: ['data-sidebar-collapsed'] });
      }); } catch (e) { bootLog('frame-ready-fail:' + (e && e.message || e)); }

      // 9) Keyboard fix: when the input is focused and the on-screen keyboard
      //    pops up, keep the composer visible above the keyboard.
      //    - If a scrollable ancestor exists (existing conversation), scroll it.
      //    - Otherwise (hero/new-chat, no scrollable space) translate the card
      //      up; restore when the keyboard hides.
      //    dsh itself has no visualViewport handling; the bare browser auto
      //    scroll only works when there IS overflow, which hero lacks.
      function installKeyboardFix() {
        var vv = window.visualViewport;
        var lifted = null; // card currently translated up
        var liftTransform = '';
        function apply() {
          if (!enabled) return; // 官方原版：不干预
          var card = document.querySelector('[data-composer-card]');
          if (!card) return;
          var ta = card.querySelector('textarea');
          var focused = document.activeElement === ta || (ta && ta.contains(document.activeElement));
          if (!focused) {
            if (lifted) { lifted.style.transform = liftTransform; lifted = null; }
            return;
          }
          var rect = card.getBoundingClientRect();
          var vvH = (vv ? vv.height : 0) || window.innerHeight;
          var overlap = rect.bottom - vvH + 12;
          if (overlap <= 0) {
            if (lifted) { lifted.style.transform = liftTransform; lifted = null; }
            return;
          }
          // 1) Try scrolling a scrollable ancestor (existing conversation).
          var el = card.parentElement;
          var scrolled = false;
          while (el && el !== document.body) {
            var st = getComputedStyle(el);
            if ((st.overflowY === 'auto' || st.overflowY === 'scroll' || st.overflowY === 'overlay')
              && el.scrollHeight > el.clientHeight) {
              el.scrollTop += overlap;
              scrolled = true;
              break;
            }
            el = el.parentElement;
          }
          if (!scrolled) {
            // 2) Hero/new-chat: no scrollable space → lift the card via transform.
            if (lifted !== card) { liftTransform = card.style.transform || ''; lifted = card; }
            card.style.transform = 'translateY(-' + Math.ceil(overlap) + 'px)';
            window.__dsh_kb_lift = card; // setEnabled 时清理
          }
        }
        if (vv) {
          vv.addEventListener('resize', apply);
          vv.addEventListener('scroll', apply);
        }
        document.addEventListener('focusin', function () { setTimeout(apply, 80); });
        document.addEventListener('focusout', function () { setTimeout(apply, 150); });
        // Run once shortly after mount in case focus is already inside.
        setTimeout(apply, 600);
      }
      // 【已停用 —— 实现整体保留在 installKeyboardFix 内，便于回退】
      //
      // 停用理由：0.1.7 改了 composer 的结构，先按"完全上游原生行为"取基线，
      // 判断上游是否已自行处理键盘遮挡，再决定是否需要本段（尤其第 2 级那套
      // transform 抬升：它会把卡片整体上移，观感上就是"对话框被拉伸"）。
      //
      // 回退方式（按需二选一）：
      //  · 整体恢复：取消下面这行的注释即可；
      //  · 只恢复第 1 级（滚动祖先）：把 installKeyboardFix 内第 2 级的
      //    transform 分支保持注释、仅恢复函数调用。
      //
      // 注意：setEnabled 里对 window.__dsh_kb_lift 的清理可以留着 ——
      // 本段停用后该字段不会再被写入，清理分支自然不生效，无副作用。
      // try { installKeyboardFix(); } catch (e) { bootLog('kb-fail:' + (e && e.message || e)); }

      // 10) Brand: 【已停用 —— 实现整体注释保留，便于回退】
      //
      // 停用理由：web profile 启用上游 `ui-brand-official` 条目，它通过
      // 插槽 `sidebar.brand.name` 渲染官方 SVG 字标；而 `fallbackBrandName` /
      // `localBuildTitle` 只在 `buildVersion` 未定义时才渲染。因此本段在上游当前
      // 形态下**什么都没做**（两个选择器都查不到，applyBrand 提前 return）——
      // 侧栏显示正确是上游的功劳，与本段无关。
      //
      // 反过来，一旦某构建禁用了该官方条目，fallback 分支就会渲染，本段便会把
      // 正确的「DSH 本地构建」改写成 "DSH mobile" 并隐藏版本徽标 —— 那才是"改坏"。
      // 类名子串方案本身也在漂移（`buildRevision` 在 0.1.7 已全树消失，只剩
      // `buildVersion` 单腿支撑）。
      //
      // 若将来确需定制品牌：应注册 `sidebar.brand.name` 插槽，而不是做 DOM 改写。
      //
      // 这里保留一个**同名 no-op 声明**而不是彻底删掉：setEnabled 里有一处调用点
      // （品牌文本随模式切换），删掉定义会让那个调用点抛 ReferenceError。
      function applyBrand() { /* 已停用：见上方说明 */ }

      /*
      function applyBrand() {
        var sb = document.querySelector('[class*="sidebarCol"]');
        if (!sb) return;
        var name = sb.querySelector('[class*="fallbackBrandName"], [class*="localBuildTitle"]');
        var rev = sb.querySelector('[class*="buildRevision"], [class*="buildVersion"]');
        if (rev) rev.style.display = 'none';
        if (!name) return;
        if (enabled) {
          name.textContent = 'DSH mobile';
        } else {
          name.textContent = 'DeepSeek Harness';
        }
      }
      try { (function watchBrand() {
        var sb = document.querySelector('[class*="sidebarCol"]');
        applyBrand();
        var obs = new MutationObserver(function () {
          applyBrand();
        });
        obs.observe(document.body, { childList: true, subtree: true });
      })(); } catch (e) { bootLog('brand-fail:' + (e && e.message || e)); }
      */

      // 11) Initial enabled state application (style.disabled + hamburger).
      if (style) style.disabled = !enabled;
      if (menu) menu.style.display = enabled ? 'flex' : 'none';
      if (backdrop) backdrop.style.display = 'none';
      // Expose for the session-row handler to check.
      window.__dsh_mobile_enabled = function () { return enabled; };

      // 11b) 安全网：1Hz 轻量复查（一次 querySelectorAll + 子节点扫描，开销可忽略）。
      // 即使 observer/兜底同步等全部事件通道被内核缓存愚弄，汉堡显隐最迟 1 秒内
      // 自动纠正；真实发生纠正才记面包屑（供事后诊断"哪条通道瞎了"）。
      var menuSafetySeen = null;
      setInterval(function () {
        try {
          var d = settingsDialog();
          settingsOpen = !!d;
          var want = (d || !enabled) ? 'none' : 'flex';
          if (menu) {
            if (!menu.isConnected) { document.body.appendChild(menu); bootLog('menu-safety-reattach'); }
            else if (menu.style.display !== want) {
              if (menuSafetySeen !== want) { bootLog('menu-safety-fix:' + want); menuSafetySeen = want; }
              menu.style.display = want;
            }
          }
          if (backdrop && !backdrop.isConnected) document.body.appendChild(backdrop);
        } catch (e) { /* noop */ }
      }, 1000);

      // =====================================================================
      // 13) 「打开配置文件」接管
      // =====================================================================
      // 背景：设置面板顶部的「打开配置文件」按钮
      // （dsh-client-ui-settings-general，仅 loopback 时出现 —— 手机上 127.0.0.1
      //  直连也算 loopback，所以按钮**会**出现）调用
      //   ctx.remote.settings.openSettingsDocument()
      // 宿主端用 OS 默认应用打开 `<DSH_HOME>/settings.yaml`；
      // 而宿主跑在 PRoot 沙箱里 —— 没有 xdg-open、没有「默认应用」，
      // 结果必然是 UI 弹「无法打开配置文件」。
      //
      // 处置：接管该按钮 —— 拦截点击后让页面导航到内部 scheme
      //   dshbox://open-settings-document
      // 由原生 WebViewClient.shouldOverrideUrlLoading 消费，再用 app 内置
      // 文件查看器打开（自带 YAML 高亮，不依赖任何外部编辑器/查看器）。
      // 路径由原生侧按 DSH_HOME=/root/projects/.dsh 解析到 user-data/.dsh/。
      //
      // 按钮识别：dsh 此命名空间只注册了 zh/en 两套字典
      // （源码 ctx.locale.register(NS, { zh, en })），直接按可显示文本精确匹配，
      // 比猜哈希类名/结构稳；其余语言未安装该文案时 dsh 回落英文。
      var OPEN_DOC_LABELS = [
        '打开配置文件',             // zh
        'Open configuration file',  // en
      ];

      function isOpenDocButton(btn) {
        if (!btn) return false;
        var label = (btn.textContent || '').replace(/\s+/g, ' ').trim();
        return OPEN_DOC_LABELS.indexOf(label) >= 0;
      }

      try { (function installOpenDocumentShim() {
        document.addEventListener('pointerdown', function (ev) {
          if (!(ev.target instanceof Node)) return;
          var btn = ev.target.closest ? ev.target.closest('button') : null;
          if (!isOpenDocButton(btn)) return;
          ev.preventDefault();
          ev.stopPropagation();
          ev.stopImmediatePropagation();
          // 主框架导航一定经过原生 shouldOverrideUrlLoading；返回 true 即被消费，
          // 页面不会被真正跳走（即便万一未被消费也只是 ERR_UNKNOWN_URL_SCHEME，刷新可恢复）。
          try {
            window.location.href = 'dshbox://open-settings-document';
          } catch (e) { /* noop */ }
        }, true);
        document.addEventListener('click', function (ev) {
          if (!(ev.target instanceof Node)) return;
          var btn = ev.target.closest ? ev.target.closest('button') : null;
          if (!isOpenDocButton(btn)) return;
          ev.preventDefault();
          ev.stopPropagation();
          ev.stopImmediatePropagation();
        }, true);
      })(); } catch (e) { bootLog('open-doc-fail:' + (e && e.message || e)); }

      // =====================================================================
      // 14) 附件直达按钮（工具行「+」右侧）
      // =====================================================================
      // 背景：附件的唯一入口藏在「+」唤起的命令面板里（其中一项叫「文件」），要点两步。
      // 本段在工具行「+」右侧补一个**直达**按钮：外观取 dsh 工具行按钮的同一套规格
      // （回形针 Medium 图标 stroke 1.3 / 14px；28×28 圆角按钮，配色沿用 dsh 的主题
      // 变量，深浅主题自动跟随），点击行为与命令面板里的「文件」**完全一致** —— 上游
      // filePicker.open() 的实现就是 `fileInputRef.current.click()`，故本段也只做同一
      // 件事：点那个隐藏的 <input type="file">。其后链路（原生接管 → 来源面板 → 回填）
      // 与既有入口同一条，不新增分支。
      //
      // 键盘纪律：与 dsh 工具行按钮一致 —— mousedown 时 preventDefault，并在**手势前
      // composer 已聚焦**时把焦点交回编辑器（只「保持」不「建立」：若此刻本就没聚焦，
      // 主动聚焦会凭空唤起输入法）。**不**在页面侧收放键盘：打开来源面板时的收键盘由
      // 原生负责，页面侧重复处理会与它打架。
      //
      // 归属：这是**功能入口**而非布局适配件，但按钮本身是我们加的外观，故与汉堡、返回键
      // 同口径 —— 关掉「移动端适配」时一并撤掉（能力本身仍在 dsh 自己的命令面板里）。
      //
      // 挂载点：紧贴「+」之后（隐藏的 file input 无宽度，视觉上即「+」右边第一个图标）。
      // 这一簇 DOM 归 React 管理，重渲染会移除或挪动我们的节点，故用「判存 + 位置校验 +
      // 重挂」幂等范式（与段 2 汉堡同一做法）。
      try { (function installAttachButton() {
        var BTN_ID = 'dsh-mobile-attach';
        var STYLE_ID = 'dsh-mobile-attach-style';
        // 回形针路径逐字取自 dsh 的 IconPaperclipOutlineArtwork（viewBox 0 0 16 16），
        // 不做坐标换算；svg 上只带 fill/stroke-width，path 上只带 d/stroke —— 与上游
        // 的写法逐项对齐（上游未设 linecap/linejoin，此处也不设）。
        var PAPERCLIP_D = 'M12.75 4.5V9.5C12.75 10.7598 12.2496 11.968 11.3588 12.8588' +
          'C10.468 13.7496 9.25978 14.25 8 14.25C6.74022 14.25 5.53204 13.7496 4.64124 12.8588' +
          'C3.75045 11.968 3.25 10.7598 3.25 9.5V5C3.25 4.13805 3.59241 3.3114 4.2019 2.7019' +
          'C4.8114 2.09241 5.63805 1.75 6.5 1.75C7.36195 1.75 8.1886 2.09241 8.7981 2.7019' +
          'C9.40759 3.3114 9.75 4.13805 9.75 5V9.5C9.75 9.96413 9.56563 10.4092 9.23744 10.7374' +
          'C8.90925 11.0656 8.46413 11.25 8 11.25C7.53587 11.25 7.09075 11.0656 6.76256 10.7374' +
          'C6.43437 10.4092 6.25 9.96413 6.25 9.5V5.5';

        // 重入保护：本段可能被求值两次（同页面从旧 bundle 热切换）。上一次实例的定时器与
        // 观察器不随节点一起消失，两个实例会互相踩位 —— 各自发现「按钮不在锚点上」→ 反复
        // 重建，表现为 1Hz 乒乓与两个同 id 按钮。故上一次实例的收尾挂在 document 上，本段
        // 开头先执行一次（与段 0c 的幂等闸同式）。
        try {
          if (typeof document.__dshboxAttachTeardown === 'function') document.__dshboxAttachTeardown();
        } catch (e) { /* 上一次收尾失败也要继续装本次 */ }

        // 再清一次残留节点与样式：上一次实例可能没跑完收尾，或来自更早的 bundle。
        // 保证本次的监听与样式都是新的（与段 1 清遗留开关胶囊、段 4 清遗留返回键同一做法）。
        try {
          var staleBtn = document.getElementById(BTN_ID);
          if (staleBtn && staleBtn.parentNode) staleBtn.parentNode.removeChild(staleBtn);
          var staleStyle = document.getElementById(STYLE_ID);
          if (staleStyle && staleStyle.parentNode) staleStyle.parentNode.removeChild(staleStyle);
        } catch (e) { /* 清不掉也不影响后续重建 */ }

        function attachCopy() {
          var l = (document.documentElement.getAttribute('lang') || navigator.language || 'en').toLowerCase();
          if (l.indexOf('zh') === 0) return { aria: '添加附件' };
          return { aria: 'Add attachment' };
        }

        function ensureAttachStyle() {
          if (document.getElementById(STYLE_ID) !== null) return;
          var s = document.createElement('style');
          s.id = STYLE_ID;
          s.textContent =
            '#' + BTN_ID + '{corner-shape:round;background:var(--dsw-specific-selector);' +
            'width:28px;height:28px;color:var(--dsw-alias-label-primary);cursor:pointer;' +
            'border:none;border-radius:999px;flex:none;place-items:center;display:grid;padding:0;' +
            '-webkit-tap-highlight-color:transparent;touch-action:manipulation}' +
            '#' + BTN_ID + ':hover:not(:disabled){background:var(--dsw-alias-interactive-bg-hover-solid)}' +
            '#' + BTN_ID + ':disabled{opacity:.5;cursor:default}';
          document.head.appendChild(s);
        }

        /**
         * composer 的隐藏 file input：**必须**位于 composer 卡片内，且**必须**紧跟在
         * 一个按钮之后（那个按钮就是「+」）。不做全文档回退 —— 页面别处若也有 file input，
         * 回退会把按钮插到无关按钮旁边、点了还会唤起无关选择器。
         */
        function composerFileInput() {
          var card = document.querySelector('[data-composer-card]');
          if (!card) return null;
          var list = card.querySelectorAll('input[type="file"]');
          for (var i = 0; i < list.length; i++) {
            var prev = list[i].previousElementSibling;
            if (prev !== null && prev.tagName === 'BUTTON') return list[i];
          }
          return null;
        }

        /** 「+」按钮 = 隐藏 file input 的前一个兄弟：结构锚点，不依赖 dsh 的哈希类名。 */
        function plusButton() {
          var input = composerFileInput();
          var prev = input ? input.previousElementSibling : null;
          return prev !== null && prev.tagName === 'BUTTON' ? prev : null;
        }

        function keepDraftFocus(ev) {
          try {
            ev.preventDefault();
            var host = document.querySelector('[data-composer-input]');
            if (!host) return;
            // 只「保持」不「建立」：手势前 composer 未聚焦时直接放行，否则本按钮会凭空
            // 唤起输入法（随后还要被原生收一次，表现为键盘闪一下）。
            var act = document.activeElement;
            if (!(act === host || (act !== null && host.contains && host.contains(act)))) return;
            var editor = host.__lexicalEditor;
            if (editor && typeof editor.focus === 'function') editor.focus();
            else if (typeof host.focus === 'function') host.focus({ preventScroll: true });
          } catch (e) { /* 焦点收不回也不影响本次点击 */ }
        }

        function buildAttachButton() {
          var b = document.createElement('button');
          b.id = BTN_ID;
          b.type = 'button';
          b.setAttribute('aria-label', attachCopy().aria);
          b.innerHTML = '<svg viewBox="0 0 16 16" width="14" height="14" fill="none" ' +
            'xmlns="http://www.w3.org/2000/svg" aria-hidden="true" focusable="false" ' +
            'stroke-width="1.3"><path d="' + PAPERCLIP_D + '" stroke="currentColor"/></svg>';
          b.addEventListener('mousedown', keepDraftFocus);
          // 点按与悬停前刷新一次可用态：1Hz 复查最坏有 1s 滞后，而这段时间里点下去会打开
          // 选择器、dsh 却可能静默丢弃结果（表现为「选了没反应」）。
          // 注意这里只刷新状态、**不做**完整同步 —— 完整同步在位置校验失败时会重建节点，
          // 那会在手势中途换掉元素、令本次 click 不再触发。
          b.addEventListener('pointerenter', function () { refreshAttachState(); });
          b.addEventListener('pointerdown', function () { refreshAttachState(); });
          b.addEventListener('click', function (ev) {
            try {
              ev.preventDefault();
              // 必须阻止冒泡：未选工作区时整个 composer 卡片是「选工作区」的点击区，
              // 事件冒上去会被它当成一次卡片点击而弹出工作区选择器。
              ev.stopPropagation();
              var input = composerFileInput();
              if (input && !input.disabled) input.click();
            } catch (e) { /* noop */ }
          });
          return b;
        }

        var attachBtn = null;
        var lastMissReason = '';
        /** 缺锚诊断：同一个原因只记一次面包屑，避免刷屏又能事后定位。 */
        function logMissOnce(reason) {
          if (lastMissReason === reason) return;
          lastMissReason = reason;
          bootLog('attach-btn-anchor:' + reason);
        }

        function dropAttachButton() {
          try {
            if (attachBtn && attachBtn.parentNode) attachBtn.parentNode.removeChild(attachBtn);
          } catch (e) { /* noop */ }
          attachBtn = null;
        }

        /** 可用性跟随「+」与隐藏 input：dsh 在锁定 / 命令面板未绑定 / 子代理等状态下会
         *  禁用它们，此时它自己也不接受上传（选中的文件会被静默丢弃），故一并置灰。 */
        function applyAttachState(plus, input) {
          if (attachBtn === null) return;
          attachBtn.disabled = plus.disabled === true || input.disabled === true;
          var aria = attachCopy().aria;
          if (attachBtn.getAttribute('aria-label') !== aria) attachBtn.setAttribute('aria-label', aria);
        }

        /** 只刷新可用态、不动 DOM：供交互前调用（重建节点会打断进行中的点击手势）。 */
        function refreshAttachState() {
          try {
            if (attachBtn === null || !attachBtn.isConnected) return;
            var plus = plusButton();
            var input = composerFileInput();
            if (!plus || !input) return;
            applyAttachState(plus, input);
          } catch (e) { /* noop */ }
        }

        function syncAttachButton() {
          try {
            // 关掉「移动端适配」= 回到官方原版外观：本按钮是我们加的外观，一并撤掉。
            if (enabled !== true) { dropAttachButton(); return; }
            ensureAttachStyle();
            var plus = plusButton();
            var input = composerFileInput();
            if (!plus || !input || !plus.parentNode) {
              // 锚点缺失（结构变更 / composer 未挂载）：不插、不报错，只记一次面包屑。
              // 记「缺锚」而不是细分原因：两个判据同源（plus 由 input 派生），细分只会误标。
              dropAttachButton();
              logMissOnce('anchor-missing');
              return;
            }
            // 判存 + 位置校验：React 重渲染既可能移除我们的节点，也可能把它挪到别处。
            if (attachBtn === null || !attachBtn.isConnected ||
              attachBtn.previousElementSibling !== plus || attachBtn.nextElementSibling !== input) {
              dropAttachButton();
              attachBtn = buildAttachButton();
              plus.parentNode.insertBefore(attachBtn, input);
            }
            applyAttachState(plus, input);
          } catch (e) { bootLog('attach-btn-fail:' + (e && e.message || e)); }
        }

        syncAttachButton();
        var attachTimer = setInterval(syncAttachButton, 1000);
        // 工具栏归 React 管理，重渲染会移除或挪动我们的节点：观察文档子树，发现异常立刻补回。
        // 回调先做廉价判存（连接性 + 前一个兄弟仍是按钮），再做 250ms 节流 —— 否则每次敲键
        // 都会走一次完整同步（含全文档查询）。
        var lastObserveSync = 0;
        var attachObserver = null;
        try {
          attachObserver = new MutationObserver(function () {
            if (attachBtn !== null && attachBtn.isConnected) {
              var prev = attachBtn.previousElementSibling;
              if (prev !== null && prev.tagName === 'BUTTON') return;
            }
            var now = Date.now();
            if (now - lastObserveSync < 250) return;
            lastObserveSync = now;
            syncAttachButton();
          });
          attachObserver.observe(document.documentElement, { childList: true, subtree: true });
        } catch (e) { /* 观察器装不上时退化为每秒复查 */ }

        // 供下一次求值（同页面热切换）先停掉本实例，见段首「重入保护」。
        document.__dshboxAttachTeardown = function () {
          try { clearInterval(attachTimer); } catch (e) { /* noop */ }
          try { if (attachObserver !== null) attachObserver.disconnect(); } catch (e) { /* noop */ }
          dropAttachButton();
        };
      })(); } catch (e) { bootLog('attach-btn-install-fail:' + (e && e.message || e)); }

    };

    return module.exports;
  },
});
