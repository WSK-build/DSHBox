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

    var SAFE_TOP = 'calc(env(safe-area-inset-top, 0px) + 12px)';


    var MOBILE_CSS = [
      '/* === dsh-mobile-adapt: 移动端浮层抽屉 + 设置两步式 === */',
      '@media (max-width: 1024px) {',
      '  /* 全宽内容：覆盖 grid 列让对话区满宽；隐藏侧栏 rail(折叠态)与 details 列。',
      '     注意 centerCol 必须留在文档流(不能 absolute)，否则键盘弹出时浏览器',
      '     无法自动滚动输入框上移 → 键盘唤出/上移异常。 */',
      '  [class*="frame"] { grid-template-columns: 0px 1fr 0px !important; }',
      '  [class*="frame"][data-sidebar-collapsed] [class*="sidebarCol"] {',
      '    display: none !important;',
      '  }',
      '  [class*="detailsCol"] { display: none !important; }',
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
      '  /* 触屏不需要拖拽手柄 */',
      '  [class*="handle"] { display: none !important; }',
      '  /* 汉堡按钮（下移到 56px，避开顶部标签栏） */',
      '  #dsh-mobile-menu {',
      '    position: fixed; top: calc(env(safe-area-inset-top, 0px) + 56px); left: 10px; z-index: 1100;',
      '    width: 40px; height: 40px;',
      '    display: flex; align-items: center; justify-content: center;',
      '    font-size: 20px; line-height: 1;',
      '    border: 1px solid var(--dsw-alias-border-l1, rgba(0, 0, 0, .12));',
      '    border-radius: 10px;',
      '    background: var(--dsw-alias-bg-float, #fff);',
      '    color: var(--dsw-alias-text-primary, #111);',
      '    cursor: pointer; box-shadow: 0 2px 8px rgba(0, 0, 0, .15);',
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
      '     options 底部留 120px 避开左下返回键 + 底部开关浮层(防遮挡) */',
      '  body.dsh-settings-content-open [role="dialog"][aria-modal="true"]:has(> nav) > div {',
      '    overflow-x: hidden !important; overflow-y: auto !important;',
      '  }',
      '  body.dsh-settings-content-open [role="dialog"][aria-modal="true"]:has(> nav) > div > div:last-child {',
      '    width: 100% !important;',
      '    overflow-x: hidden !important; overflow-y: auto !important;',
      '    padding-bottom: 120px !important;',
      '  }',
      '  body.dsh-settings-content-open [role="dialog"][aria-modal="true"]:has(> nav) > div > div:last-child > * {',
      '    width: 100% !important;',
      '    max-width: 100% !important;',
      '    min-width: 0 !important;',
      '    margin: 0 !important;',
      '  }',
      '  /* 内容页所有元素一律收缩到容器内(不裁切、不超出右侧) */',
      '  body.dsh-settings-content-open [role="dialog"][aria-modal="true"]:has(> nav) > div > div:last-child * {',
      '    max-width: 100% !important;',
      '    min-width: 0 !important;',
      '    box-sizing: border-box !important;',
      '  }',
      '  body.dsh-settings-content-open [role="dialog"][aria-modal="true"]:has(> nav) > div > div:last-child [class*="row"],',
      '  body.dsh-settings-content-open [role="dialog"][aria-modal="true"]:has(> nav) > div > div:last-child [class*="item"],',
      '  body.dsh-settings-content-open [role="dialog"][aria-modal="true"]:has(> nav) > div > div:last-child [class*="cell"] {',
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
      '',
      '  /* 打开设置(仅真实设置面板)时隐藏汉堡 */',
      '  body:has([role="dialog"][aria-modal="true"]:has(> nav)) #dsh-mobile-menu {',
      '    display: none !important;',
      '  }',
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

      // 1) Inject the global stylesheet (idempotent).
      var style = document.querySelector('style[data-plugin="@local/dsh-mobile-adapt"]');
      if (style === null) {
        style = document.createElement('style');
        style.setAttribute('data-plugin', '@local/dsh-mobile-adapt');
        style.textContent = MOBILE_CSS;
        document.head.appendChild(style);
      }

      // 1b) Mobile-adapt on/off switch (hot toggle, persisted).
      //     The toggle lives OUTSIDE MOBILE_CSS (inline styles) so it stays
      //     usable even after MOBILE_CSS is disabled (official mode).
      var enabled = localStorage.getItem('dsh-mobile-enabled') !== '0'; // default ON
      var toggle = document.getElementById('dsh-mobile-toggle');
      if (toggle === null) {
        toggle = document.createElement('button');
        toggle.id = 'dsh-mobile-toggle';
        toggle.setAttribute('aria-label', '切换移动端适配');
        toggle.style.cssText = [
          'position:fixed',
          'left:50%',
          'transform:translateX(-50%)',
          'bottom:calc(env(safe-area-inset-bottom,0px) + 16px)',
          'z-index:1200',
          'display:none',
          'align-items:center',
          'gap:8px',
          'height:40px',
          'padding:0 16px',
          'border:1px solid rgba(0,0,0,.12)',
          'border-radius:20px',
          'background:#fff',
          'color:#111',
          'font-size:14px',
          'font-weight:600',
          'cursor:pointer',
          'box-shadow:0 2px 10px rgba(0,0,0,.18)',
        ].join(';');
        toggle.addEventListener('click', function () {
          setEnabled(!enabled);
        });
        document.body.appendChild(toggle);
      }

      function setEnabled(v) {
        enabled = !!v;
        localStorage.setItem('dsh-mobile-enabled', enabled ? '1' : '0');
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
        // 开关自身文案
        renderToggle();
      }
      var settingsOpen = false;
      function renderToggle() {
        if (!toggle) return;
        var label = document.createElement('span');
        label.textContent = '移动端适配';
        var state = document.createElement('span');
        // 黑白切换，跟随 web 主题色（深色主题自动反色）
        state.style.cssText = 'padding:2px 12px;border-radius:12px;font-size:12px;font-weight:700;' +
          (enabled
            ? 'background:var(--dsw-alias-text-primary,#111);color:var(--dsw-alias-bg-float,#fff);'
            : 'background:var(--dsw-alias-interactive-bg-hover,#e5e5e5);color:var(--dsw-alias-label-secondary,#666);');
        state.textContent = enabled ? '开' : '关';
        toggle.textContent = '';
        toggle.appendChild(label);
        toggle.appendChild(state);
      }
      renderToggle();

      // 2) Hamburger button (created once, on <body>).
      var menu = document.getElementById('dsh-mobile-menu');
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

      // 3) Backdrop to close the drawer by tapping outside.
      var backdrop = document.querySelector('.dsh-mobile-backdrop');
      if (backdrop === null) {
        backdrop = document.createElement('div');
        backdrop.className = 'dsh-mobile-backdrop';
        backdrop.addEventListener('click', function () {
          try { ctx.layout.toggleSidebar(); } catch (e) { /* noop */ }
        });
        document.body.appendChild(backdrop);
      }

      // 4) Unified bottom-left "返回" key: content state -> nav page,
      //    nav state -> main conversation. Hidden unless settings is open.
      var back = document.getElementById('dsh-settings-back-lb');
      if (back === null) {
        back = document.createElement('button');
        back.id = 'dsh-settings-back-lb';
        back.textContent = '← 返回';
        back.setAttribute('aria-label', '返回上级');
        back.style.cssText = [
          'position:fixed',
          'left:16px',
          'bottom:calc(env(safe-area-inset-bottom,0px) + 16px)',
          'z-index:1100',
          'display:none',
          'align-items:center',
          'height:40px',
          'padding:0 18px',
          'border:1px solid var(--dsw-alias-border-l1, rgba(0,0,0,.12))',
          'border-radius:12px',
          'background:var(--dsw-alias-bg-float,#fff)',
          'color:var(--dsw-alias-text-primary,#111)',
          'font-size:15px',
          'font-weight:600',
          'cursor:pointer',
          'box-shadow:0 2px 10px rgba(0,0,0,.18)',
        ].join(';');
        back.addEventListener('click', function (ev) {
          ev.stopPropagation();
          if (document.body.classList.contains('dsh-settings-content-open')) {
            goToNav();
          } else {
            closeSettings();
          }
        });
        document.body.appendChild(back);
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

      function showBack() { if (back) back.style.display = 'flex'; }
      function hideBack() { if (back) back.style.display = 'none'; }

      function goToContent() {
        document.body.classList.add('dsh-settings-content-open');
        showBack();
      }
      function goToNav() {
        document.body.classList.remove('dsh-settings-content-open');
        showBack();
      }

      // 6) Two-step settings navigation + sidebar session-row handling.
      if (!document.__dsh_mobile_nav_bound) {
        document.__dsh_mobile_nav_bound = true;
        document.addEventListener('click', function (e) {
          if (closing) return;

          // 点侧栏里的"对话记录"→ dsh 切换会话后自动收起侧栏，回到对话主界面
          var sbRow = e.target && e.target.closest ? e.target.closest('[class*="sessionRow"]') : null;
          if (sbRow && enabled) {
            setTimeout(function () {
              var f = document.querySelector('[class*="frame"]');
              if (f && !f.hasAttribute('data-sidebar-collapsed')) {
                try { ctx.layout.toggleSidebar(); } catch (err) { /* noop */ }
              }
            }, 350); // 等 dsh 完成会话切换再收起，避免竞争
            return;
          }

          if (!enabled) return; // 官方原版：设置面板保持 dsh 原生，不干预
          var dlg = document.querySelector('[role="dialog"][aria-modal="true"]');
          if (!dlg) return;
          var navBtn = e.target && e.target.closest ? e.target.closest('nav button') : null;
          if (navBtn && dlg.contains(navBtn)) {
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
        // 仅当"真实设置面板"(带 nav 的 dialog)存在时才隐藏汉堡、显示返回键/开关
        var dlg = document.querySelector('[role="dialog"][aria-modal="true"]:has(> nav)');
        settingsOpen = !!dlg;
        if (menu) menu.style.display = (dlg || !enabled) ? 'none' : 'flex';
        // 开关：设置面板打开时始终显示（两个模式都能切回）
        if (toggle) toggle.style.display = dlg ? 'flex' : 'none';
        if (!dlg) {
          document.body.classList.remove('dsh-settings-content-open');
          hideBack();
          return;
        }
        if (!enabled) {
          // 官方原版：设置面板保持 dsh 原生（不做两步式）
          hideBack();
          return;
        }
        tagCloseButton(dlg);
        showBack();
      });
      dialogObs.observe(document.body, { childList: true, subtree: true });

      // 8) Sync drawer-open state onto <body> so CSS shows the backdrop.
      //    Use a hash-tolerant selector for the frame container.
      function syncUI(frame) {
        if (!enabled) { document.body.classList.remove('dsh-mobile-drawer-open'); return; }
        var collapsed = frame.hasAttribute('data-sidebar-collapsed');
        document.body.classList.toggle('dsh-mobile-drawer-open', !collapsed);
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
      whenFrameReady(function (frame) {
        syncUI(frame);
        var attrObs = new MutationObserver(function () { syncUI(frame); });
        attrObs.observe(frame, { attributes: true, attributeFilter: ['data-sidebar-collapsed'] });
      });

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
      installKeyboardFix();

      // 10) Brand fix: replace the sidebar's "DSH Local Build <hash>" label
      // 10) Brand: mobile mode → "DSH mobile" (hide hash); official mode →
      //     the official product name "DeepSeek Harness" (whale logo is the
      //     official FishLogo, untouched). We never modified dsh source for
      //     the UI; the default fallback label is "DSH Local Build", which we
      //     replace in BOTH modes to match the expected official branding.
      function applyBrand() {
        var sb = document.querySelector('[class*="sidebarCol"]');
        if (!sb) return;
        var name = sb.querySelector('[class*="fallbackBrandName"]');
        var rev = sb.querySelector('[class*="buildRevision"]');
        if (rev) rev.style.display = 'none';
        if (!name) return;
        if (enabled) {
          name.textContent = 'DSH mobile';
        } else {
          name.textContent = 'DeepSeek Harness';
        }
      }
      (function watchBrand() {
        var sb = document.querySelector('[class*="sidebarCol"]');
        applyBrand();
        var obs = new MutationObserver(function () {
          applyBrand();
        });
        obs.observe(document.body, { childList: true, subtree: true });
      })();

      // 11) Initial enabled state application (style.disabled + hamburger).
      if (style) style.disabled = !enabled;
      if (menu) menu.style.display = enabled ? 'flex' : 'none';
      if (backdrop) backdrop.style.display = 'none';
      // Expose for the session-row handler to check.
      window.__dsh_mobile_enabled = function () { return enabled; };
    };

    return module.exports;
  },
});
