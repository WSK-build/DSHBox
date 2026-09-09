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
    renderDynamic();
  }

  function applyFallback() {
    currentApkUrl = RELEASE_FALLBACK;
    releaseState = "fallback";
    renderDynamic();
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

  function readCache() {
    try {
      var cached = JSON.parse(localStorage.getItem(CACHE_KEY));
      if (!cached || Date.now() - cached.savedAt > CACHE_DURATION) return null;
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
     6. Full-page slide navigation + page dots
     Desktop: one wheel tick smoothly flips to the next page.
     Touch devices: native scrolling with CSS scroll-snap.
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
  var snapLockUntil = 0;

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

  function snapPageTops() {
    return snapPages.map(function (page) {
      return page.getBoundingClientRect().top + window.scrollY;
    });
  }

  function currentSnapIndex() {
    var tops = snapPageTops();
    var mid = window.scrollY + window.innerHeight / 2;
    var index = 0;
    for (var i = 0; i < tops.length; i++) {
      if (tops[i] <= mid + 1) index = i;
    }
    return index;
  }

  function goToSnapPage(index) {
    index = Math.max(0, Math.min(snapPages.length - 1, index));
    snapLockUntil = Date.now() + 900;
    snapPages[index].scrollIntoView({
      behavior: reducedMotion ? "auto" : "smooth",
      block: "start"
    });
    setActiveDot(index);
  }

  /* Returns true when the gesture was converted into a page flip. */
  function snapAttempt(direction) {
    var index = currentSnapIndex();
    var rect = snapPages[index].getBoundingClientRect();
    /* More of the current page is still off-screen — scroll natively. */
    if (direction > 0 && rect.bottom > window.innerHeight + 2) return false;
    if (direction < 0 && rect.top < -2) return false;
    var target = index + direction;
    if (target < 0 || target >= snapPages.length) return false;
    goToSnapPage(target);
    return true;
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

    var finePointer =
      window.matchMedia && window.matchMedia("(pointer: fine)").matches;

    if (finePointer && !reducedMotion) {
      window.addEventListener(
        "wheel",
        function (event) {
          if (event.ctrlKey || event.defaultPrevented) return;
          if (siteNav && siteNav.classList.contains("is-open")) return;
          var delta = event.deltaY * (event.deltaMode === 1 ? 16 : 1);
          if (Date.now() < snapLockUntil) {
            /* Swallow inertia wheel events during a page transition. */
            if (delta !== 0) event.preventDefault();
            return;
          }
          if (Math.abs(delta) < 12) return;
          if (snapAttempt(delta > 0 ? 1 : -1)) event.preventDefault();
        },
        { passive: false }
      );

      window.addEventListener("keydown", function (event) {
        if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return;
        var active = document.activeElement;
        if (active && (active.tagName === "INPUT" || active.tagName === "TEXTAREA" ||
            active.tagName === "SELECT" || active.isContentEditable)) return;
        var target = null;
        if (event.key === "PageDown") target = currentSnapIndex() + 1;
        else if (event.key === "PageUp") target = currentSnapIndex() - 1;
        else if (event.key === "Home") target = 0;
        else if (event.key === "End") target = snapPages.length - 1;
        if (target === null) return;
        event.preventDefault();
        goToSnapPage(target);
      });
    }
  }
})();
