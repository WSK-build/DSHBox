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
    // 0) WebView 兼容补丁：非标准 scheme 的 URL host 解析（1.3.1 M22）
    // =====================================================================
    // 背景（真机两机对照实测）：dsh 的 `dsh-client-resources` 用
    //   `protocolOf(address)` 从资源地址取协议名，实现依赖 `URL.hostname`：
    //     const u = new URL('dsh-resource://file/session/<id>/<path>');
    //     if (u.protocol !== 'dsh-resource:') return undefined;
    //     return u.hostname === '' ? undefined : u.hostname.toLowerCase();
    //   旧内核（如 WebView 126）对**非特殊 scheme** 不解析 authority：
    //     hostname === ''            pathname === '//file/session/…'
    //   → 取不到 'file' → provider 匹配失败 → 右栏预览显示「文件资源服务不可用」。
    //   新内核（WebView 138）按标准解析：hostname === 'file'，一切正常。
    //
    // 处置：**运行期修正 URL 解析行为**，不改 dsh 源码（与硬链接垫片同一哲学）。
    //   仅当探测确认内核有此缺陷时才安装；修正范围严格限定为
    //   「hostname 为空**且** pathname 以 `//` 开头」的地址——即缺陷表现本身。
    //   特殊 scheme（http/https/…）与无 authority 的 scheme（mailto: 等）原样放行。
    (function installUrlHostCompat() {
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
       * 缺陷恰恰体现在「对外视图」上（实测踩过：修复逻辑因此完全不生效）。
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
    })();

    var SAFE_TOP = 'calc(env(safe-area-inset-top, 0px) + 12px)';


    var MOBILE_CSS = [
      '/* === dsh-mobile-adapt: 移动端浮层抽屉 + 设置两步式 === */',
      '@media (max-width: 1024px) {',
      '  /* 全宽内容：把侧栏轨道压成 0（我们把它改成了脱离文档流的抽屉，见下），',
      '     中栏吃满剩余宽度。第三条轨道交给 dsh 自己按内容定宽——**不再钉死 0**：',
      '     右栏的面板是绝对定位悬挂出来的，轨道宽度本就是 0，钉死只会妨碍',
      '     「占用方显式申请轨道宽度」的情形。',
      '     注意 centerCol 必须留在文档流(不能 absolute)，否则键盘弹出时浏览器',
      '     无法自动滚动输入框上移 → 键盘唤出/上移异常。 */',
      '  [class*="frame"] { grid-template-columns: 0px minmax(0, 1fr) auto !important; }',
      '  [class*="frame"][data-sidebar-collapsed] [class*="sidebarCol"] {',
      '    display: none !important;',
      '  }',
      '  /* 右侧列（0.1.1 名 detailsCol，0.1.5 同一模块内改名 rightbarCol；两个类名都匹配）。',
      '     ⚠️ 这里曾经写 `display: none !important`，是**错的**（1.3.1 修正）：',
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
      //
      //     类名两版不同，且互不重叠，必须同时兼容（本插件随 app 打包）：
      //       0.1.1  sidebar 渲染
      //                span.fallbackBrandName   ← 品牌文本
      //                span.buildRevision       ← 构建哈希
      //       0.1.5  buildVersion 已定义时渲染
      //                span.localBuildBrand
      //                  span.localBuildTitle   ← 品牌文本
      //                  span.buildVersion      ← 构建哈希
      //              buildVersion 未定义时才回退到 span.fallbackBrandName。
      //     因此品牌文本取 [fallbackBrandName | localBuildTitle]，
      //     哈希取 [buildRevision | buildVersion]；缺任一分支都不会误伤。
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

      // =====================================================================
      // 12) 回形针「上传来源」菜单（1.3.1 M5；1.3.1 修正挂载点）
      // =====================================================================
      // 需求：附件除了「手机文件」，还要能选**沙箱文件**（guest /root/projects）。
      // 而 dsh 的 <input type="file"> 走系统 ACTION_GET_CONTENT，只列手机侧来源；
      // PRoot 沙箱不是 Android 的 DocumentsProvider，永远不出现在系统选择器里。
      // 因此 app 侧必须自带一个沙箱选择器，并把「这次要选哪一侧」告诉原生。
      //
      // ⚠️ 挂载点（1.3.1 修正，重要）：
      //   早期版本把菜单挂在 composer 的「**+**」按钮上（识别方式
      //   `button[aria-haspopup="listbox"]`）——那是 dsh 的**斜杠命令面板**入口
      //   （aria-label = t("input.commands") 即「指令」/「Commands」）。
      //   劫持它会把命令面板整个顶掉（表现为点 + 弹出上传菜单而不是命令集），
      //   属于把「附件」语义错挂到「命令」语义上。
      //   现在改挂**回形针**（aria-label = t("file.attach") 即「添加附件」/
      //   「Add attachment」），「+」完全归还 dsh，本段不再触碰它。
      //
      // 关于 dsh 命令面板本身：0.1.5 的 reducer 有两条自动关闭路径
      //   - source-settled 后 allReadyEmpty(groups) → closed()
      //   - source-failed 后 groups.length === 0    → closed()
      // 历史上曾观察到「点 + 只闪一下」；但这属于 dsh 内部行为，**不由本插件接管**。
      //
      // 来源偏好如何传给原生：本 WebView **没有 addJavascriptInterface 桥**，
      // 因此用隐藏 <input type="file"> 的 accept 属性作为唯一可用通道 ——
      // 打上哨兵 MIME 即代表「沙箱」，否则代表「手机文件」；
      // 原生 WebChromeClient.onShowFileChooser 读 fileChooserParams.acceptTypes 分流。
      //
      // ⚠️ accept 是**共享状态**：dsh 自有的上传路径若绕过我们的菜单直接
      // input.click()，哨兵值就会把它误判为「沙箱上传」。所以哨兵值必须在每条
      // 结束路径上复位（见 resetUploadAccept 与捕获阶段 click 守卫）——
      // 不能假设「每次点击前都会重写，所以不存在残留」。
      //
      // 本段**不受「移动端适配」开关控制**：上传来源是功能能力而非布局适配，
      // 关掉适配（官方原版外观）时仍必须可用。
      var UPLOAD_SENTINEL = 'application/x-dshbox-sandbox-upload';

      var UPLOAD_TEXT = {
        zh: {
          title: '添加附件',
          phone: '上传手机文件',
          phoneSub: '从系统「文件 / 相册」中选择',
          sandbox: '上传沙箱文件',
          sandboxSub: '从 DSHBox 工作区（/root/projects）选择',
          blocked: '暂不可上传：请先发送一条消息创建会话，或等待当前任务结束',
          noInput: '上传入口未就绪，请稍后重试',
        },
        en: {
          title: 'Add attachment',
          phone: 'Upload from phone',
          phoneSub: 'Pick from system Files / Photos',
          sandbox: 'Upload from sandbox',
          sandboxSub: 'Pick from the DSHBox workspace (/root/projects)',
          blocked: 'Cannot attach now: send a message to create a session, or wait for the current task to finish',
          noInput: 'Upload entry not ready, please retry',
        },
        ar: {
          title: 'إضافة مرفق',
          phone: 'رفع من الهاتف',
          phoneSub: 'اختر من «الملفات / الصور» في النظام',
          sandbox: 'رفع من البيئة المعزولة',
          sandboxSub: 'اختر من مساحة عمل DSHBox ‏(/root/projects)',
          blocked: 'لا يمكن الإرفاق الآن: أرسل رسالة لإنشاء جلسة أو انتظر انتهاء المهمة الحالية',
          noInput: 'مدخل الرفع غير جاهز، حاول مرة أخرى',
        },
        es: {
          title: 'Añadir adjunto',
          phone: 'Subir desde el teléfono',
          phoneSub: 'Elegir de Archivos / Fotos del sistema',
          sandbox: 'Subir desde el sandbox',
          sandboxSub: 'Elegir del espacio de trabajo de DSHBox (/root/projects)',
          blocked: 'No se puede adjuntar ahora: envía un mensaje para crear una sesión o espera a que termine la tarea actual',
          noInput: 'La entrada de subida no está lista, inténtalo de nuevo',
        },
        fr: {
          title: 'Ajouter une pièce jointe',
          phone: 'Importer depuis le téléphone',
          phoneSub: 'Choisir dans Fichiers / Photos du système',
          sandbox: 'Importer depuis le bac à sable',
          sandboxSub: 'Choisir dans l\'espace de travail DSHBox (/root/projects)',
          blocked: 'Impossible de joindre maintenant : envoyez un message pour créer une session ou attendez la fin de la tâche en cours',
          noInput: 'L\'entrée d\'import n\'est pas prête, réessayez',
        },
        ru: {
          title: 'Добавить вложение',
          phone: 'Загрузить с телефона',
          phoneSub: 'Выбрать из «Файлы / Фото» системы',
          sandbox: 'Загрузить из песочницы',
          sandboxSub: 'Выбрать из рабочей области DSHBox (/root/projects)',
          blocked: 'Сейчас прикрепить нельзя: отправьте сообщение, чтобы создать сеанс, или дождитесь завершения текущей задачи',
          noInput: 'Точка загрузки не готова, повторите попытку',
        },
      };

      function uploadText() {
        var l = (document.documentElement.getAttribute('lang') || navigator.language || 'en').toLowerCase();
        if (l.indexOf('zh') === 0) return UPLOAD_TEXT.zh;
        if (l.indexOf('ar') === 0) return UPLOAD_TEXT.ar;
        if (l.indexOf('es') === 0) return UPLOAD_TEXT.es;
        if (l.indexOf('fr') === 0) return UPLOAD_TEXT.fr;
        if (l.indexOf('ru') === 0) return UPLOAD_TEXT.ru;
        return UPLOAD_TEXT.en;
      }

      function dshComposerCard() {
        return document.querySelector('[data-composer-card]');
      }
      function dshToolsRow() {
        var card = dshComposerCard();
        if (!card) return null;
        var cands = card.querySelectorAll('[class*="tools"]');
        for (var i = 0; i < cands.length; i++) {
          if (cands[i].querySelector('button')) return cands[i];
        }
        return null;
      }
      /**
       * 「+」命令按钮（`aria-haspopup="listbox"`，`aria-label = t("input.commands")`
       * 即「指令」/「Commands」）。
       *
       * **本插件绝不劫持它**——它是 dsh 的斜杠命令面板入口（1.3.1 修正：
       * 早期版本误把上传来源菜单挂在它上面，把命令面板整个顶掉了）。
       * 仅在文档里保留识别方式，供日后排查使用。
       */
      function dshCommandButton() {
        var row = dshToolsRow();
        if (!row) return null;
        return row.querySelector('button[aria-haspopup="listbox"]') || null;
      }

      /**
       * 回形针「添加附件」按钮 —— 上传来源菜单的**真正挂载点**。
       *
       * dsh 的 tools 行渲染顺序（0.1.5 实测）：
       *   `[+ 命令按钮][回形针按钮][<input type="file" multiple hidden>]`
       * 三个都带同一个 `className = InputBar_module_css_default.add`，**不能靠类名区分**。
       *
       * 识别方式（双保险，互为兜底）：
       *  ① 结构：隐藏 `<input type="file">` 的紧邻前一个 button
       *     （Tooltip 可能包一层，故先看自身、再向下找 button）；
       *  ② 文案：`aria-label` 命中 `t("file.attach")`（「添加附件」/「Add attachment」）。
       * 结构调整时 ① 失效，文案改动时 ② 失效，两者不会同时失效。
       */
      var ATTACH_LABELS = ['添加附件', 'Add attachment'];

      function dshAttachButton() {
        var input = dshFileInput();
        if (input) {
          var sib = input.previousElementSibling;
          if (sib) {
            if (sib.tagName === 'BUTTON') return sib;
            var inner = sib.querySelector ? sib.querySelector('button') : null;
            if (inner) return inner;
          }
        }
        var row = dshToolsRow();
        if (!row) return null;
        var btns = row.querySelectorAll('button');
        for (var i = 0; i < btns.length; i++) {
          var label = (btns[i].getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim();
          if (ATTACH_LABELS.indexOf(label) >= 0) return btns[i];
        }
        return null;
      }

      function dshFileInput() {
        var row = dshToolsRow();
        if (!row) return null;
        return row.querySelector('input[type="file"]') || null;
      }

      /** 一次性 toast（复用 dsh 没有公开 toast API，故自绘，样式跟主题变量走）。 */
      var toastEl = document.getElementById('dsh-upload-toast');
      function uploadToast(msg) {
        if (toastEl === null) {
          toastEl = document.createElement('div');
          toastEl.id = 'dsh-upload-toast';
          toastEl.style.cssText = [
            'position:fixed',
            'left:50%',
            'transform:translateX(-50%)',
            'bottom:calc(env(safe-area-inset-bottom,0px) + 96px)',
            'z-index:2147483000',
            'max-width:86vw',
            'padding:10px 16px',
            'border-radius:12px',
            'font-size:14px',
            'line-height:1.45',
            'text-align:center',
            'background:var(--dsw-alias-bg-float,#1f1f1f)',
            'color:var(--dsw-alias-text-primary,#fff)',
            'border:1px solid var(--dsw-alias-border-l1,rgba(128,128,128,.35))',
            'box-shadow:0 6px 24px rgba(0,0,0,.28)',
            'pointer-events:none',
            'opacity:0',
            'transition:opacity .18s ease',
          ].join(';');
          document.body.appendChild(toastEl);
        }
        toastEl.textContent = msg;
        toastEl.style.opacity = '1';
        clearTimeout(toastEl.__dshTimer);
        toastEl.__dshTimer = setTimeout(function () { toastEl.style.opacity = '0'; }, 2600);
      }

      var uploadMenu = null;
      var uploadMenuOpen = false;
      var uploadOpenedAt = 0;

      function iconSvg(kind) {
        var d = kind === 'sandbox'
          ? 'M3 6.5A1.5 1.5 0 0 1 4.5 5h4.2l1.7 1.8H19.5A1.5 1.5 0 0 1 21 8.3v9.2A1.5 1.5 0 0 1 19.5 19h-15A1.5 1.5 0 0 1 3 17.5z'
          : 'M7 3.5h6.2L17 7.3V19.5a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4.5a1 1 0 0 1 1-1z M13 3.7V7.6H16.9';
        return '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" '
          + 'stroke="currentColor" stroke-width="1.6" stroke-linecap="round" '
          + 'stroke-linejoin="round" aria-hidden="true"><path d="' + d + '"/></svg>';
      }

      function closeUploadMenu() {
        if (!uploadMenu || !uploadMenuOpen) return;
        uploadMenuOpen = false;
        uploadMenu.style.opacity = '0';
        uploadMenu.style.transform = 'translateY(6px) scale(.98)';
        uploadMenu.style.pointerEvents = 'none';
        document.removeEventListener('pointerdown', onDocPointerDown, true);
        document.removeEventListener('keydown', onMenuKeyDown, true);
        window.removeEventListener('resize', closeUploadMenu);
        window.removeEventListener('scroll', closeUploadMenu, true);
      }

      function onDocPointerDown(ev) {
        if (!uploadMenuOpen) return;
        if (uploadMenu && ev.target instanceof Node && uploadMenu.contains(ev.target)) return;
        closeUploadMenu();
      }

      function onMenuKeyDown(ev) {
        if (ev.key === 'Escape') closeUploadMenu();
      }

      // 本次点击是否由 pickSource 发起（accept 已由我们写好）。
      // 只在这一跳内有效：input.click() 的事件派发是同步的，原生 onShowFileChooser
      // 也就在这一跳里读走 acceptTypes，因此标志用完即清，不留任何跨点击状态。
      var pickSourceActive = false;

      /** 复位 accept：清掉可能的哨兵残留，让 <input> 回到「无 accept」的干净状态。 */
      function resetUploadAccept(input) {
        pickSourceActive = false;
        if (input) input.removeAttribute('accept');
      }

      function pickSource(source) {
        var T = uploadText();
        // 回形针被禁用 ⟺ (subagent || locked || machineBusy || addFiles 未注入)——
        // 它比「+」更贴近上传能力本身（「+」另有 toggleCommandMenu 未注入这一独立条件）。
        // 注意：这里**不摘除也不缓存** disabled —— Blink 对禁用控件仍会派发
        // pointerdown（实测：pointerdown/touchstart 到达，mousedown/click 不到），
        // 所以拦截器照样能打开菜单，而禁用态可以每次都现读，天然不会过期。
        var attach = dshAttachButton();
        if (attach !== null && attach.hasAttribute('disabled')) {
          uploadToast(T.blocked);
          closeUploadMenu();
          return;
        }
        var input = dshFileInput();
        if (!input || input.disabled) {
          uploadToast(input ? T.blocked : T.noInput);
          closeUploadMenu();
          return;
        }
        // 本次点击显式重写 accept：沙箱用哨兵 MIME，手机文件用空 accept（任意类型）。
        if (source === 'sandbox') input.setAttribute('accept', UPLOAD_SENTINEL);
        else input.removeAttribute('accept');
        closeUploadMenu();
        pickSourceActive = true;
        try {
          input.click();
        } catch (e) {
          uploadToast(T.noInput);
        } finally {
          pickSourceActive = false;
        }
      }

      // 哨兵值复位（三条结束路径，缺一都会污染 dsh 自有的上传入口）：
      //   ① change —— 用户选完文件；
      //   ② cancel —— 用户取消选择（Chromium 对 <input type=file> 派发 cancel）；
      //   ③ 捕获阶段 click 守卫 —— dsh 自己的入口触发 input.click() 时不经过
      //      pickSource，若 accept 上还留着哨兵值就会被原生误判为沙箱上传。
      //      守卫在事件捕获阶段先于原生读取执行，且仅对「非 pickSource 发起」
      //      的点击生效，不影响哨兵值的正常传递。
      document.addEventListener('change', function (ev) {
        var input = dshFileInput();
        if (input && ev.target === input) resetUploadAccept(input);
      }, true);
      document.addEventListener('cancel', function (ev) {
        var input = dshFileInput();
        if (input && ev.target === input) resetUploadAccept(input);
      }, true);
      document.addEventListener('click', function (ev) {
        var input = dshFileInput();
        if (!input || ev.target !== input) return;
        if (pickSourceActive) return;   // 本次点击由 pickSource 发起，accept 由它负责
        resetUploadAccept(input);
      }, true);

      function buildUploadMenu() {
        if (uploadMenu) return uploadMenu;
        var T = uploadText();
        var el = document.createElement('div');
        el.id = 'dsh-upload-menu';
        el.setAttribute('role', 'menu');
        el.setAttribute('aria-label', T.title);
        el.style.cssText = [
          'position:fixed',
          'z-index:2147483000',
          'min-width:236px',
          'max-width:min(340px,calc(100vw - 24px))',
          'padding:6px',
          'border-radius:16px',
          'background:var(--dsw-alias-bg-float,#fff)',
          'color:var(--dsw-alias-text-primary,#111)',
          'border:1px solid var(--dsw-alias-border-l1,rgba(0,0,0,.12))',
          'box-shadow:0 10px 34px rgba(0,0,0,.22)',
          'opacity:0',
          'transform:translateY(6px) scale(.98)',
          'transition:opacity .16s ease,transform .16s ease',
          'pointer-events:none',
          'box-sizing:border-box',
        ].join(';');

        function row(source, icon, title, sub) {
          var b = document.createElement('button');
          b.type = 'button';
          b.setAttribute('role', 'menuitem');
          b.setAttribute('data-source', source);
          b.style.cssText = [
            'display:flex',
            'align-items:center',
            'gap:12px',
            'width:100%',
            'padding:10px 12px',
            'border:0',
            'border-radius:12px',
            'background:transparent',
            'color:inherit',
            'text-align:left',
            'font:inherit',
            'cursor:pointer',
            'box-sizing:border-box',
          ].join(';');
          var ico = document.createElement('span');
          ico.style.cssText = 'flex:none;display:flex;align-items:center;justify-content:center;width:34px;height:34px;border-radius:10px;'
            + 'background:var(--dsw-alias-interactive-bg-hover,rgba(128,128,128,.14));color:var(--dsw-alias-text-primary,#111)';
          ico.innerHTML = iconSvg(icon);
          var txt = document.createElement('span');
          txt.style.cssText = 'min-width:0;display:flex;flex-direction:column;gap:2px';
          var t1 = document.createElement('span');
          t1.textContent = title;
          t1.style.cssText = 'font-size:14px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis';
          var t2 = document.createElement('span');
          t2.textContent = sub;
          t2.style.cssText = 'font-size:12px;line-height:1.35;color:var(--dsw-alias-label-secondary,#6b7280);'
            + 'white-space:normal;word-break:break-word';
          txt.appendChild(t1);
          txt.appendChild(t2);
          b.appendChild(ico);
          b.appendChild(txt);
          b.addEventListener('pointerenter', function () {
            b.style.background = 'var(--dsw-alias-interactive-bg-hover,rgba(128,128,128,.14))';
          });
          b.addEventListener('pointerleave', function () { b.style.background = 'transparent'; });
          // pointerdown 而非 click：触屏下 click 可能被 dsh 的 composer 抢焦点逻辑吞掉
          b.addEventListener('pointerdown', function (ev) {
            ev.preventDefault();
            ev.stopPropagation();
            pickSource(source);
          }, true);
          b.addEventListener('click', function (ev) {
            ev.preventDefault();
            ev.stopPropagation();
          }, true);
          return b;
        }

        el.appendChild(row('phone', 'phone', T.phone, T.phoneSub));
        el.appendChild(row('sandbox', 'sandbox', T.sandbox, T.sandboxSub));
        document.body.appendChild(el);
        uploadMenu = el;
        return el;
      }

      function openUploadMenu() {
        var btn = dshAttachButton();
        if (!btn) return;
        var el = buildUploadMenu();
        // 重写文案（语言可能在会话内切换）
        var T = uploadText();
        var t1 = el.querySelectorAll('button[data-source] span > span');
        var rows = el.querySelectorAll('button[data-source]');
        if (rows.length === 2 && t1.length === 4) {
          t1[0].textContent = T.phone; t1[1].textContent = T.phoneSub;
          t1[2].textContent = T.sandbox; t1[3].textContent = T.sandboxSub;
        }
        // 锚定在回形针左上角正上方，超出视口自动纠偏
        el.style.opacity = '0';
        el.style.transform = 'translateY(6px) scale(.98)';
        el.style.pointerEvents = 'none';
        el.style.left = '-9999px';
        el.style.top = '0px';
        var r = btn.getBoundingClientRect();
        var mw = el.offsetWidth;
        var mh = el.offsetHeight;
        var left = Math.min(Math.max(8, r.left - 6), Math.max(8, window.innerWidth - mw - 8));
        var top = r.top - mh - 8;
        if (top < 8) top = Math.min(window.innerHeight - mh - 8, r.bottom + 8);
        el.style.left = left + 'px';
        el.style.top = Math.max(8, top) + 'px';
        // 强制一帧后再展开，保证 transition 生效
        void el.offsetHeight;
        el.style.opacity = '1';
        el.style.transform = 'translateY(0) scale(1)';
        el.style.pointerEvents = 'auto';
        uploadMenuOpen = true;
        uploadOpenedAt = Date.now();
        document.addEventListener('pointerdown', onDocPointerDown, true);
        document.addEventListener('keydown', onMenuKeyDown, true);
        window.addEventListener('resize', closeUploadMenu);
        window.addEventListener('scroll', closeUploadMenu, true);
      }

      (function installUploadMenu() {
        // 关键实测结论（Chromium/WebView 同源 Blink）：
        //   禁用 <button>  → pointerdown ✔ / touchstart ✔ / mousedown ✘ / click ✘
        //   可用 <button>  → 四者皆 ✔
        // 所以「无会话时 dsh 把回形针置灰」并不会挡住我们的拦截器：用 **捕获阶段
        // pointerdown** 打开菜单即可；是否能真的上传则在 pickSource 里现读 disabled 判定。
        // 这样既不需要摘除 disabled（不动 dsh 的状态），也不存在任何缓存过期问题。
        //
        // 拦截目标是**回形针（添加附件）**，不是「+」。
        // 1.3.1 修正：早期版本劫持了「+」（aria-haspopup="listbox"），把 dsh 的
        // 斜杠命令面板整个顶掉了。上传来源属于「附件」语义，就该挂在回形针上。
        //
        // 用 closest('button') 而非 identity 比较：回形针的图标是内联 <svg>，
        // 触摸点落在 svg 上时 ev.target 是 svg 而非 button 本身。
        document.addEventListener('pointerdown', function (ev) {
          var btn = dshAttachButton();
          if (!btn || !(ev.target instanceof Node)) return;
          var hit = ev.target === btn || btn.contains(ev.target);
          if (!hit) {
            var closest = ev.target.closest ? ev.target.closest('button') : null;
            hit = closest !== null && closest === btn;
          }
          if (!hit) return;
          // 抢在 dsh 的 onClick(fileInputRef.click()) 之前，且阻止焦点离开编辑器
          ev.preventDefault();
          ev.stopPropagation();
          if (uploadMenuOpen && Date.now() - uploadOpenedAt > 120) closeUploadMenu();
          else openUploadMenu();
        }, true);

        // 按钮可用时 dsh 的 onClick 也在监听：必须拦掉，否则它会直接
        // fileInputRef.current.click() 弹出系统选择器（菜单白开了）。
        // 禁用时 click 本就不派发，这条只是对可用态的兜底。
        document.addEventListener('click', function (ev) {
          var btn = dshAttachButton();
          if (!btn || !(ev.target instanceof Node)) return;
          var hit = ev.target === btn || btn.contains(ev.target);
          if (!hit) {
            var closest = ev.target.closest ? ev.target.closest('button') : null;
            hit = closest !== null && closest === btn;
          }
          if (!hit) return;
          ev.preventDefault();
          ev.stopPropagation();
          ev.stopImmediatePropagation();
        }, true);
      })();

      // =====================================================================
      // 13) 「打开配置文件」接管（1.3.1 M6）
      // =====================================================================
      // 背景（实测根因）：设置面板顶部的「打开配置文件」按钮
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

      (function installOpenDocumentShim() {
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
      })();

    };

    return module.exports;
  },
});
