/**
 * Browser half of @local/dsh-mobile-adapt.
 *
 * DSH 0.2.2 自带窄屏布局（视口 < 1024 自动折叠侧栏、< 768 侧栏自动变抽屉，
 * layout 模块内已有成套 `[data-sidebar-drawer]` 样式）。本插件**不再干预页面
 * 布局**：不覆盖 grid 模板、不自绘 fixed 抽屉/汉堡/遮罩、不做两步式设置导航。
 * 现在只留三件事：
 *   ① 键盘相关（焦点抑制、键盘顶起补偿、Gecko 顶栏钉住）
 *   ② 返回键分层（读**原生**状态：抽屉属性 / 设置 dialog / 设置子页返回控件）
 *   ③ 设置「通用」页里的「移动端适配」开关行 + 附件直达入口
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

    // =====================================================================
    // 0d) 设置「通用」面板里的「移动端适配」开关行
    // =====================================================================
    // DSH 0.2.2 的设置面板（dsh-client-ui-settings-general 的 SettingsRoot）：
    //   div[role="dialog"][aria-modal="true"]
    //     > div.navTitle / nav.nav（分类导航）/ div.header（内含原生关闭按钮）
    //     / div.options（内容区）
    // 通用页最后一行是「会话日志上传」开关（dsh-client-ui-settings-session-log
    // 的 UploadRow），结构：
    //   div[class*="_row"] > div > div[class*="_title"] + div[class*="_description"]
    //   …… 右侧是 primitives.Switch：button[role="switch"] > span
    // 本插件**克隆那一行**（连同 switch 按钮，样式与原生完全一致）插到它下面，
    // 只换文案与 aria-checked —— 不自造控件，也就不会与原生样式打架。
    //
    // 锚点匹配：先按已知标题文案表（DSH 各语言）精确匹配；失配时回退到该面板
    // 最后一个带 role="switch" 的行；两者都找不到就不注入（只记 bootLog，不抛）。
    //
    // 开关行由插件注入，**不受 enabled 影响**：关掉适配后刷新页面，这一行仍在，
    // 用户可以从「关」状态恢复（enabled 只控制适配逻辑与样式）。
    var MOBILE_ROW_ATTR = 'data-dsh-mobile-row';
    // 标题文案表：取自 @deepseek-ai/dsh-client-ui-settings-session-log 0.2.2 的
    // locales.js（en / zh / ja / es / fr / de / ru / pt 八种语言）。
    var SESSION_LOG_TITLES = [
      'Upload Session Log when using the official model API',
      '在使用官方模型 API 时上传 Session Log',
      '公式モデル API 使用時にセッションログをアップロード',
      'Cargar registro de sesión cuando utilice el modelo oficial API',
      'Téléchargez le journal de session lors de l\'utilisation du modèle officiel API',
      'Laden Sie das Sitzungsprotokoll hoch, wenn Sie das offizielle Modell API verwenden',
      'Загрузить журнал сеансов при использовании официальной модели API',
      'Carregar log de sessão ao usar o modelo oficial API'
    ];

    /** DSH 当前语言。DSH 会写 documentElement.lang（见 dsh-client-locale），
     *  读不到时退回浏览器语言。 */
    function currentLang() {
      try {
        var l = document.documentElement.getAttribute('lang') || '';
        if (!l) l = navigator.language || '';
        return String(l).toLowerCase();
      } catch (e) { return 'en'; }
    }

    /** 文案：跟随 DSH 当前语言，至少 zh/en，其它语言回退英文。
     *  说明写的是「关掉这个开关会怎样」——用户最容易忽略的正是关掉后的后果，
     *  故把键盘自动弹出、Gecko 顶栏被顶出屏幕、返回键不再收抽屉/关设置
     *  三条警告都点出来。 */
    function mobileCopy() {
      var l = currentLang();
      if (l.indexOf('zh') === 0) {
        return {
          title: 'DSH 页面交互适配',
          desc: '关闭后：切换对话或打开菜单时键盘可能自动弹出，输入区可能被键盘遮挡；Gecko 版打字时会话顶栏会被顶出屏幕；返回键不再优先收起抽屉/关闭设置。',
          refresh: '请刷新页面'
        };
      }
      return {
        title: 'DSH page interaction adaptation',
        desc: 'When off: the keyboard may pop up when switching chats or opening menus and cover the input; on Gecko the session header is pushed off-screen while typing; the back key no longer closes the drawer or settings first.',
        refresh: 'Please refresh the page'
      };
    }

    /** 文本归一化：折叠空白、去首尾，供标题文案比对。 */
    function normalizeText(t) {
      return String(t === null || t === undefined ? '' : t).replace(/\s+/g, ' ').trim();
    }

    /** 设置面板本体：**带 NAV 直接子节点**的 dialog。
     *  JS 探测不用 :has()：目标内核的 :has() 失效缓存有 bug —— 面板卸载后
     *  querySelector(':has(> nav)') 仍可能返回已脱离文档的 dialog。用
     *  querySelectorAll 纯属性匹配再逐个检查直接子节点 tagName==='NAV'，
     *  语义与 :has(> nav) 完全等价。
     *
     *  ⚠️ 必须排除**原生抽屉侧栏**（0.2.2 的 SidebarRoot）：它同样是
     *  role="dialog" + aria-modal="true"（drawer 展开时），而且**直接子节点含
     *  <nav class="panelList">**（随包 profile 的插件/定时任务面板必渲染）——
     *  只按「有 NAV 子节点」判，会把抽屉误认成设置面板，后果有两处：
     *    ① 返回键走 closeSettings(抽屉) → 兜底点抽屉 foot 里的设置触发键 →
     *       **按下返回反而打开设置**，哨兵还会补压，越按越不收敛；
     *    ② 开关行会去抽屉里找锚点（findSessionLogRow 返回 null）→ 窄屏下抽屉
     *       常驻 aria-modal=true，「移动端适配」开关行永远不注入。
     *  判据用官方标记：设置面板带 data-shortcut-modal="settings"，抽屉带
     *  data-shortcut-modal="sidebar"（见 settingsBackButton 的同一守卫）。 */
    function settingsDialog() {
      try {
        var list = document.querySelectorAll('[role="dialog"][aria-modal="true"]');
        var fallback = null;
        for (var i = 0; i < list.length; i++) {
          var dlg = list[i];
          var modal = dlg.getAttribute('data-shortcut-modal');
          if (modal === 'sidebar') continue; // 原生抽屉侧栏，不是设置面板
          if (modal === 'settings') return dlg; // 官方标记，优先认它
          var kids = dlg.children;
          for (var k = 0; k < kids.length; k++) {
            if (kids[k].tagName === 'NAV') {
              if (fallback === null) fallback = dlg;
              break;
            }
          }
        }
        // 无官方标记的版本：退回到「有 NAV 子节点且不是抽屉」的那一个。
        if (fallback !== null) return fallback;
      } catch (e) { /* noop */ }
      return null;
    }

    /** 原生抽屉所在容器：DSH 在 frame 上挂 data-sidebar-drawer / data-sidebar-collapsed。 */
    function sidebarFrame() {
      try {
        return document.querySelector('[data-sidebar-drawer]') ||
          document.querySelector('[data-sidebar-collapsed]') ||
          document.querySelector('[class*="frame"]');
      } catch (e) { return null; }
    }

    /** 原生抽屉是否展开。
     *  依据（DSH 0.2.2 dsh-client-ui-layout 的 AppFrame）：
     *    · 视口 < 768 且非 windows-titlebar 时 frame 带 data-sidebar-drawer；
     *    · sidebarCollapsed = narrow ? !narrowExpanded : sidebar === 0，
     *      折叠时 frame 带 data-sidebar-collapsed。
     *  故「抽屉形态且未折叠」= 抽屉展开。
     *  宽屏轨道形态（无 data-sidebar-drawer）不算抽屉层 —— 返回键不吞它。 */
    function drawerOpen() {
      var f = sidebarFrame();
      if (!f) return false;
      if (!f.hasAttribute('data-sidebar-drawer')) return false;
      return !f.hasAttribute('data-sidebar-collapsed');
    }

    /** 返回控件识别：CSS Modules 类名里的 "back" 子串，或标签文案里的「返回/Back」。
     *  类名判定用「两侧不是字母」的边界，免得 backdrop 之类误判。 */
    var BACK_LABEL_RE = /(返回|回到|back|戻る|volver|zurück|retour|назад)/i;
    function backButtonWithin(root) {
      if (!root) return null;
      var btns;
      try { btns = root.querySelectorAll('button, [role="button"]'); } catch (e) { return null; }
      for (var i = 0; i < btns.length; i++) {
        var b = btns[i];
        var cls = b.getAttribute('class') || '';
        if (/(^|[^a-z])back([^a-z]|$)/i.test(cls)) return b;
        var label = b.getAttribute('aria-label') || b.getAttribute('title') || normalizeText(b.textContent);
        // 标签过长多半是正文段落而不是返回控件，放弃匹配。
        if (label && label.length <= 40 && BACK_LABEL_RE.test(label)) return b;
      }
      return null;
    }

    /** 设置二级页的原生返回控件。
     *  ① 设置面板内部（子页在 options 区里渲染）的返回箭头/面包屑；
     *  ② 叠在设置面板之上的原生二级浮层（如账号页的 PlatformOverlay，
     *     它是 portal 出来的独立 dialog，自带 IconChevronLeft 返回按钮）。
     *  都没有 → null（视为设置面板一级，返回键直接关面板）。
     *  守卫与 settingsDialog 一致：原生抽屉侧栏（data-shortcut-modal="sidebar"）
     *  既不是设置面板、也不是设置子页。 */
    function settingsBackButton(dlg) {
      if (!dlg) return null;
      var b = backButtonWithin(dlg);
      if (b) return b;
      try {
        var overlays = document.querySelectorAll('[role="dialog"][aria-modal="true"]');
        for (var i = 0; i < overlays.length; i++) {
          if (overlays[i] === dlg) continue;
          // 原生抽屉侧栏也是 dialog，但它不是设置子页，跳过。
          if (overlays[i].getAttribute('data-shortcut-modal') === 'sidebar') continue;
          var ob = backButtonWithin(overlays[i]);
          if (ob) return ob;
        }
      } catch (e) { /* noop */ }
      return null;
    }

    /** 设置面板的原生关闭按钮（0.2.2：div.header 里 class 含 close 的 button）。 */
    function nativeCloseButton(dlg) {
      if (!dlg) return null;
      var header = null;
      try { header = dlg.querySelector(':scope > [class*="header"]'); } catch (e) { header = null; }
      if (!header) { try { header = dlg.querySelector('[class*="header"]'); } catch (e) { header = null; } }
      var scope = header || dlg;
      var btns;
      try { btns = scope.querySelectorAll('button'); } catch (e) { return null; }
      for (var i = 0; i < btns.length; i++) {
        var cls = btns[i].getAttribute('class') || '';
        if (cls.indexOf('close') >= 0) return btns[i];
      }
      return btns.length ? btns[btns.length - 1] : null;
    }

    /** 关闭设置面板：走 DSH 原生 onClose（点它的关闭按钮），不改 DSH 源码。 */
    function closeSettings(dlg) {
      var d = dlg || settingsDialog();
      if (!d) return false;
      var btn = nativeCloseButton(d);
      if (btn) {
        try { btn.click(); return true; } catch (e) { /* 落到下面的兜底 */ }
      }
      try {
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      } catch (e) { /* noop */ }
      return false;
    }

    /** 会话日志行（锚点）。
     *  ① 已知标题文案表精确匹配（DSH 各语言标题不同，按渲染后的实际文案比对）；
     *  ② 失配回退：该面板**最后一个**带 role="switch" 的行；
     *  ③ 都没有 → null（不注入）。 */
    function findSessionLogRow(dlg) {
      if (!dlg) return null;
      var list;
      try { list = dlg.querySelectorAll('div[class*="_row"]'); } catch (e) { return null; }
      var rows = [];
      for (var i = 0; i < list.length; i++) {
        var row = list[i];
        if (row.querySelector('div[class*="_title"]') === null) continue;
        if (row.querySelector('button[role="switch"]') === null) continue;
        rows.push(row);
      }
      if (rows.length === 0) return null;
      for (var r = 0; r < rows.length; r++) {
        var t = normalizeText(rows[r].querySelector('div[class*="_title"]').textContent);
        for (var k = 0; k < SESSION_LOG_TITLES.length; k++) {
          if (t === SESSION_LOG_TITLES[k]) return rows[r];
        }
      }
      return rows[rows.length - 1];
    }

    /** 已注入行的开关态同步。 */
    function syncMobileRowState(row, on) {
      try {
        if (!row) return;
        var sw = row.querySelector('button[role="switch"]');
        if (!sw) return;
        var want = on ? 'true' : 'false';
        if (sw.getAttribute('aria-checked') !== want) sw.setAttribute('aria-checked', want);
      } catch (e) { /* noop */ }
    }

    /** 移除已注入的行（面板关闭时清理）。 */
    function removeMobileRow() {
      try {
        var rows = document.querySelectorAll('[' + MOBILE_ROW_ATTR + ']');
        for (var i = 0; i < rows.length; i++) {
          if (rows[i].parentNode) rows[i].parentNode.removeChild(rows[i]);
        }
      } catch (e) { /* noop */ }
    }

    /** 幂等注入「移动端适配」开关行。
     *  opts: { isEnabled: function() → boolean, onToggle: function(next) }
     *  返回注入的行节点；未注入时返回 null。 */
    function ensureMobileRow(opts) {
      try {
        var dlg = settingsDialog();
        if (!dlg) { removeMobileRow(); return null; }
        var on = opts && typeof opts.isEnabled === 'function' ? !!opts.isEnabled() : false;
        // 已在位就不动（只同步开关态）—— 保证 observer 反复触发时零 DOM 变更。
        var existing = document.querySelector('[' + MOBILE_ROW_ATTR + ']');
        if (existing && existing.isConnected) {
          syncMobileRowState(existing, on);
          return existing;
        }
        var anchor = findSessionLogRow(dlg);
        if (!anchor) { bootLog('mobile-row:anchor-missing'); return null; }
        var copy = mobileCopy();
        var row = anchor.cloneNode(true);
        row.setAttribute(MOBILE_ROW_ATTR, '1');
        row.removeAttribute('id');
        var titleEl = row.querySelector('div[class*="_title"]');
        var descEl = row.querySelector('div[class*="_description"]');
        var sw = row.querySelector('button[role="switch"]');
        if (!titleEl || !sw) { bootLog('mobile-row:clone-incomplete'); return null; }
        titleEl.textContent = copy.title;
        if (descEl) descEl.textContent = copy.desc;
        // 原生那一行的 switch 可能因上传未就绪而 disabled；我们的是本地开关，
        // 必须可点。
        sw.removeAttribute('disabled');
        sw.setAttribute('aria-label', copy.title);
        sw.setAttribute('title', copy.title);
        syncMobileRowState(row, on);
        sw.addEventListener('click', function (ev) {
          try {
            if (ev && typeof ev.preventDefault === 'function') ev.preventDefault();
            if (ev && typeof ev.stopPropagation === 'function') ev.stopPropagation();
            var cur = opts && typeof opts.isEnabled === 'function' ? !!opts.isEnabled() : false;
            if (opts && typeof opts.onToggle === 'function') opts.onToggle(!cur);
          } catch (e) { bootLog('mobile-row:toggle-fail:' + (e && e.message || e)); }
        });
        var parent = anchor.parentNode;
        if (!parent) { bootLog('mobile-row:no-parent'); return null; }
        parent.insertBefore(row, anchor.nextSibling);
        return row;
      } catch (e) {
        bootLog('mobile-row:fail:' + (e && e.message || e));
        return null;
      }
    }

    // 「请刷新页面」悬浮提示：全行内样式，不依赖 MOBILE_CSS。
    var adaptToastEl = null;
    function showAdaptToast() {
      try {
        var copy = mobileCopy();
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

    /** 当前最上层可退层（自上而下）：设置二级页 > 设置弹窗 > 原生抽屉。
     *  顺序依据：设置面板是**覆盖在抽屉之上**的浮层，先关它才不会出现
     *  「按了没反应」（抽屉在浮层背后，收它用户看不见变化）。
     *  都没有 → null（放行给宿主，不吞键）。 */
    function topLayerKind() {
      var dlg = settingsDialog();
      if (dlg) return settingsBackButton(dlg) ? 'settings-sub' : 'settings';
      if (drawerOpen()) return 'drawer';
      return null;
    }

    // =====================================================================
    // 移动端适配样式表。
    // =====================================================================
    // 0.2.2 已自带窄屏布局（视口 < 1024 自动折叠、< 768 侧栏自动变抽屉，
    // layout 内已有成套 [data-sidebar-drawer] 样式），插件自绘的布局覆盖
    // （grid 模板、fixed 全屏抽屉、#root 高度、handle 隐藏、rightbar 轨道
    // 钉死、汉堡、遮罩、两步式设置）与之冲突，表现为「页面拉扯」，已全部删除。
    // 这里只留**不与原生布局打架**的触控友好微调。
    var MOBILE_CSS = [
      '/* === dsh-mobile-adapt: 触控友好微调（不干预 DSH 原生窄屏布局） === */',
      '',
      '/* 注入行（data-dsh-mobile-row）的说明文字较长（键盘/返回键/Gecko 顶栏三条警告），',
      '   必须整段换行显示：若沿用原生的单行截断样式，长文案会被截掉或省略成「…」，',
      '   用户就看不到「关掉后会怎样」。故只对注入行放开换行，不动原生行的样式。 */',
      '[data-dsh-mobile-row] div[class*="_description"] {',
      '  white-space: normal !important;',
      '  overflow: visible !important;',
      '  text-overflow: clip !important;',
      '}',
      '',
      '@media (max-width: 1024px) {',
      '  /* 输入框字号 16px：低于 16px 会触发移动端浏览器自动放大页面 */',
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

    // =====================================================================
    // 0a) Gecko 键盘顶栏钉住（仅 Gecko；System WebView 行为已正常，零动作）。
    // =====================================================================
    // 背景（见 docs/DSH-GeckoView双APK架构方案.md 待办项）：Gecko 把软键盘当布局
    //   视口变化（reflow + dvh），会把 DSH 会话顶栏（"对话/轨迹"横幅）整体顶出屏幕；
    //   System WebView（固定 Chrome/128 UA）只缩 visual viewport，顶栏正常。宿主侧
    //   四条方案（视图收缩 / 仅 clipping / maxHeight 只增不减 / maxHeight 跟随归零）
    //   已全部真机证伪，根治点只能在页面运行时。
    // 约束：
    //   ① 严格按 UA 判定仅 Gecko 启用；System WebView 分支零 DOM 写入、零监听。
    //   ② 插件开关（enabled）关闭时一并失效并还原顶栏。
    //   ③ 幂等：重复执行不叠加 visualViewport/window 监听。
    //   ④ 与既有 composer 上移逻辑（installKeyboardFix）共存、互不触碰对方 DOM。
    //   ⑤ 钉住前保存顶栏内联样式原值，键盘收起/开关关闭时精确还原，不硬编码覆盖
    //      DSH 自身样式；顶栏被 React 重建（元素换掉）时先还原旧元素再钉新元素。
    /** 是否 Gecko 内核（GeckoView 内置固定 Release）。
     *   判定用 UA 里的 Firefox/ 构建标识：GeckoView 的 UA 形如
     *   "... rv:158.0) Gecko/158.0 Firefox/158.0"，必含 Firefox/；而 System WebView
     *   固定为 Chromium UA（"... (KHTML, like Gecko) Chrome/128.0.0.0 ..."），只含
     *   无害的 "like Gecko" 字样、绝无 Firefox/，故返回 false → 后续零动作。
     *   注意：不能用裸 "Gecko" 匹配，否则会把 Chromium UA 里的 "like Gecko" 误判为 Gecko。 */
    function isGecko() {
      try { return /Firefox\//i.test(navigator.userAgent || ''); } catch (e) { return false; }
    }
    /** 定位 DSH 会话顶栏（"对话/轨迹"横幅所在元素）。
     *   不用哈希类名（CSS Modules 每次构建都变），只用结构：centerCol 内的 <header>
     *   后代。找不到 <header> 时返回 null（静默不钉）；不做 firstElementChild
     *   兜底——centerCol 首位子元素可能是消息列表，误钉会把整个会话区变成 fixed。 */
    function findTopBar() {
      var col = document.querySelector('[class*="centerCol"]');
      if (!col) return null;
      return col.querySelector('header') || null;
    }
    /** Reserve keyboard space without displacing a pinned header.  Gecko leaves
     *   the layout viewport static while shrinking visualViewport; translating
     *   the header then overlaps scrolled text, so add transparent padding to the
     *   actual conversation scroller. */
    function pinSpace(height) {
      var scroller = document.querySelector('[class*="scrollBody"]');
      if (!scroller) return;
      if (height > 0) {
        scroller.style.boxSizing = 'border-box';
        scroller.style.paddingBottom = Math.max(0, Math.round(height)) + 'px';
      } else {
        scroller.style.paddingBottom = '';
        scroller.style.boxSizing = '';
      }
    }
    /** Return only the visual viewport area not already represented by its pan.
     *   Gecko may resize the layout viewport, pan the visual viewport, or do both.
     *   Adding the full height difference in the pan case reserves the keyboard
     *   twice and creates a trailing blank area. */
    function keyboardReservedPadding(innerHeight, visualHeight, visualOffsetTop) {
      var viewportHeight = Number(innerHeight);
      var visibleHeight = Number(visualHeight);
      var panOffset = Number(visualOffsetTop);
      if (!isFinite(viewportHeight) || !isFinite(visibleHeight) ||
          !isFinite(panOffset) || viewportHeight <= 0 || visibleHeight <= 0) {
        return 0;
      }
      var layoutShrink = Math.max(0, viewportHeight - visibleHeight);
      return Math.max(0, Math.round(layoutShrink - Math.max(0, panOffset)));
    }
    /** Gecko 下把顶栏钉在 visualViewport 顶部。getEnabled 取实时开关态（apply 传入
     *   function(){ return enabled; }），便于开关关闭时即时还原。 */
    function installGeckoTopBarPin(getEnabled) {
      // 幂等闸：本次页面生命周期只装一次，重复求值/重试不叠加监听。
      if (window.__dshGeckoTopBarPin === true) return;
      window.__dshGeckoTopBarPin = true;
      // System WebView：严格零动作（不挂监听、不写 DOM）。函数直接返回，但保留幂等
      // 标记避免后续 Gecko 场景在「同 UA 不变」的前提下重复进入。
      if (!isGecko()) return;

      var vv = window.visualViewport;
      var bar = null;     // 当前被钉住的顶栏元素
      var saved = null;   // 钉住前的内联样式快照
      var PINNED = false;
      var composerFocusActive = false;
      var scheduled = false;
      var headerObserver = null;
      var observedHeaderParent = null;

      function saveOriginal(el) {
        return {
          position: el.style.position || '',
          top: el.style.top || '',
          left: el.style.left || '',
          right: el.style.right || '',
          bottom: el.style.bottom || '',
          zIndex: el.style.zIndex || '',
          width: el.style.width || '',
          maxWidth: el.style.maxWidth || '',
          backgroundColor: el.style.backgroundColor || '',
          backdropFilter: el.style.backdropFilter || '',
          transform: el.style.transform || '',
          willChange: el.style.willChange || '',
        };
      }
      // fixed 会把顶栏从原层叠父容器里摘出来：父级/backdrop 提供的背景随之消失，
      // 下方滚动内容就会透过来（Gecko 特有观感）。解析一个不透明底色补上：
      // 先看顶栏自身，再沿祖先链找第一个不透明背景，最后回落到根/body。
      function parseAlpha(color) {
        var m = /^rgba?\(([^)]+)\)$/i.exec(color || '');
        if (!m) return 1;
        var parts = m[1].split(',');
        if (parts.length < 4) return 1;
        var a = parseFloat(parts[3]);
        return isFinite(a) ? a : 1;
      }
      function resolveOpaqueBackground(el) {
        var gcs = function (node) {
          try {
            return window.getComputedStyle ? window.getComputedStyle(node) : null;
          } catch (e) { return null; }
        };
        var node = el;
        while (node && node !== document.documentElement) {
          var st = gcs(node);
          var bg = (st && st.backgroundColor) || '';
          if (bg && bg !== 'transparent' && parseAlpha(bg) >= 1) return bg;
          node = node.parentElement;
        }
        var rootStyle = gcs(document.documentElement);
        var rootBg = (rootStyle && rootStyle.backgroundColor) || '';
        if (rootBg && rootBg !== 'transparent' && parseAlpha(rootBg) >= 1) return rootBg;
        if (document.body) {
          var bodyBg = (gcs(document.body) && gcs(document.body).backgroundColor) || '';
          if (bodyBg && bodyBg !== 'transparent' && parseAlpha(bodyBg) >= 1) return bodyBg;
        }
        return '#ffffff';
      }
      function currentTranslateY(el) {
        var m = /translateY\((-?[\d.]+)px\)/.exec(el.style.transform || '');
        return m ? parseFloat(m[1]) : 0;
      }
      // 键盘弹起后顶栏随页面 reflow 被滚出：用 transform 把它视觉上平移回
      // 可视区顶部。不脱离文档流、不改 position/z-index、不强制合成层
      // （fixed+translateZ+will-change 在 Gecko/TextureView 下会引出侧栏
      // 状态不一致与页面卡死）。rect.top 已含当前 transform，需先扣掉。
      function applyPinPosition(el) {
        var ty = currentTranslateY(el);
        var naturalTop = el.getBoundingClientRect().top - ty;
        var target = naturalTop < 0 ? -naturalTop : 0;
        if (target === 0 && vv) {
          var visualOffset = typeof vv.offsetTop === 'number' ? vv.offsetTop : 0;
          if (visualOffset > naturalTop) target = visualOffset - naturalTop;
        }
        var next = target > 0 ? ('translateY(' + target + 'px)') : '';
        if ((el.style.transform || '') !== next) el.style.transform = next;
      }
      function pin(el) {
        saved = saveOriginal(el);
        // 顶栏平移后下方内容会滚到其视觉区域下层，补一个从原样式链解析出的
        // 不透明底色，避免透字。
        el.style.backgroundColor = resolveOpaqueBackground(el);
        applyPinPosition(el);
        PINNED = true;
        bar = el;
        window.__dsh_kb_topbar = el;
      }
      function restore() {
        pinSpace(0);
        if (headerObserver) {
          try { headerObserver.disconnect(); } catch (e) {}
          headerObserver = null;
          observedHeaderParent = null;
        }
        if (!bar || !saved) { PINNED = false; bar = null; return; }
        var el = bar;
        el.style.position = saved.position;
        el.style.top = saved.top;
        el.style.left = saved.left;
        el.style.right = saved.right;
        el.style.bottom = saved.bottom;
        el.style.zIndex = saved.zIndex;
        el.style.width = saved.width;
        el.style.maxWidth = saved.maxWidth;
        el.style.backgroundColor = saved.backgroundColor;
        el.style.backdropFilter = saved.backdropFilter;
        el.style.transform = saved.transform;
        el.style.willChange = saved.willChange;
        bar = null; saved = null; PINNED = false;
        window.__dsh_kb_topbar = null;
      }
      // 暴露给 setEnabled：开关关闭时即时还原顶栏（即便键盘此刻正弹起）。
      window.__dsh_kb_topbar_restore = restore;

      function composerFocused() {
        try {
          var el = document.activeElement;
          return !!(el && typeof el.closest === 'function' &&
            el.closest('[data-composer-input], [data-composer-card]'));
        } catch (e) { return false; }
      }

      function scheduleApply() {
        if (scheduled) return;
        scheduled = true;
        var run = function () {
          scheduled = false;
          apply();
        };
        if (typeof window.requestAnimationFrame === 'function') {
          window.requestAnimationFrame(run);
        } else {
          setTimeout(run, 0);
        }
      }

      function observeHeaderParent(el) {
        if (typeof window.MutationObserver !== 'function') return;
        var parent = el.parentElement || null;
        if (parent === observedHeaderParent && headerObserver) return;
        if (headerObserver) {
          try { headerObserver.disconnect(); } catch (e) {}
          headerObserver = null;
        }
        observedHeaderParent = parent;
        if (!parent) return;
        try {
          headerObserver = new window.MutationObserver(scheduleApply);
          headerObserver.observe(parent, { childList: true });
          window.__dsh_gecko_topbar_observer = headerObserver;
        } catch (e) {
          headerObserver = null;
          observedHeaderParent = null;
        }
      }

      function apply() {
        // 开关关闭：逻辑失效并还原（零 DOM 残留）。
        if (!getEnabled || !getEnabled()) { if (PINNED) restore(); return; }
        var el = findTopBar();
        if (!el) { if (PINNED) restore(); return; }
        observeHeaderParent(el);
        var vvH = (vv && vv.height) || 0;
        var innerHeight = window.innerHeight || 0;
        var visualOffsetTop = (vv && vv.offsetTop) || 0;
        var keyboardHeight = Math.max(0, Math.round(innerHeight - vvH));
        var viewportKeyboard = vvH > 0 && keyboardHeight > 1;
        var shouldPin = composerFocusActive || composerFocused() || viewportKeyboard;
        pinSpace(shouldPin
          ? keyboardReservedPadding(innerHeight, vvH, visualOffsetTop)
          : 0);
        if (shouldPin) {
          if (PINNED && bar === el) {
            // 已钉在当前顶栏：随可视视口变化重新计算位移。
            applyPinPosition(el);
          } else {
            if (PINNED && bar !== el) restore(); // 顶栏被 React 换掉，先还原旧元素
            pin(el);
          }
        } else if (PINNED) {
          restore(); // 键盘收起：还原为 DSH 原始样式
        }
      }

      if (vv) {
        vv.addEventListener('resize', scheduleApply);
        vv.addEventListener('scroll', scheduleApply);
      }
      // 兜底：部分内核 layout 视口 resize 与 vv 事件顺序不一致时仍触发一次。
      window.addEventListener('resize', scheduleApply);
      window.addEventListener('scroll', scheduleApply, true);
      document.addEventListener('scroll', scheduleApply, true);
      document.addEventListener('focusin', function () {
        composerFocusActive = composerFocused();
        scheduleApply();
      }, true);
      document.addEventListener('focusout', function () {
        setTimeout(function () {
          composerFocusActive = composerFocused();
          scheduleApply();
        }, 0);
      }, true);
      // 挂载后跑一次（键盘已在弹起状态时也能立即钉住）。
      composerFocusActive = composerFocused();
      setTimeout(scheduleApply, 0);
    }
    // 暴露内部实现给 standalone 测试（载入即执行、不影响插件正常加载路径）。
    exports.__test = {
      isGecko: isGecko,
      findTopBar: findTopBar,
      installGeckoTopBarPin: installGeckoTopBarPin,
      pinSpace: pinSpace,
      keyboardReservedPadding: keyboardReservedPadding,
      // 开关行注入
      MOBILE_ROW_ATTR: MOBILE_ROW_ATTR,
      SESSION_LOG_TITLES: SESSION_LOG_TITLES,
      currentLang: currentLang,
      mobileCopy: mobileCopy,
      normalizeText: normalizeText,
      findSessionLogRow: findSessionLogRow,
      ensureMobileRow: ensureMobileRow,
      removeMobileRow: removeMobileRow,
      syncMobileRowState: syncMobileRowState,
      // 返回键分层（读原生状态）
      settingsDialog: settingsDialog,
      sidebarFrame: sidebarFrame,
      drawerOpen: drawerOpen,
      settingsBackButton: settingsBackButton,
      backButtonWithin: backButtonWithin,
      nativeCloseButton: nativeCloseButton,
      closeSettings: closeSettings,
      topLayerKind: topLayerKind,
    };
    exports.apply = function apply(ctx) {
      if (!ctx || !ctx.layout) return;

      // body-ready 守卫：本插件脚本注入在 <head>，若模块系统抢在 <body> 解析
      // 完成前调用 apply()，段 1/3/4/5 的 document.body 操作会抛 TypeError 并
      // 连累其后所有段（表现为开关行与返回键一起消失、刷新又好）。
      // 未就绪则挂 DOMContentLoaded 重入一次；重入安全（所有创建均幂等：
      // 注入行判存 + __dsh_mobile_nav_bound 标志）。
      if (!document.body) {
        bootLog('apply-deferred(no-body)');
        document.addEventListener('DOMContentLoaded', function () {
          try { apply(ctx); } catch (e) { bootLog('apply-retry-fail:' + (e && e.message || e)); }
        }, { once: true });
        return;
      }
      bootLog('apply');

      // 1) 注入全局样式（幂等）。
      //    只保留**不与 DSH 0.2.2 原生窄屏布局冲突**的触控友好微调；布局类规则
      //    （grid 模板覆盖 / 固定全屏抽屉 / #root 高度 / handle 隐藏 / rightbar
      //    轨道钉死 / 汉堡 / 遮罩 / 两步式设置）已随 0.2.2 的原生窄屏布局删除。
      var style = document.querySelector('style[data-plugin="@local/dsh-mobile-adapt"]');
      if (style === null) {
        style = document.createElement('style');
        style.setAttribute('data-plugin', '@local/dsh-mobile-adapt');
        style.textContent = MOBILE_CSS;
        document.head.appendChild(style);
      }

      // 2) 适配开关状态（热切换、持久化）。
      //    localStorage 在部分 WebView 配置下会抛 SecurityError，裸读会杀死本段
      //    及其后全部段 —— 读失败按默认开处理，仅失去跨刷新持久化。
      var enabled = true;
      try { enabled = localStorage.getItem('dsh-mobile-enabled') !== '0'; } catch (e) { bootLog('ls-read-fail'); }

      function setEnabled(v) {
        enabled = !!v;
        try { localStorage.setItem('dsh-mobile-enabled', enabled ? '1' : '0'); } catch (e) { /* 存不下也要当场生效 */ }
        if (style) style.disabled = !enabled; // 触控微调随开关热切换
        // 键盘修复的 transform 清理
        if (window.__dsh_kb_lift) { window.__dsh_kb_lift.style.transform = ''; window.__dsh_kb_lift = null; }
        // Gecko 顶栏钉住清理：开关关闭时还原顶栏（即便键盘此刻正弹起）。
        if (typeof window.__dsh_kb_topbar_restore === 'function') {
          try { window.__dsh_kb_topbar_restore(); } catch (e) { /* noop */ }
        }
        // brand 文本随模式切换（当前为 no-op，见段 10 的说明）
        applyBrand();
        // 已注入的开关行同步开关态（可能有多个实例：面板重开未卸载时）
        syncMobileRows();
        // 关模式要确定性把已压的返回哨兵吃净（见段 7b）：不能只靠 toast 挂载产生的
        // 突变把对账"顺带"带起来——toast 整段在 try/catch 里，失败就漏吃。
        reconcileBackSentinels();
        // 悬浮提示"请刷新页面"：适配逻辑（键盘抑制等）在装载期安装，开关要刷新
        // 才是确定性生效路径。开/关两个方向都提示。
        showAdaptToast();
      }

      // =====================================================================
      // 3) 设置「通用」面板里的「移动端适配」开关行
      // =====================================================================
      // 0.2.2 的 navTitle 是网格项、内含省略号文本，塞内联按钮会偏移/被裁，故
      // 开关不再放在标题旁，改为克隆通用页的原生会话日志行插到它下面。
      // 注入节点带稳定标记 data-dsh-mobile-row，由 observer 幂等重插；面板关闭
      // 时清理。
      function syncMobileRows() {
        try {
          var rows = document.querySelectorAll('[' + MOBILE_ROW_ATTR + ']');
          for (var i = 0; i < rows.length; i++) syncMobileRowState(rows[i], enabled);
        } catch (e) { /* noop */ }
      }
      var rowOps = {
        isEnabled: function () { return enabled; },
        onToggle: function (next) { setEnabled(next); }
      };
      // 面板可能在插件装载前就已打开（热切换/刷新竞态）：先补一次。
      ensureMobileRow(rowOps);

      // =====================================================================
      // 4) 返回键层级：哨兵历史条目 + popstate 分发
      // =====================================================================
      // DSH 前端不使用浏览历史（无 pushState、无路由），页面内各级浮层也不是历史
      // 条目，于是原生返回键的 goBack() 在 DSH 内部无处可退。这里在"打开一层"时
      // 向历史栈压一条哨兵条目：原生返回键弹出它时本页收到 popstate，按层级关掉
      // 对应浮层——"逐级返回"就借原生既有链路完成，原生侧无需任何改动。
      //
      // 层级判定**全部读原生状态**（不再依赖插件自建 DOM）：
      //   · 原生抽屉展开：frame[data-sidebar-drawer] 且未折叠 → toggleSidebar()
      //   · 设置弹窗一级：role=dialog + aria-modal 且含 NAV 子节点 → 原生 close
      //   · 设置弹窗二级页：设置内存在原生返回控件 → 触发它，回一级
      //   · 其余（右栏、中央面板）：沿用既有服务面判定
      //   · 没有可退层：放行给宿主，不吞键、不死循环
      // 自己关层时必须把对应哨兵一并吃掉，否则留下幽灵条目，下一次按返回会空转。
      // 状态挂在 window 上并只绑一次 popstate：本插件支持同页面热切换（重复
      // apply），实例换了但历史条目还在，共享状态才不会算错条数。
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
      function openLayerDepth() {
        // 官方原版（适配开关关）：不接管返回键，一层都不压 —— 返回键交还原生宿主。
        if (!enabled) return 0;
        var depth = 0;
        var dlg = settingsDialog();
        if (dlg) {
          depth += 1; // 设置弹窗（一级）
          if (settingsBackButton(dlg)) depth += 1; // 设置二级页
        }
        if (drawerOpen()) depth += 1; // 原生抽屉
        if (rightbarOpen()) depth += 1;
        if (panelActive()) depth += 1;
        return depth;
      }
      function closeTopLayer() {
        // 官方原版（适配开关关）：不接管返回键，放行给宿主。
        if (!enabled) return false;
        var dlg = settingsDialog();
        if (dlg) {
          // 设置二级页 → 触发原生返回控件回一级；否则原生关闭设置面板。
          var back = settingsBackButton(dlg);
          if (back) {
            try { back.click(); } catch (e) { bootLog('settings-back-fail:' + (e && e.message || e)); }
            return true;
          }
          return closeSettings(dlg);
        }
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
            // 合法层级最深 5（设置子页 + 设置面板 + 抽屉 + 右栏 + 中央面板）；到 6 说明
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
          // 关层会改 DOM/状态，但 childList 观察器与属性观察器的触发不保证覆盖所有
          // 路径；这里无条件补一次对账，避免 pushed 与真实层数漂移后"按返回空转一次"。
          if (typeof window.__dshboxReconcileBack === 'function') window.__dshboxReconcileBack();
        });
      }
      // 原生状态变化的观察通道：抽屉开合（frame 的 data-sidebar-collapsed）、
      // 抽屉形态（data-sidebar-drawer）与右栏开合（data-sidebar-right-open）
      // 都只是**属性变化**，不产生 childList 突变，childList 通道收不到 ——
      // 不订这一条，打开抽屉时哨兵不会压，返回键便直接穿透到原生宿主。
      // 订 body 的属性而不是订具体元素：抽屉 frame 与右栏面板都会随会话增删，
      // body 才不丢生命周期。整棵子树被卸载时本观察器收不到（脱离观察子树），
      // 那个方向由 childList 通道兜住——两端各管一向。
      // 本观察器有意不做 teardown：它必须活过热切换，回调经 window 转发到最新实例的
      // 对账函数（仍受"最新实例动账"守卫），按实例 teardown 会让通道在热切换后失效。
      if (!window.__dshboxStateObsBound) {
        window.__dshboxStateObsBound = true;
        try {
          new MutationObserver(function () {
            if (typeof window.__dshboxReconcileBack === 'function') window.__dshboxReconcileBack();
          }).observe(document.body, {
            attributes: true,
            subtree: true,
            attributeFilter: ['data-sidebar-collapsed', 'data-sidebar-drawer', 'data-sidebar-right-open']
          });
        } catch (e) { bootLog('state-obs-fail:' + (e && e.message || e)); }
      }

      // 5) 面板观察：设置面板开合 → 注入/清理开关行 + 哨兵对账。
      var panelObs = new MutationObserver(function () {
        var dlg = settingsDialog();
        if (!dlg) {
          // 面板关闭：清掉注入行（正常情况下它随面板一起卸载，这里是兜底）。
          removeMobileRow();
          reconcileBackSentinels();
          return;
        }
        ensureMobileRow(rowOps);
        reconcileBackSentinels();
      });
      try { panelObs.observe(document.body, { childList: true, subtree: true }); }
      catch (e) { bootLog('panelObs-fail:' + (e && e.message || e)); }

      // 6) 点侧栏里的会话行 → 收起原生抽屉（窄屏下抽屉盖住会话区）。
      //    行内交互子树（⋯菜单钮/重命名输入框/菜单项等）不得排定收起：
      //      dsh 三点钮就在 sessionRow 内（row > span.rowActions > Menu anchor），
      //      其 React stopPropagation 只挡 React 合成层，拦不住本 capture 监听——
      //      不排行的话点⋯→350ms 后抽屉收起→菜单锚点被隐藏吞掉→菜单闪现即灭。
      //    只在**原生抽屉展开**时收起：宽屏轨道形态不受影响。
      if (!document.__dsh_mobile_nav_bound) {
        document.__dsh_mobile_nav_bound = true;
        document.addEventListener('click', function (e) {
          if (!enabled) return; // 官方原版：不干预
          var sbRow = e.target && e.target.closest ? e.target.closest('[class*="sessionRow"]') : null;
          if (!sbRow) return;
          if (e.target.closest('[class*="rowActions"], button, input, [role="menu"], [role="menuitem"]')) return;
          setTimeout(function () {
            // 定时器到点二次核查——任一行的菜单仍打开
            // （dsh 源码确认：菜单打开时行加 YDXeBa_menuOpen 类）或存在菜单 portal
            // 时跳过本次收起，兜住"排定后菜单才被打开"的竞态。
            if (document.querySelector('[class*="menuOpen"]') || document.querySelector('[role="menu"]')) return;
            if (!drawerOpen()) return;
            try { ctx.layout.toggleSidebar(); } catch (err) { /* noop */ }
          }, 350); // 等 dsh 完成会话切换再收起，避免竞争
        }, true);
      }

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

      // 9b) Gecko 键盘顶栏钉住（仅 Gecko；System WebView 零动作）。
      //     与 composer 上移逻辑（installKeyboardFix）共存：本段只处理顶栏、不碰 composer 卡片。
      //     开关关闭或 System WebView 下此处不进入安装路径（installGeckoTopBarPin 内部再二次守门）。
      try {
        if (enabled && isGecko()) installGeckoTopBarPin(function () { return enabled; });
      } catch (e) { bootLog('gecko-topbar-fail:' + (e && e.message || e)); }

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

      // 11) 初始开关态应用（样式热切换 + 对外暴露状态）。
      if (style) style.disabled = !enabled;
      // Expose for the session-row handler to check.
      window.__dsh_mobile_enabled = function () { return enabled; };

      // 11b) 首次对账：按当下的原生状态压哨兵（设置面板/抽屉可能在插件装载前
      //      就已经开着），并在下一拍再对一次（等 React 把 frame 属性写上）。
      reconcileBackSentinels();
      setTimeout(function () { reconcileBackSentinels(); }, 0);

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
