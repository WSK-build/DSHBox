/* ============================================================
   DSHBox website — main.js
   Native JS only. Handles:
   1. EN/中文 language toggle (CSS-driven via <html lang>)
   2. Latest-release resolution via GitHub Releases API
      (30-minute localStorage cache, fallback to Releases page)
   3. "Copy download link" buttons
   4. Mobile navigation toggle + header scroll state
   5. Scroll-reveal animations (respects prefers-reduced-motion)
   ============================================================ */

(function () {
  "use strict";

  var REPOSITORY = "WSK-build/DSHBox";
  var RELEASE_API = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
  var RELEASE_FALLBACK = "https://github.com/" + REPOSITORY + "/releases/latest";
  var CACHE_KEY = "dshbox-latest-release";
  var CACHE_DURATION = 30 * 60 * 1000;
  var LANG_KEY = "dshbox-lang";

  /* ---------------------------------------------------------
     i18n strings for JS-rendered dynamic texts
     --------------------------------------------------------- */

  var STR = {
    en: {
      download: "Download APK",
      viewLatest: "View latest release",
      checking: "Checking the latest release…",
      latestOnGitHub: "Latest release on GitHub",
      copy: "Copy download link",
      copied: "Copied \u2713",
      copyFailed: "Copy failed"
    },
    zh: {
      download: "下载 APK",
      viewLatest: "查看最新 Release",
      checking: "正在获取最新版本…",
      latestOnGitHub: "最新版本见 GitHub",
      copy: "复制下载链接",
      copied: "已复制 \u2713",
      copyFailed: "复制失败"
    }
  };

  function t() {
    return document.documentElement.lang === "zh" ? STR.zh : STR.en;
  }

  var downloadButtons = Array.prototype.slice.call(
    document.querySelectorAll("[id^='download-apk']")
  );
  var releaseInfos = Array.prototype.slice.call(
    document.querySelectorAll("[id^='release-information']")
  );
  var copyButtons = Array.prototype.slice.call(
    document.querySelectorAll("[id^='copy-download-link']")
  );

  /* releaseState: null = still checking, {tag,size} = resolved,
     "fallback" = API unavailable */
  var releaseState = null;
  var currentApkUrl = RELEASE_FALLBACK;

  /* ---------------------------------------------------------
     1. Language toggle
     --------------------------------------------------------- */

  function setLang(lang, persist) {
    document.documentElement.lang = lang === "zh" ? "zh" : "en";
    if (persist) {
      try { localStorage.setItem(LANG_KEY, document.documentElement.lang); } catch (e) { /* optional */ }
    }
    var pressed = document.querySelectorAll(".lang-btn");
    Array.prototype.forEach.call(pressed, function (btn) {
      btn.setAttribute("aria-pressed", btn.getAttribute("data-lang-btn") === document.documentElement.lang ? "true" : "false");
    });
    /* 日期按 locale 重渲染（Release 说明正文来自 GitHub，保持维护者原语言，不翻译） */
    if (lastRelease) applyReleaseNotes(lastRelease);
    renderDynamic();
  }

  document.querySelectorAll(".lang-btn").forEach(function (btn) {
    btn.addEventListener("click", function () {
      setLang(btn.getAttribute("data-lang-btn"), true);
    });
  });

  setLang(document.documentElement.lang === "zh" ? "zh" : "en", false);

  /* ---------------------------------------------------------
     2. Latest release
     --------------------------------------------------------- */

  function formatBytes(bytes) {
    if (!Number.isFinite(bytes) || bytes <= 0) return "";
    var megabytes = bytes / 1024 / 1024;
    return megabytes.toFixed(1) + " MB";
  }

  function selectApk(assets) {
    var apkAssets = (assets || []).filter(function (asset) {
      return asset.name.toLowerCase().endsWith(".apk");
    });
    return (
      apkAssets.find(function (asset) {
        return /arm64|aarch64/i.test(asset.name);
      }) ||
      apkAssets[0] ||
      null
    );
  }

  function applyRelease(release) {
    var apk = selectApk(release.assets);
    if (!apk) {
      throw new Error("No APK asset was found in the latest release.");
    }
    currentApkUrl = apk.browser_download_url;
    releaseState = {
      tag: release.tag_name,
      size: formatBytes(apk.size)
    };
    lastRelease = release;
    applyReleaseNotes(release);
    renderDynamic();
  }

  function applyFallback() {
    currentApkUrl = RELEASE_FALLBACK;
    releaseState = "fallback";
    /* 保留 HTML 里的静态兜底文案，不覆盖 */
    renderDynamic();
  }

  /* ---------------------------------------------------------
     2b. 「最新版本变化」板块（从 Release 说明自动生成）
     ---------------------------------------------------------
     数据源 = 同一个 GitHub Releases API 的 `body` 字段（Markdown）。
     因此**无需手工维护**：发布新 Release 时把要点写进 Release 说明，
     网站会自动更新（30 分钟缓存，见 CACHE_DURATION）。

     解析策略 —— 按实测到的真实写法分两趟：

       第一趟：**加粗标题 + 冒号说明** 的段落行
               （如 `**多语言适配**：界面暂且支持六种语言…`）
               ← 这是本项目 Release 说明的实际主结构，优先采用
       第二趟：Markdown 要点行（`- ` / `* ` / `+ `）
               ← 仅在上一趟没找到任何条目时启用（兼容另一种写法）

     排除项：标题行（`#`）、表格行（`|`）、纯链接、图片、
             git 自动生成的 "Full Changelog" 行。
     解析不出任何条目时**隐藏板块**（不显示空框），保留 HTML 静态兜底。 */

  var NOTES_FILES = Array.prototype.slice.call(
    /* 注意用 aside[id^='whats-new'] 而非裸的 [id^='whats-new']：
       板块内的标题元素 id 是 whats-new-*-title，也会被前缀选择器命中，
       导致标题被当成板块误加 data-state="empty"（实测踩到）。 */
    document.querySelectorAll("aside[id^='whats-new']")
  );
  var lastRelease = null;   /* 最近一次成功获取的 release（供语言切换时重渲染日期） */
  var MAX_NOTES = 5;       /* 解析上限：下载区展示满这个数 */
  var MAX_LEN = 160;       /* 单条正文解析上限（超出截断加省略号） */

  /* 各板块的展示深度。
     hero 是首屏里的精简卡，条目多了会把首页撑到两屏以上（手机 390×844 实测：
     5 条满长时 hero 高 1958px ≈ 2.3 屏），所以只留 2 条、单条也收紧到 80 字；
     下载区是「先看变化再下载」的完整语境，给满 5 条。
     两处共用同一份解析结果，只是截取深度不同。 */
  function sectionLimits(section) {
    return section.classList.contains("whats-new--hero")
      ? { notes: 2, len: 80 }
      : { notes: MAX_NOTES, len: MAX_LEN };
  }

  /** 按板块长度上限重整单条（解析期已截过一次，这里可能截得更短）。 */
  function reshapeNote(note, limit) {
    if (!note.body || note.body.length <= limit) return note;
    /* 去掉解析期已加的省略号，否则二次截断会留下「……」 */
    var base = note.body.replace(/\u2026$/, "");
    return { title: note.title, body: truncate(base, limit) };
  }

  /** 去掉行内 Markdown 记号，保留可读文本。 */
  function stripMarkdown(text) {
    return String(text)
      .replace(/!\[[^\]]*\]\([^)]*\)/g, "")        /* 图片 */
      .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")     /* 链接 → 文字 */
      .replace(/`([^`]+)`/g, "$1")                 /* 行内代码 */
      .replace(/\*\*([^*]+)\*\*/g, "$1")           /* 加粗 */
      .replace(/(^|\s)\*([^*]+)\*/g, "$1$2")       /* 斜体 */
      .replace(/~~([^~]+)~~/g, "$1")               /* 删除线 */
      .replace(/^\s*#+\s*/, "")                    /* 行首标题记号 */
      .replace(/\s+/g, " ")
      .trim();
  }

  function truncate(text, limit) {
    if (text.length <= limit) return text;
    return text.slice(0, limit - 1).replace(/[\s,;:，；：、-]+$/, "") + "\u2026";
  }

  /** 该行内容是否值得作为一条要点（排除元信息/自动 changelog/纯链接）。 */
  function isUsefulNote(text) {
    if (!text || text.length < 4) return false;
    if (/^(full changelog|changelog|compare)\b/i.test(text)) return false;
    if (/^https?:\/\//i.test(text)) return false;
    return true;
  }

  /** 该行是否应整体跳过（标题/表格/分隔线/引用等结构行）。 */
  function isStructuralLine(line) {
    var s = line.trim();
    if (!s) return true;
    if (s.charAt(0) === "|") return true;                    /* 表格 */
    if (/^#{1,6}\s/.test(s)) return true;                    /* 标题 */
    if (/^(-{3,}|\*{3,}|_{3,})$/.test(s)) return true;       /* 分隔线 */
    if (/^>\s?/.test(s)) return true;                        /* 引用 */
    if (/^```/.test(s)) return true;                         /* 代码围栏 */
    return false;
  }

  /**
   * 从 Release 说明里抽取要点。
   * @returns {Array<{title: string, body: string}>}
   */
  function parseReleaseNotes(body) {
    if (!body) return [];
    /* 兼容 CRLF 与偶发裸 CR（GitHub 返回的 body 实测为 CRLF） */
    var lines = String(body).split(/\r\n|\r|\n/);
    var bold = [];
    var bullets = [];

    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (isStructuralLine(line)) continue;

      /* 第一趟素材：**标题**：说明 */
      var mBold = line.match(/^\s*\*\*([^*]+)\*\*\s*[：:]\s*(.+)$/);
      if (mBold) {
        var bTitle = stripMarkdown(mBold[1]);
        var bBody = stripMarkdown(mBold[2]);
        if (bTitle && isUsefulNote(bBody)) {
          bold.push({ title: bTitle, body: truncate(bBody, MAX_LEN) });
          continue;
        }
      }

      /* 第二趟素材：- 要点（含首段加粗标题的形态） */
      var mBullet = line.match(/^\s*[-*+]\s+(.+)$/);
      if (mBullet) {
        var content = mBullet[1];
        var inner = content.match(/\*\*([^*]+)\*\*/);
        var title = inner ? stripMarkdown(inner[1]) : "";
        var plain = stripMarkdown(content);
        if (!isUsefulNote(plain)) continue;
        var rest = plain;
        if (title && plain.indexOf(title) === 0) {
          rest = plain.slice(title.length).replace(/^[\s:：—\-·]+/, "");
        }
        bullets.push({
          title: title,
          body: truncate(rest || plain, MAX_LEN)
        });
      }
    }

    /* 优先用「加粗标题」结构；没有才退回要点列表 */
    var chosen = bold.length ? bold : bullets;
    return chosen.slice(0, MAX_NOTES);
  }

  /** 渲染一条要点（标题 + 正文；无标题时只渲染正文）。 */
  function buildNoteItem(note) {
    var li = document.createElement("li");
    if (note.title) {
      var strong = document.createElement("strong");
      strong.textContent = note.title;
      li.appendChild(strong);
      if (note.body) li.appendChild(document.createTextNode(" — " + note.body));
    } else {
      li.textContent = note.body;
    }
    return li;
  }

  function applyReleaseNotes(release) {
    var notes = parseReleaseNotes(release && release.body);
    var tag = (release && release.tag_name) || "";
    var dateText = "";
    if (release && release.published_at) {
      var d = new Date(release.published_at);
      if (!isNaN(d.getTime())) {
        var lang = document.documentElement.lang === "zh" ? "zh-CN" : "en-US";
        dateText = d.toLocaleDateString(lang, { year: "numeric", month: "long", day: "numeric" });
      }
    }

    NOTES_FILES.forEach(function (section) {
      var list = section.querySelector("[data-release-list]");
      var badge = section.querySelector("[data-release-tag]");
      var dateEl = section.querySelector("[data-release-date]");
      var link = section.querySelector("[data-release-link]");

      if (badge && tag) badge.textContent = tag;
      if (dateEl && dateText) {
        dateEl.textContent = dateText;
        dateEl.hidden = false;
      }
      if (link) {
        link.href = release && release.html_url
          ? release.html_url
          : RELEASE_FALLBACK;
      }

      /* 解析不出要点 → 隐藏板块（保留 HTML 的静态兜底内容不被清空） */
      if (!notes.length || !list) {
        section.setAttribute("data-state", "empty");
        return;
      }
      var limits = sectionLimits(section);
      list.textContent = "";
      notes.slice(0, limits.notes).forEach(function (note) {
        list.appendChild(buildNoteItem(reshapeNote(note, limits.len)));
      });
      section.removeAttribute("data-state");
    });
  }

  function renderDynamic() {
    var s = t();

    downloadButtons.forEach(function (button) {
      if (releaseState && releaseState !== "fallback") {
        button.href = currentApkUrl;
        button.textContent = s.download + " \u00B7 " + releaseState.tag;
      } else if (releaseState === "fallback") {
        button.href = RELEASE_FALLBACK;
        button.textContent = s.viewLatest;
      }
      /* while checking (null), keep the initial HTML markup untouched */
    });

    releaseInfos.forEach(function (el) {
      if (releaseState && releaseState !== "fallback") {
        el.textContent = [releaseState.tag, releaseState.size, "Android ARM64"]
          .filter(Boolean)
          .join(" \u00B7 ");
      } else if (releaseState === "fallback") {
        el.textContent = s.latestOnGitHub;
      }
    });

    copyButtons.forEach(function (button) {
      if (button.getAttribute("data-busy") !== "true") {
        button.textContent = s.copy;
      }
    });

    refreshDotLabels();
  }

  /**
   * 页面自带的版本号 —— 取 HTML 里 JSON-LD 的 softwareVersion（构建时写入，JS 从不修改）。
   *
   * 用途：识别「**新 HTML + 旧缓存**」。localStorage 里的 release 有 30 分钟有效期，
   * 而站点 HTML 每次发版都更新；若用户在发版后立刻访问，缓存里还留着上一版的
   * release 数据，而 loadLatestRelease() 是**缓存优先**的 —— 于是
   * 页面明明已经是新版本（下载按钮已指向新 APK），
   * 「最新版本变化」板块却还显示上一版的内容，看起来像"官网没更新"。
   * 实测踩到：v1.3.1 发布后官网仍显示 v1.3.0 的更新要点。
   */
  function pageVersion() {
    try {
      var ld = document.querySelector('script[type="application/ld+json"]');
      if (!ld) return null;
      var m = /"softwareVersion"\s*:\s*"([^"]+)"/.exec(ld.textContent);
      return m ? m[1] : null;
    } catch (e) {
      return null;
    }
  }

  /** 版本号按数值段比较：返回 -1/0/1；无法解析时返回 0（视为不可比）。 */
  function compareVersions(a, b) {
    var pa = String(a || "").replace(/^v/i, "").split(".");
    var pb = String(b || "").replace(/^v/i, "").split(".");
    for (var i = 0; i < Math.max(pa.length, pb.length); i++) {
      var na = parseInt(pa[i], 10), nb = parseInt(pb[i], 10);
      if (isNaN(na) || isNaN(nb)) return 0;
      if (na !== nb) return na < nb ? -1 : 1;
    }
    return 0;
  }

  function readCache() {
    try {
      var cached = JSON.parse(localStorage.getItem(CACHE_KEY));
      if (!cached || Date.now() - cached.savedAt > CACHE_DURATION) return null;
      /* 缓存比页面自带版本还旧 → 作废，走网络重新拉取（见 pageVersion 注释）。
         相等或更新则照用缓存，省掉一次 API 调用。 */
      var pv = pageVersion();
      if (pv && cached.release && cached.release.tag_name &&
          compareVersions(cached.release.tag_name, pv) < 0) {
        return null;
      }
      return cached.release;
    } catch (e) {
      return null;
    }
  }

  function writeCache(release) {
    try {
      localStorage.setItem(
        CACHE_KEY,
        JSON.stringify({ savedAt: Date.now(), release: release })
      );
    } catch (e) {
      /* Local storage is optional. Download still works without it. */
    }
  }

  function loadLatestRelease() {
    var cachedRelease = readCache();
    if (cachedRelease) {
      try {
        applyRelease(cachedRelease);
        return;
      } catch (e) {
        /* fall through to network */
      }
    }

    fetch(RELEASE_API, {
      headers: { Accept: "application/vnd.github+json" }
    })
      .then(function (response) {
        if (!response.ok) {
          throw new Error("GitHub API returned " + response.status);
        }
        return response.json();
      })
      .then(function (release) {
        applyRelease(release);
        writeCache(release);
      })
      .catch(function (error) {
        console.warn("Unable to resolve the latest DSHBox APK:", error);
        applyFallback();
      });
  }

  if (downloadButtons.length) {
    loadLatestRelease();
  }

  /* ---------------------------------------------------------
     3. Copy download link
     --------------------------------------------------------- */

  function flashButton(button, text, copiedStyle) {
    button.setAttribute("data-busy", "true");
    button.textContent = text;
    if (copiedStyle) button.classList.add("is-copied");
    window.setTimeout(function () {
      button.classList.remove("is-copied");
      button.removeAttribute("data-busy");
      button.textContent = t().copy;
    }, 2000);
  }

  copyButtons.forEach(function (button) {
    button.addEventListener("click", function () {
      var url = currentApkUrl;
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard
          .writeText(url)
          .then(function () {
            flashButton(button, t().copied, true);
          })
          .catch(function () {
            flashButton(button, t().copyFailed, false);
          });
      } else {
        flashButton(button, t().copyFailed, false);
      }
    });
  });

  /* ---------------------------------------------------------
     4. Mobile navigation + header scroll state
     --------------------------------------------------------- */

  var navToggle = document.getElementById("nav-toggle");
  var siteNav = document.getElementById("site-nav");

  if (navToggle && siteNav) {
    navToggle.addEventListener("click", function () {
      var isOpen = siteNav.classList.toggle("is-open");
      navToggle.setAttribute("aria-expanded", isOpen ? "true" : "false");
    });

    siteNav.addEventListener("click", function (event) {
      if (event.target && event.target.tagName === "A") {
        siteNav.classList.remove("is-open");
        navToggle.setAttribute("aria-expanded", "false");
      }
    });
  }

  var header = document.querySelector(".site-header");
  function onScroll() {
    if (!header) return;
    header.classList.toggle("is-scrolled", window.scrollY > 8);
    if (pageDotButtons && pageDotButtons.length) {
      setActiveDot(currentSnapIndex());
    }
  }
  window.addEventListener("scroll", onScroll, { passive: true });
  onScroll();

  /* ---------------------------------------------------------
     5. Scroll reveal
     --------------------------------------------------------- */

  var reducedMotion =
    window.matchMedia &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  var revealElements = Array.prototype.slice.call(
    document.querySelectorAll(".reveal")
  );

  if (reducedMotion || !("IntersectionObserver" in window)) {
    revealElements.forEach(function (el) {
      el.classList.add("is-visible");
    });
  } else {
    var observer = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) {
            entry.target.classList.add("is-visible");
            observer.unobserve(entry.target);
          }
        });
      },
      { rootMargin: "0px 0px -8% 0px", threshold: 0.08 }
    );
    revealElements.forEach(function (el) {
      observer.observe(el);
    });
  }

  /* ---------------------------------------------------------
     6. Section navigator (right-side page dots)
     1.3.1：此处原为「整页翻页」——桌面按一次滚轮/PageDown 就翻到下一区块，
     并配合 CSS 的 scroll-snap-stop:always 在手机上「一次手势前进一格」。
     手机实测手指滑 100px、页面跳 545px，体验很差，故**整块移除翻页行为**，
     只保留右侧圆点作为**锚点快速跳转**（点击平滑滚到对应区块，并随滚动高亮）。
     --------------------------------------------------------- */

  var snapPages = Array.prototype.slice.call(
    document.querySelectorAll("[data-snap]")
  );

  var STR_PAGES = {
    en: ["Home", "Features", "Screenshots", "How it works", "Requirements", "Download", "FAQ"],
    zh: ["首页", "功能", "截图", "工作原理", "系统要求", "下载", "常见问题"]
  };

  var pageDotsNav = null;
  var pageDotButtons = [];
  /* reducedMotion 已在第 5 节（滚动显现）定义，此处复用同一变量 */

  function refreshDotLabels() {
    if (!pageDotButtons || !pageDotButtons.length) return;
    var names = document.documentElement.lang === "zh" ? STR_PAGES.zh : STR_PAGES.en;
    pageDotButtons.forEach(function (dot, index) {
      dot.setAttribute("aria-label", names[index] || String(index + 1));
    });
  }

  function setActiveDot(index) {
    pageDotButtons.forEach(function (dot, i) {
      dot.classList.toggle("is-active", i === index);
    });
  }

  /** 当前视口中心所在的区块索引（用于高亮圆点）。 */
  function currentSnapIndex() {
    var mid = window.scrollY + window.innerHeight / 2;
    var index = 0;
    snapPages.forEach(function (page, i) {
      var top = page.getBoundingClientRect().top + window.scrollY;
      if (top <= mid + 1) index = i;
    });
    return index;
  }

  /** 平滑滚动到指定区块（仅锚点跳转，不再"翻页"）。 */
  function goToSnapPage(index) {
    index = Math.max(0, Math.min(snapPages.length - 1, index));
    snapPages[index].scrollIntoView({
      behavior: reducedMotion ? "auto" : "smooth",
      block: "start"
    });
    setActiveDot(index);
  }

  if (snapPages.length > 1) {
    pageDotsNav = document.createElement("nav");
    pageDotsNav.className = "page-dots";
    pageDotsNav.setAttribute("aria-label", "Page navigation");
    snapPages.forEach(function (page, index) {
      var dot = document.createElement("button");
      dot.type = "button";
      dot.addEventListener("click", function () {
        goToSnapPage(index);
      });
      pageDotsNav.appendChild(dot);
      pageDotButtons.push(dot);
    });
    document.body.appendChild(pageDotsNav);
    refreshDotLabels();
    setActiveDot(currentSnapIndex());

    /* 随滚动高亮当前区块（用 rAF 节流，避免滚动时频繁回调） */
    var dotTick = false;
    window.addEventListener(
      "scroll",
      function () {
        if (dotTick) return;
        dotTick = true;
        window.requestAnimationFrame(function () {
          setActiveDot(currentSnapIndex());
          dotTick = false;
        });
      },
      { passive: true }
    );

    /* 键盘：Home/End 跳到首尾（保留无障碍能力，移除 PageUp/PageDown 的强制翻页） */
    window.addEventListener("keydown", function (event) {
      if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return;
      var active = document.activeElement;
      if (active && (active.tagName === "INPUT" || active.tagName === "TEXTAREA" ||
          active.tagName === "SELECT" || active.isContentEditable)) return;
      if (event.key === "Home") goToSnapPage(0);
      else if (event.key === "End") goToSnapPage(snapPages.length - 1);
    });
  }
})();
