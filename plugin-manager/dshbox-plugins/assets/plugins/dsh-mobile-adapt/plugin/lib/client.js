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
    // apply() 是 13 段线性执行、无隔离：任何一段抛异常，其后所有段
    // （含汉堡、上传菜单）都会不执行，表现为"整插件消失、刷新恢复"。
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
      // 连累其后所有段（表现为汉堡+上传菜单一起消失、刷新又好）。
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
              //   面板行没有行内菜单，而 dsh 的下拉/命令面板/本插件上传菜单都可能
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
      try { dialogObs.observe(document.body, { childList: true, subtree: true }); }
      catch (e) { bootLog('dialogObs-fail:' + (e && e.message || e)); }

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
      // 12) 「上传来源」菜单（附件 / 相机）
      // =====================================================================
      // 需求：附件除了「手机文件」，还要能选**沙箱文件**（guest /root/projects），
      // 以及**直接拍照 / 录像**后作为附件上传。而 dsh 的 <input type="file"> 走系统
      // ACTION_GET_CONTENT，只列手机侧来源；PRoot 沙箱不是 Android 的
      // DocumentsProvider，永远不出现在系统选择器里；相机更是上游完全没有的能力
      // （上游全树无 capture / accept / image/*，需宿主自建通道）。
      //
      // ⚠️ 挂载点（重要）：
      //   **不得**挂到 tools 行的回形针/「+」上：上游 0.1.7 起把附件入口收进了
      //   「+」唤起的**命令面板**（其中一项叫「文件」），tools 行里已无独立回形针
      //   按钮 —— 按「隐藏 input 的前一个兄弟」识别会得到「+」本身，把菜单挂到
      //   「+」上并在捕获阶段吞掉事件，命令面板会被整个顶掉（"点加号误触发
      //   上传"）。
      //   做法：**拦隐藏 input 的 click**：无论用户从哪个入口（命令面板的「文件」、
      //   拖放、粘贴，或将来新增的入口）触发 input，我们都在默认动作（系统选择器）
      //   之前接管，弹出自己的来源菜单。这样既不依赖命令面板的 DOM 结构，也彻底
      //   不碰「+」——「+」完全归还 dsh。
      //
      // 来源偏好如何传给原生：本 WebView **没有 addJavascriptInterface 桥**，
      // 因此用隐藏 <input type="file"> 的 accept 属性作为唯一可用通道 ——
      // 打上哨兵 MIME 即代表对应来源，原生 WebChromeClient.onShowFileChooser
      // 读 fileChooserParams.acceptTypes 分流（沙箱 / 手机 / 拍照 / 录像）。
      //
      // ⚠️ accept 是**共享状态**：dsh 自有的上传路径若绕过我们的菜单直接
      // input.click()，哨兵值就会把它误判为我们的来源。所以哨兵值必须在每条
      // 结束路径上复位（见 resetUploadAccept 与捕获阶段 click 守卫）——
      // 不能假设「每次点击前都会重写，所以不存在残留」。
      //
      // 本段**不受「移动端适配」开关控制**：上传来源是功能能力而非布局适配，
      // 关掉适配（官方原版外观）时仍必须可用。
      var UPLOAD_SENTINEL = 'application/x-dshbox-sandbox-upload';
      var CAMERA_PHOTO_SENTINEL = 'application/x-dshbox-camera-photo';
      var CAMERA_VIDEO_SENTINEL = 'application/x-dshbox-camera-video';

      var UPLOAD_TEXT = {
        zh: {
          title: '添加附件',
          phone: '上传手机文件',
          phoneSub: '从系统「文件 / 相册」中选择',
          sandbox: '上传沙箱文件',
          sandboxSub: '从 DSHBox 工作区（/root/projects）选择',
          photo: '拍照上传',
          photoSub: '拍摄照片后作为附件上传',
          video: '录像上传',
          videoSub: '录制视频后作为附件上传',
          blocked: '暂不可上传：请先发送一条消息创建会话，或等待当前任务结束',
          noInput: '上传入口未就绪，请稍后重试',
        },
        en: {
          title: 'Add attachment',
          phone: 'Upload from phone',
          phoneSub: 'Pick from system Files / Photos',
          sandbox: 'Upload from sandbox',
          sandboxSub: 'Pick from the DSHBox workspace (/root/projects)',
          photo: 'Take a photo',
          photoSub: 'Capture a photo and attach it',
          video: 'Record a video',
          videoSub: 'Record a video and attach it',
          blocked: 'Cannot attach now: send a message to create a session, or wait for the current task to finish',
          noInput: 'Upload entry not ready, please retry',
        },
        ar: {
          title: 'إضافة مرفق',
          phone: 'رفع من الهاتف',
          phoneSub: 'اختر من «الملفات / الصور» في النظام',
          sandbox: 'رفع من البيئة المعزولة',
          sandboxSub: 'اختر من مساحة عمل DSHBox ‏(/root/projects)',
          photo: 'التقاط صورة',
          photoSub: 'التقط صورة وأرفقها',
          video: 'تسجيل فيديو',
          videoSub: 'سجّل مقطع فيديو وأرفقه',
          blocked: 'لا يمكن الإرفاق الآن: أرسل رسالة لإنشاء جلسة أو انتظر انتهاء المهمة الحالية',
          noInput: 'مدخل الرفع غير جاهز، حاول مرة أخرى',
        },
        es: {
          title: 'Añadir adjunto',
          phone: 'Subir desde el teléfono',
          phoneSub: 'Elegir de Archivos / Fotos del sistema',
          sandbox: 'Subir desde el sandbox',
          sandboxSub: 'Elegir del espacio de trabajo de DSHBox (/root/projects)',
          photo: 'Hacer una foto',
          photoSub: 'Captura una foto y adjúntala',
          video: 'Grabar un vídeo',
          videoSub: 'Graba un vídeo y adjúntalo',
          blocked: 'No se puede adjuntar ahora: envía un mensaje para crear una sesión o espera a que termine la tarea actual',
          noInput: 'La entrada de subida no está lista, inténtalo de nuevo',
        },
        fr: {
          title: 'Ajouter une pièce jointe',
          phone: 'Importer depuis le téléphone',
          phoneSub: 'Choisir dans Fichiers / Photos du système',
          sandbox: 'Importer depuis le bac à sable',
          sandboxSub: 'Choisir dans l\'espace de travail DSHBox (/root/projects)',
          photo: 'Prendre une photo',
          photoSub: 'Capturez une photo et joignez-la',
          video: 'Enregistrer une vidéo',
          videoSub: 'Enregistrez une vidéo et joignez-la',
          blocked: 'Impossible de joindre maintenant : envoyez un message pour créer une session ou attendez la fin de la tâche en cours',
          noInput: 'L\'entrée d\'import n\'est pas prête, réessayez',
        },
        ru: {
          title: 'Добавить вложение',
          phone: 'Загрузить с телефона',
          phoneSub: 'Выбрать из «Файлы / Фото» системы',
          sandbox: 'Загрузить из песочницы',
          sandboxSub: 'Выбрать из рабочей области DSHBox (/root/projects)',
          photo: 'Сделать фото',
          photoSub: 'Снимите фото и прикрепите его',
          video: 'Записать видео',
          videoSub: 'Запишите видео и прикрепите его',
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

      /**
       * composer 的隐藏 `<input type="file">` —— 上传来源哨兵的**唯一载体**，
       * 也是本段唯一的挂载依据（不再依赖任何按钮：见段首关于挂载点变更的说明）。
       *
       * 先在 composer 卡片内找；找不到再退回全文档 —— 上游在 composer 内只渲染
       * 这一个 file input，退回全文档是为了兼容它被移到卡片之外的结构调整。
       * 两条都落空时返回 null，调用方据此提示「上传入口未就绪」而不是静默失败。
       */
      function dshFileInput() {
        var card = dshComposerCard();
        var input = card ? card.querySelector('input[type="file"]') : null;
        return input || document.querySelector('input[type="file"]') || null;
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

      /**
       * 来源图标（内联 SVG，跟随主题 `currentColor`）。
       * 四个来源各一枚：沙箱 = 文件夹、手机 = 文档、拍照 = 相机、录像 = 摄像机。
       */
      var UPLOAD_ICONS = {
        sandbox: '<path d="M3 6.5A1.5 1.5 0 0 1 4.5 5h4.2l1.7 1.8H19.5A1.5 1.5 0 0 1 21 8.3v9.2A1.5 1.5 0 0 1 19.5 19h-15A1.5 1.5 0 0 1 3 17.5z"/>',
        phone: '<path d="M7 3.5h6.2L17 7.3V19.5a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4.5a1 1 0 0 1 1-1z M13 3.7V7.6H16.9"/>',
        'camera-photo': '<path d="M4 8.5A1.5 1.5 0 0 1 5.5 7h2.1l1.2-1.7a1 1 0 0 1 .8-.3h4.8a1 1 0 0 1 .8.4L16.4 7h2.1A1.5 1.5 0 0 1 20 8.5v9A1.5 1.5 0 0 1 18.5 19h-13A1.5 1.5 0 0 1 4 17.5z"/><circle cx="12" cy="13" r="3.2"/>',
        'camera-video': '<path d="M3.5 7.5A1.5 1.5 0 0 1 5 6h8a1.5 1.5 0 0 1 1.5 1.5v9A1.5 1.5 0 0 1 13 18H5a1.5 1.5 0 0 1-1.5-1.5z"/><path d="M14.5 11.2l5-2.7v7l-5-2.7z"/>',
      };

      function iconSvg(kind) {
        var inner = UPLOAD_ICONS[kind] || UPLOAD_ICONS.phone;
        return '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" '
          + 'stroke="currentColor" stroke-width="1.6" stroke-linecap="round" '
          + 'stroke-linejoin="round" aria-hidden="true">' + inner + '</svg>';
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

      /** 来源 → 传给原生的 accept 哨兵；空串表示「任意类型」（手机文件走系统选择器）。 */
      function sentinelFor(source) {
        if (source === 'sandbox') return UPLOAD_SENTINEL;
        if (source === 'camera-photo') return CAMERA_PHOTO_SENTINEL;
        if (source === 'camera-video') return CAMERA_VIDEO_SENTINEL;
        return '';
      }

      function pickSource(source) {
        var T = uploadText();
        var input = dshFileInput();
        // input.disabled ⟺ (subagent || locked || machineBusy || addFiles 未注入)——
        // 每次现读而不缓存，天然不会过期。
        if (!input || input.disabled) {
          uploadToast(input ? T.blocked : T.noInput);
          closeUploadMenu();
          return;
        }
        // 本次点击显式重写 accept：哨兵 MIME 即来源，手机文件用「无 accept」。
        var sentinel = sentinelFor(source);
        if (sentinel) input.setAttribute('accept', sentinel);
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

      // 哨兵值复位（两条结束路径，缺一都会污染 dsh 自有的上传入口）：
      //   ① change —— 用户选完文件；
      //   ② cancel —— 用户取消选择（Chromium 对 <input type=file> 派发 cancel）。
      document.addEventListener('change', function (ev) {
        var input = dshFileInput();
        if (input && ev.target === input) resetUploadAccept(input);
      }, true);
      document.addEventListener('cancel', function (ev) {
        var input = dshFileInput();
        if (ev.target === input) resetUploadAccept(input);
      }, true);

      // ③ 捕获阶段 click 守卫 —— 既是**本段的挂载点**，也兼哨兵复位。
      //
      //   dsh 自己的入口（命令面板的「文件」项、拖放、粘贴）触发 input.click() 时
      //   不经过 pickSource，因此在这里：
      //    · 先复位 accept —— 否则上一次留下的哨兵会让原生把这次点击误判成我们的来源；
      //    · 再拦住这次点击的默认动作（系统选择器），改弹我们自己的来源菜单。
      //   仅对「非 pickSource 发起」的点击生效，pickSource 的正常传递不受影响。
      document.addEventListener('click', function (ev) {
        var input = dshFileInput();
        if (!input || ev.target !== input) return;
        if (pickSourceActive) return;   // 本次点击由 pickSource 发起，accept 由它负责
        resetUploadAccept(input);
        ev.preventDefault();
        ev.stopPropagation();
        openUploadMenu();
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
        el.appendChild(row('camera-photo', 'camera-photo', T.photo, T.photoSub));
        el.appendChild(row('camera-video', 'camera-video', T.video, T.videoSub));
        document.body.appendChild(el);
        uploadMenu = el;
        return el;
      }

      /** 来源 → 文案键（会话内切换语言后重写菜单文案用）。 */
      var SOURCE_TEXT_KEYS = {
        phone: ['phone', 'phoneSub'],
        sandbox: ['sandbox', 'sandboxSub'],
        'camera-photo': ['photo', 'photoSub'],
        'camera-video': ['video', 'videoSub'],
      };

      function refreshUploadMenuText(el) {
        var T = uploadText();
        el.setAttribute('aria-label', T.title);
        var rows = el.querySelectorAll('button[data-source]');
        for (var i = 0; i < rows.length; i++) {
          var keys = SOURCE_TEXT_KEYS[rows[i].getAttribute('data-source')];
          if (!keys) continue;
          var spans = rows[i].querySelectorAll('span > span');
          if (spans.length === 2) {
            spans[0].textContent = T[keys[0]];
            spans[1].textContent = T[keys[1]];
          }
        }
      }

      function openUploadMenu() {
        var el = buildUploadMenu();
        refreshUploadMenuText(el);
        // 锚点：composer 卡片本身（挂载点已不再是某个按钮，见段首说明）。
        // 定位在卡片正上方、与卡片左对齐，超出视口自动纠偏；卡片不可得时贴底居中。
        el.style.opacity = '0';
        el.style.transform = 'translateY(6px) scale(.98)';
        el.style.pointerEvents = 'none';
        el.style.left = '-9999px';
        el.style.top = '0px';
        var mw = el.offsetWidth;
        var mh = el.offsetHeight;
        var card = dshComposerCard();
        var r = card ? card.getBoundingClientRect() : null;
        var anchorLeft = r ? r.left : (window.innerWidth - mw) / 2;
        var anchorTop = r ? r.top : window.innerHeight - mh - 12;
        var anchorBottom = r ? r.bottom : anchorTop + mh;
        var left = Math.min(Math.max(8, anchorLeft), Math.max(8, window.innerWidth - mw - 8));
        var top = anchorTop - mh - 8;
        if (top < 8) top = Math.min(window.innerHeight - mh - 8, anchorBottom + 8);
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

      // 挂载点：本段不再需要"拦某个按钮"的安装步骤。
      // 拦截发生在上面那条**捕获阶段 click 守卫**里（针对隐藏 input），
      // 因此「+」与命令面板都完全归 dsh —— 我们只在用户真正要选文件的那一刻介入。
      //
      // 这样做的另一个好处：不依赖命令面板的 DOM 结构（菜单项 id 是索引型的
      // `dsh-slash-option-command-<i>`，上游增删命令即漂移），也不依赖任何按钮文案。
      // 无论上游把附件入口放在哪、叫什么名字，只要它最终点的是那个 file input，
      // 本段就仍然有效。

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

    };

    return module.exports;
  },
});
