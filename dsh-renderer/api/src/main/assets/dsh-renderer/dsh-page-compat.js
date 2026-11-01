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
   * DSH uses window.open for destinations that belong outside the embedded
   * application. Keep direct blank popups usable for account login, then send
   * their eventual HTTP(S) destination to the native host.
   */
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
        if (targetUrl.origin === window.location.origin) return false;
        if (targetUrl.username !== '' || targetUrl.password !== '') return false;
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
