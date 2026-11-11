/*
 * APP-owned DSH page compatibility.
 *
 * This resource is installed by a renderer adapter in the page world at
 * document_start. It only acts on the exact configured top-level DSH origin.
 */
(function () {
  'use strict';

  var CONFIG_KEY_TEXT = 'com.dshbox.renderer.dsh-page-compat-config.v1';
  var INSTALL_KEY_TEXT = 'com.dshbox.renderer.dsh-page-compat.v1';
  var OPEN_SETTINGS_DOCUMENT_URL = 'dshbox://open-settings-document';
  var OPEN_EXTERNAL_URL_PREFIX = 'dshbox://open-external?url=';
  var OPEN_DOCUMENT_LABELS = [
    '\u6253\u5f00\u914d\u7f6e\u6587\u4ef6',
    'Open configuration file'
  ];
  /*
   * Fallback grace window after a replayed Enter: how long to wait before the
   * interceptor checks the editor text and mutates it if the replay did not
   * take. Long enough for Lexical's own dispatch to run, short enough that
   * the user never perceives the delay.
   */
  var ENTER_NEWLINE_FALLBACK_DELAY_MS = 100;

  if (typeof Symbol !== 'function' || typeof Symbol.for !== 'function') return;
  var globalObject = typeof globalThis === 'object' ? globalThis : window;
  var configKey = Symbol.for(CONFIG_KEY_TEXT);
  var config = globalObject[configKey];
  try { delete globalObject[configKey]; } catch (_) {}

  if (!config || typeof config.trustedOrigin !== 'string' ||
      config.trustedOrigin === '') {
    return;
  }

  var isTopLevel = false;
  try { isTopLevel = window.top === window; } catch (_) {}
  if (!isTopLevel) return;

  var currentOrigin = '';
  try { currentOrigin = window.location.origin; } catch (_) {}
  if (currentOrigin !== config.trustedOrigin) return;

  var installKey = Symbol.for(INSTALL_KEY_TEXT);
  if (globalObject[installKey]) return;
  try {
    installUrlHostCompat();
    installExternalWindowBridge();
    installOpenDocumentBridge();
    installEnterKeyNewline();
  } catch (_) {
    return;
  }

  // The gate means "all installers completed", not merely "installation
  // started". A transient constructor/accessor failure leaves the document
  // retryable on a later injected pass.
  try {
    Object.defineProperty(globalObject, installKey, {
      configurable: false,
      enumerable: false,
      writable: false,
      value: Object.freeze({
        trustedOrigin: config.trustedOrigin
      })
    });
  } catch (_) {}

  /*
   * Legacy WebView treats a non-special scheme authority as path text. Repair
   * only the exact broken view: dsh-resource://<authority>/<path> parsed as
   * hostname="" and pathname="//<authority>/<path>". A compliant parser is
   * never replaced, and other schemes cannot enter the repair path.
   */
  function installUrlHostCompat() {
    var NativeURL = window.URL;
    if (typeof NativeURL !== 'function') return;

    try {
      if (new NativeURL('dsh-resource://file/x').hostname === 'file') return;
    } catch (_) {
      return;
    }

    function affectedParts(url) {
      try {
        if (url.protocol !== 'dsh-resource:' || url.hostname !== '') return null;
        var pathname = url.pathname;
        if (typeof pathname !== 'string' || pathname.slice(0, 2) !== '//') {
          return null;
        }
        var rest = pathname.slice(2);
        if (rest === '') return null;
        var slash = rest.indexOf('/');
        var authority = slash < 0 ? rest : rest.slice(0, slash);
        if (authority === '') return null;
        return {
          authority: authority,
          pathname: slash < 0 ? '' : rest.slice(slash)
        };
      } catch (_) {
        return null;
      }
    }

    function authorityView(authority) {
      var at = authority.lastIndexOf('@');
      var userinfo = at < 0 ? '' : authority.slice(0, at);
      var hostPort = at < 0 ? authority : authority.slice(at + 1);
      var username = '';
      var password = '';
      if (userinfo !== '') {
        var userinfoColon = userinfo.indexOf(':');
        if (userinfoColon < 0) {
          username = userinfo;
        } else {
          username = userinfo.slice(0, userinfoColon);
          password = userinfo.slice(userinfoColon + 1);
        }
      }

      var hostname = hostPort;
      var port = '';
      if (hostPort.charAt(0) === '[') {
        var close = hostPort.indexOf(']');
        if (close >= 0) {
          hostname = hostPort.slice(0, close + 1);
          if (hostPort.charAt(close + 1) === ':') {
            port = hostPort.slice(close + 2);
          }
        }
      } else {
        var colon = hostPort.lastIndexOf(':');
        if (colon >= 0 && /^\d*$/.test(hostPort.slice(colon + 1))) {
          hostname = hostPort.slice(0, colon);
          port = hostPort.slice(colon + 1);
        }
      }
      return {
        host: port === '' ? hostname : hostname + ':' + port,
        hostname: hostname,
        password: password,
        port: port,
        username: username
      };
    }

    function repaired(url) {
      var parts = affectedParts(url);
      if (!parts) return url;
      var authority = authorityView(parts.authority);
      return new Proxy(url, {
        get: function (target, property) {
          if (property === 'hostname') return authority.hostname;
          if (property === 'host') return authority.host;
          if (property === 'port') return authority.port;
          if (property === 'username') return authority.username;
          if (property === 'password') return authority.password;
          if (property === 'pathname') return parts.pathname;
          if (property === 'href') return target.href;
          if (property === 'constructor') return CompatURL;
          var value = target[property];
          return typeof value === 'function' ? value.bind(target) : value;
        }
      });
    }

    /*
     * RFC 3986 section 5.2 merge/remove-dot-segments, implemented locally so
     * the repair never routes through a synthetic HTTP URL.
     *
     * WHATWG URLs also treat a segment composed only of "%2e" as "." or "..".
     * We mirror that narrow rule so the repaired view matches a compliant
     * native URL; percent-encoded text that is not an exact dot segment is
     * preserved. A no-authority dsh-resource:/path base stays no-authority.
     */
    function parseDshResourceBase(baseHref) {
      var match = /^dsh-resource:(?:\/\/([^/?#]*))?([^?#]*)(?:\?([^#]*))?(?:#(.*))?$/i
        .exec(baseHref);
      if (!match) return null;
      var base = {
        authority: match[1] === undefined ? '' : match[1],
        hasAuthority: match[1] !== undefined,
        path: match[2] || '',
        query: match[3] === undefined ? null : match[3],
        fragment: match[4]
      };
      // Native URL rejects relative resolution against rootless opaque bases
      // such as dsh-resource:path; leave those to the engine.
      if (!base.hasAuthority && base.path.charAt(0) !== '/') return null;
      return base;
    }

    function normalizePercentDotSegments(pathname) {
      return pathname.split('/').map(function (segment) {
        var decoded = segment.replace(/%2e/ig, '.');
        return decoded === '.' || decoded === '..' ? decoded : segment;
      }).join('/');
    }

    function removeLastSegment(output) {
      var slash = output.lastIndexOf('/');
      return slash < 0 ? '' : output.slice(0, slash);
    }

    function removeDotSegments(pathname) {
      var rest = normalizePercentDotSegments(pathname);
      var output = '';
      while (rest.length > 0) {
        if (rest.slice(0, 3) === '../') {
          rest = rest.slice(3);
        } else if (rest.slice(0, 2) === './') {
          rest = rest.slice(2);
        } else if (rest.slice(0, 3) === '/./') {
          rest = '/' + rest.slice(3);
        } else if (rest === '/.') {
          rest = '/';
        } else if (rest.slice(0, 4) === '/../') {
          rest = '/' + rest.slice(4);
          output = removeLastSegment(output);
        } else if (rest === '/..') {
          rest = '/';
          output = removeLastSegment(output);
        } else if (rest === '.' || rest === '..') {
          rest = '';
        } else {
          var nextSlash = rest.charAt(0) === '/'
            ? rest.indexOf('/', 1)
            : rest.indexOf('/');
          if (nextSlash < 0) {
            output += rest;
            rest = '';
          } else {
            output += rest.slice(0, nextSlash);
            rest = rest.slice(nextSlash);
          }
        }
      }
      return output;
    }

    function mergePaths(base, inputPath) {
      if (base.hasAuthority && base.path === '') return '/' + inputPath;
      var slash = base.path.lastIndexOf('/');
      return slash < 0 ? inputPath : base.path.slice(0, slash + 1) + inputPath;
    }

    function resolveRelative(input, baseHref) {
      var value;
      try { value = String(input); } catch (_) { return null; }
      if (/^[A-Za-z][A-Za-z0-9+.-]*:/.test(value)) return null;

      var base = parseDshResourceBase(baseHref);
      if (!base) return null;

      var hashAt = value.indexOf('#');
      var beforeHash = hashAt < 0 ? value : value.slice(0, hashAt);
      var hash = hashAt < 0 ? null : value.slice(hashAt + 1);
      var queryAt = beforeHash.indexOf('?');
      var pathInput = queryAt < 0 ? beforeHash : beforeHash.slice(0, queryAt);
      var query = queryAt < 0 ? null : beforeHash.slice(queryAt + 1);

      var targetAuthority = base.authority;
      var hasAuthority = base.hasAuthority;
      var targetPath;
      if (pathInput.slice(0, 2) === '//') {
        var authorityAndPath = pathInput.slice(2);
        var authorityEnd = authorityAndPath.indexOf('/');
        targetAuthority = authorityEnd < 0
          ? authorityAndPath
          : authorityAndPath.slice(0, authorityEnd);
        hasAuthority = true;
        targetPath = authorityEnd < 0 ? '' : authorityAndPath.slice(authorityEnd);
        targetPath = removeDotSegments(targetPath);
      } else if (pathInput === '') {
        targetPath = base.path;
        if (query === null) query = base.query;
      } else {
        if (pathInput.charAt(0) === '/') {
          targetPath = pathInput;
        } else {
          targetPath = mergePaths(base, pathInput);
        }
        targetPath = removeDotSegments(targetPath);
      }

      return 'dsh-resource:' +
        (hasAuthority ? '//' + targetAuthority : '') + targetPath +
        (query === null ? '' : '?' + query) +
        (hash === null ? '' : '#' + hash);
    }

    function CompatURL(input, base) {
      if (!(this instanceof CompatURL)) {
        throw new TypeError("Failed to construct 'URL': Please use the 'new' operator.");
      }
      var created;
      if (arguments.length > 1) {
        var baseHref;
        try { baseHref = String(base); } catch (_) { baseHref = ''; }
        var resolved = resolveRelative(input, baseHref);
        created = resolved === null
          ? new NativeURL(input, base)
          : new NativeURL(resolved);
      } else {
        created = new NativeURL(input);
      }
      return repaired(created);
    }

    Object.getOwnPropertyNames(NativeURL).forEach(function (name) {
      if (name === 'prototype' || name === 'length' || name === 'name') return;
      try {
        Object.defineProperty(
          CompatURL,
          name,
          Object.getOwnPropertyDescriptor(NativeURL, name)
        );
      } catch (_) {}
    });
    CompatURL.prototype = NativeURL.prototype;
    try { Object.setPrototypeOf(CompatURL, NativeURL); } catch (_) {}

    if (typeof NativeURL.parse === 'function') {
      Object.defineProperty(CompatURL, 'parse', {
        configurable: true,
        writable: true,
        value: function parse(input, base) {
          try {
            return arguments.length > 1
              ? new CompatURL(input, base)
              : new CompatURL(input);
          } catch (_) {
            return null;
          }
        }
      });
    }

    window.URL = CompatURL;
    try {
      if (window.console && typeof window.console.warn === 'function') {
        window.console.warn(
          '[DSHBox] URL host compat installed for dsh-resource://'
        );
      }
    } catch (_) {}
  }

  function installOpenDocumentBridge() {
    var pointerButtons = typeof WeakSet === 'function' ? new WeakSet() : null;

    function buttonFor(target) {
      try {
        return target && typeof target.closest === 'function'
          ? target.closest('button')
          : null;
      } catch (_) {
        return null;
      }
    }

    function isOpenDocumentButton(button) {
      if (!button) return false;
      var label = (button.textContent || '').replace(/\s+/g, ' ').trim();
      return OPEN_DOCUMENT_LABELS.indexOf(label) >= 0;
    }

    function stop(event) {
      if (typeof event.preventDefault === 'function') event.preventDefault();
      if (typeof event.stopPropagation === 'function') event.stopPropagation();
      if (typeof event.stopImmediatePropagation === 'function') {
        event.stopImmediatePropagation();
      }
    }

    function navigate() {
      try {
        window.location.href = OPEN_SETTINGS_DOCUMENT_URL;
      } catch (_) {}
    }

    function handlePointerDown(event) {
      if (event && typeof event.button === 'number' && event.button !== 0) return;
      var button = buttonFor(event && event.target);
      if (!isOpenDocumentButton(button)) return;
      stop(event);
      if (pointerButtons) pointerButtons.add(button);
      navigate();
    }

    function handleClick(event) {
      if (event && typeof event.button === 'number' && event.button !== 0) return;
      var button = buttonFor(event && event.target);
      if (!isOpenDocumentButton(button)) return;
      stop(event);
      if (pointerButtons && pointerButtons.has(button)) {
        pointerButtons.delete(button);
        return;
      }
      navigate();
    }

    document.addEventListener('pointerdown', handlePointerDown, true);
    document.addEventListener('click', handleClick, true);
  }

  /*
   * The Enter key setting is honored here, at the page injection layer, so
   * both the local DSH container and remote sessions get the same behaviour.
   *
   * "New line" mode: a bare Enter inside the DSH composer editable is
   * captured in the capture phase, its default send action is cancelled, and
   * a shift-keyed Enter is replayed on the same target — the DSH composer's
   * own Shift+Enter newline binding takes over. A short fallback then
   * guarantees a newline is inserted even when the replayed event goes
   * unhandled (untrusted events skip the browser's contenteditable default
   * action): if the editor's DOM structure has not changed within the grace
   * window, insertLineBreak is issued once; only if that imperative insert
   * itself produces no structural change does insertText "\n" follow — at
   * most one extra insertion either way.
   *
   * Change detection uses a DOM-structure fingerprint, not `textContent`:
   * Lexical's linebreak materializes as a `<br>` node with no text of its
   * own, so a successful insert can leave `textContent` identical while
   * `innerHTML` grows. Text-only comparison would then re-issue the insert
   * every Enter and duplicate blank lines. The fingerprint combines the
   * direct child count, `<br>` count, and innerHTML length — any of the
   * three moving means a real structural edit happened.
   *
   * Scope is deliberately narrow; the interceptor only fires when the event
   * target resolves to the exact kind of editor DSH renders its input
   * surfaces with:
   *  - a `[data-composer-input="true"][contenteditable="true"]` element (or
   *    a descendant of one) — the flag DSH actually puts on its composer
   *    right now (field-proven on device);
   *  - a `[data-lexical-editor="true"]` flagged `[contenteditable="true"]`
   *    (self or ancestor) kept as a fallback for builds that mark editors
   *    the Lexical way instead;
   *  - `<textarea>` and non-matching contenteditable elements untouched —
   *    a plain textarea has no JS-level Shift+Enter handler, so intercepting
   *    it would swallow Enter entirely;
   *  - a visible open menu ([role="menu"] / [role="listbox"] /
   *    [data-radix-popper-content-wrapper], in the current document tree,
   *    not `display:none` / `hidden`) passthroughs the key so the slash/@
   *    mention menus keep their confirm-on-Enter.
   * Modifier combos (Shift/Ctrl/Meta/Alt) and IME composition events are
   * passed through untouched, and the key is recorded past the composition
   * guard so IME-confirmed Enter always keeps its native meaning.
   *
   * The enabled state is read from the mutable global
   * `__dshboxEnterNewline` on every key press, NOT frozen at install time:
   * the native host pushes settings changes by assigning that global
   * (`evaluateJavascript`), so the user's toggle lands without a page
   * reload. `config.enterKeyNewline` only seeds the initial value — absent
   * or false still keeps the interceptor uninstalled at install time unless
   * the global says otherwise.
   *
   * Scope caveat (must not be stretched further): the target gate relies on
   * DSH marking its composer/editors with those data attributes, not on a
   * page-wide DSH identification step. A third-party page that happens to
   * carry the same attributes would get the same interception — this does
   * not need to be defended against because the additional check over the
   * standard third-party form surface (textarea and plain contenteditable)
   * is what matters, and both are explicitly excluded.
   *
   * Modifier-guard, IME-guard, menu pass-through, and replay side effects
   * are unchanged from the previous interceptor; only the target gate and
   * the runtime switch differ. A missing global reads as "send" (page
   * behaviour exactly as before), which also covers hosts that never push
   * the switch.
   */
  function installEnterKeyNewline() {
    if (!window.KeyboardEvent) return;

    // Seed the mutable global from the install-time config when the host
    // has not set it itself. Hosts that push the switch build this global
    // via `evaluateJavascript`; its presence then wins over the config.
    if (globalObject.__dshboxEnterNewline === undefined) {
      globalObject.__dshboxEnterNewline = config.enterKeyNewline === true;
    }

    /*
     * DOM-structure fingerprint of an editor subtree: three integers that
     * move whenever the DOM does. `innerHTML.length` and
     * `childElementCount` are plain reads; `<br>` count is summed
     * explicitly because Lexical's linebreak is a dedicated element with
     * no text, invisible to any text-only comparison. A successful
     * newline insert grows the innerHTML span (a `<br>` and whatever
     * spacing the engine adds) even when `textContent` stays identical,
     * so the fallback gate must see it.
     */
    function structureFingerprint(editor) {
      var brs = 0;
      try {
        var list = editor.querySelectorAll('br');
        brs = list ? list.length : 0;
      } catch (_) {
        brs = 0;
      }
      var html = '';
      try { html = String(editor.innerHTML || ''); } catch (_) { html = ''; }
      var children = 0;
      try { children = editor.childElementCount || 0; } catch (_) { children = 0; }
      return children + '/' + brs + '/' + html.length;
    }

    var replayGeneratorParams = {
      key: 'Enter',
      code: 'Enter',
      bubbles: true,
      cancelable: true,
      shiftKey: true
    };

    function visibleMenuOpen() {
      // Gate kept intentionally cheap but complete: a menu counts as open
      // only when it is actually surfaceable. Collapsed states — the
      // inline `hidden` attribute, `aria-hidden`, Radix
      // `data-state="closed"`, computed `display:none` (self or any
      // ancestor) or `visibility:hidden` — all keep Enter inside the
      // interceptor, because they cannot surface a menu confirmation.
      // Only a currently-open menu passthroughs the key.
      function styleOf(node) {
        var view = document.defaultView;
        var styleReader = (view && view.getComputedStyle) || document.getComputedStyle;
        if (typeof styleReader !== 'function') return null;
        try {
          var style = styleReader.call(view || document, node);
          return style || null;
        } catch (_) {
          return null;
        }
      }

      function collapsedByAttributes(node) {
        if (node.hidden === true) return true;
        var read = function (name) {
          return typeof node.getAttribute === 'function'
            ? node.getAttribute(name)
            : null;
        };
        if (read('aria-hidden') === 'true') return true;
        if (read('data-state') === 'closed') return true;
        return false;
      }

      // Real pages resolve the computed view through `defaultView`; a
      // document hosting its own `getComputedStyle` (test double, legacy
      // engine) is honoured as a fallback. When no style engine exists the
      // attribute gates above still run; an unresolvable layer keeps the
      // interceptor (query failure collapses to "no menu").
      function visible(node) {
        if (!node || node.nodeType !== 1) return false;
        if (collapsedByAttributes(node)) return false;
        var cursor = node;
        while (cursor && cursor !== document.body && cursor.nodeType === 1) {
          var style = styleOf(cursor);
          if (style) {
            if (style.display === 'none' || style.visibility === 'hidden') {
              return false;
            }
          }
          cursor = cursor.parentElement;
        }
        return true;
      }
      var queries = ['[role="menu"]', '[role="listbox"]', '[data-radix-popper-content-wrapper]'];
      for (var i = 0; i < queries.length; i += 1) {
        var nodes = [];
        try { nodes = document.querySelectorAll(queries[i]) || []; } catch (_) { nodes = []; }
        for (var j = 0; j < nodes.length; j += 1) {
          if (visible(nodes[j])) return true;
        }
      }
      return false;
    }

    function findComposerEditable(target) {
      // Target gate, phase 1: the field-proven composer marker
      // `[data-composer-input="true"]` on a `[contenteditable="true"]`
      // element (self or ancestor). This is the attribute DSH actually
      // ships on its input surface today — the previous `data-lexical-
      // editor` gate matched nothing in the live DOM, so interception
      // silently never fired.
      var node = target;
      while (node && node !== document.body) {
        if (node.getAttribute
            && node.getAttribute('data-composer-input') === 'true') {
          return node.isContentEditable !== false;
        }
        node = node.parentElement;
      }

      // Phase 2: keep Lexical's editor flag as the fallback gate for
      // builds that mark editors the Lexical way (same climb semantics
      // as before: inner editable layers do NOT terminate the climb —
      // only the flagged root proves the chain).
      var editable = target;
      while (editable && editable !== document.body) {
        var flagged = editable.getAttribute
          ? editable.getAttribute('data-lexical-editor') === 'true'
          : false;
        if (flagged) {
          // Lexical only flags its editor roots; reaching a flag proves
          // the chain is a Lexical editable regardless of which layer
          // the caret rests on.
          return editable.isContentEditable !== false;
        }
        editable = editable.parentElement;
      }
      return false;
    }

    function closestContentEditable(target) {
      if (typeof target.closest !== 'function') return null;
      try { return target.closest('[contenteditable="true"]'); } catch (_) { return null; }
    }

    document.addEventListener('keydown', function (event) {
      try {
        // The switch reads fresh on every key press: the native host flips
        // the mutable `__dshboxEnterNewline` global via evaluateJavascript
        // when the user toggles the setting, and this read is what makes
        // the change land without a reload.
        if (globalObject.__dshboxEnterNewline !== true) return;
        if (!event || event.key !== 'Enter') return;
        if (event.shiftKey || event.ctrlKey || event.metaKey || event.altKey) return;
        if (event.isComposing || event.keyCode === 229) return;
        var contentEditable = closestContentEditable(event.target);
        if (!contentEditable && !(event.target &&
            event.target.isContentEditable === true)) return;
        if (visibleMenuOpen()) return;
        if (!findComposerEditable(event.target)) return;
        var editor = contentEditable || event.target;
        var before = structureFingerprint(editor);
        event.preventDefault();
        event.stopImmediatePropagation();
        editor.dispatchEvent(new window.KeyboardEvent('keydown', replayGeneratorParams));
        // Interception fallback: the replay is an untrusted (isTrusted:false)
        // event, so Lexical or the browser may still decline to act. Wait a
        // short grace period, and if the editor's DOM structure is unchanged,
        // insert the newline imperatively so Enter can never become a no-op.
        // `insertLineBreak` runs at most once; only when it demonstrably
        // produced no structural change does `insertText "\n"` follow — the
        // baseline snapshot guarantees the pair can never fire twice for one
        // intercepted Enter (Lexical's `<br>` nodes register in the
        // fingerprint even though they carry no text).
        setTimeout(function () {
          try {
            if (document.querySelector &&
                document.documentElement &&
                !document.documentElement.contains(editor)) return;
            if (structureFingerprint(editor) !== before) return;
            document.execCommand('insertLineBreak', false, null);
            // Second-chance gate compares against the **same** baseline, not
            // the pre-command reading: a successful insertLineBreak moves the
            // fingerprint off the baseline, and only an unchanged fingerprint
            // justifies the single text insert. Reading before this check is
            // identical to `before` re-tested post-command since nothing else
            // ran in between.
            if (structureFingerprint(editor) === before) {
              document.execCommand('insertText', false, '\n');
            }
          } catch (_) {}
        }, ENTER_NEWLINE_FALLBACK_DELAY_MS);
      } catch (_) {}
    }, true);
  }

  /*
   * DSH uses window.open for destinations that belong outside the embedded
   * application. Keep direct blank popups usable for account login, then send
   * their eventual HTTP(S) destination to the native host.
   *
   * Destinations leave the page through one of two channels, probed at
   * install time. When the injected `dshboxHost` object exposes
   * `postMessage`, the destination is handed to the host as a JSON frame
   * message and the document itself never navigates. This is the only
   * channel that keeps a proxy session alive: rewriting the top frame to an
   * unknown scheme there would unload the document and surface a connection
   * failure. The host registers `dshboxHost` only for remote sessions (both
   * proxy and direct); the local DSH container does not, so local page
   * behaviour is unchanged. Same-origin `_blank` destinations go through the
   * channel too, because the native popup window cannot open inside the
   * embedded application anyway. Without the injected `dshboxHost` object the
   * legacy `dshbox://open-external` scheme path stays in charge: a registered
   * internal scheme (proxied remote sessions; direct remote sessions; the
   * local container) cancels that navigation before commit and turns it into
   * an internal action. A
   * gecko remote session has neither: its built-in extension content script
   * only injects the fixed local origin, so remote gecko pages fall back to
   * the engine's native `window.open` handling — remote external links are
   * specified against the systemWebView renderer only.
   */
  var HOST_MESSAGE_NAME = 'dshboxHost';
  var HOST_MESSAGE_TYPE = 'open-external';

  function hostChannelAvailable() {
    try {
      return typeof window[HOST_MESSAGE_NAME] === 'object' &&
        window[HOST_MESSAGE_NAME] !== null &&
        typeof window[HOST_MESSAGE_NAME].postMessage === 'function';
    } catch (_) {
      return false;
    }
  }

  function postExternalToHost(targetUrl) {
    try {
      window[HOST_MESSAGE_NAME].postMessage(JSON.stringify({
        type: HOST_MESSAGE_TYPE,
        url: targetUrl.href
      }));
      return true;
    } catch (_) {
      return false;
    }
  }

  function installExternalWindowBridge() {
    var nativeOpen = window.open;
    if (typeof nativeOpen !== 'function') return;
    if (nativeOpen.__dshboxExternalBridge === true) return;

    function requestExternal(rawUrl) {
      try {
        var targetUrl = new window.URL(rawUrl, window.location.href);
        if (targetUrl.protocol !== 'http:' && targetUrl.protocol !== 'https:') {
          return false;
        }
        if (targetUrl.username !== '' || targetUrl.password !== '') return false;
        if (hostChannelAvailable()) {
          return postExternalToHost(targetUrl);
        }
        if (targetUrl.origin === window.location.origin) return false;
        window.location.href = OPEN_EXTERNAL_URL_PREFIX +
          encodeURIComponent(targetUrl.href);
        return true;
      } catch (_) {
        return false;
      }
    }

    function createBlankPopup() {
      var closed = false;
      var navigated = false;
      var location = {};

      function navigate(rawUrl) {
        if (closed || navigated || !requestExternal(rawUrl)) return;
        navigated = true;
      }

      Object.defineProperty(location, 'href', {
        configurable: false,
        enumerable: true,
        get: function () { return 'about:blank'; },
        set: navigate
      });
      Object.defineProperty(location, 'replace', {
        configurable: false,
        enumerable: true,
        writable: false,
        value: navigate
      });
      Object.defineProperty(location, 'assign', {
        configurable: false,
        enumerable: true,
        writable: false,
        value: navigate
      });

      var popup = {
        opener: null,
        location: location,
        close: function () { closed = true; },
        focus: function () {},
        blur: function () {},
        postMessage: function () {}
      };
      Object.defineProperty(popup, 'closed', {
        configurable: false,
        enumerable: true,
        get: function () { return closed; }
      });
      return popup;
    }

    function bridgedOpen(url, target, features) {
      if (target === '_blank') {
        var blankUrl = String(url == null ? '' : url).trim();
        if (blankUrl === '' || /^about:blank(?:[?#]|$)/i.test(blankUrl)) {
          return createBlankPopup();
        }
        if (requestExternal(blankUrl)) return null;
      }
      return nativeOpen.call(window, url, target, features);
    }

    try {
      Object.defineProperty(bridgedOpen, '__dshboxExternalBridge', {
        configurable: false,
        enumerable: false,
        writable: false,
        value: true
      });
    } catch (_) {}
    window.open = bridgedOpen;
  }
}());
