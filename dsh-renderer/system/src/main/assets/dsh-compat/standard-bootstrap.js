/* APP-owned standard API compatibility. No DSH state, DOM, CSS or RPC changes. */
(function () {
  'use strict';
  if (typeof window !== 'undefined' && window.top !== window) return;
  var globalObject = typeof globalThis === 'object' ? globalThis : window;
  var reportKey = Symbol.for('com.dshbox.renderer.native-capabilities.v1');
  if (!Object.prototype.hasOwnProperty.call(globalObject, reportKey)) {
    function css(property, value) {
      try { return CSS.supports(property, value); } catch (_) { return false; }
    }
    var hasSelector = false;
    try { hasSelector = CSS.supports('selector(:has(*))'); } catch (_) {}
    Object.defineProperty(globalObject, reportKey, { value: Object.freeze({
      PROMISE_WITH_RESOLVERS: typeof Promise.withResolvers === 'function',
      PROMISE_TRY: typeof Promise.try === 'function',
      ABORT_SIGNAL_ANY: typeof AbortSignal === 'function' && typeof AbortSignal.any === 'function',
      ARRAY_TO_SORTED: typeof Array.prototype.toSorted === 'function',
      ARRAY_FIND_LAST: typeof Array.prototype.findLast === 'function',
      ARRAY_TO_REVERSED: typeof Array.prototype.toReversed === 'function',
      STRUCTURED_CLONE: typeof structuredClone === 'function',
      URL_PARSE: typeof URL === 'function' && typeof URL.parse === 'function',
      URL_CAN_PARSE: typeof URL === 'function' && typeof URL.canParse === 'function',
      REGEXP_ESCAPE: typeof RegExp.escape === 'function',
      MAP_GET_OR_INSERT: typeof Map.prototype.getOrInsert === 'function',
      MAP_GET_OR_INSERT_COMPUTED:
        typeof Map.prototype.getOrInsertComputed === 'function',
      RESIZE_OBSERVER: typeof ResizeObserver === 'function',
      WORKER: typeof Worker === 'function',
      READABLE_STREAM: typeof ReadableStream === 'function',
      WEBSOCKET: typeof WebSocket === 'function',
      CSS_HAS: hasSelector,
      CSS_COLOR_MIX: css('color', 'color-mix(in srgb, red, blue)'),
      CSS_DVH: css('height', '100dvh'),
      CSS_SUBGRID: css('grid-template-columns', 'subgrid'),
      CSS_LIGHT_DARK: css('color', 'light-dark(red, blue)')
    }) });
  }
  function arrayLength(object) {
    var length = +object.length;
    return length !== length || length <= 0
      ? 0
      : Math.min(Math.floor(length), Number.MAX_SAFE_INTEGER);
  }
  if (typeof Promise.withResolvers !== 'function') {
    Object.defineProperty(Promise, 'withResolvers', {
      configurable: true,
      enumerable: false,
      writable: true,
      value: function withResolvers() {
        var resolve, reject;
        var promise = new this(function (res, rej) {
          if (resolve !== undefined || reject !== undefined) {
            throw new TypeError('Promise capability executor invoked twice');
          }
          resolve = res;
          reject = rej;
        });
        if (typeof resolve !== 'function' || typeof reject !== 'function') {
          throw new TypeError('Invalid promise capability');
        }
        return { promise: promise, resolve: resolve, reject: reject };
      }
    });
  }
  if (typeof Promise.try !== 'function') {
    Object.defineProperty(Promise, 'try', {
      configurable: true,
      enumerable: false,
      writable: true,
      value: function promiseTry(callback) {
        if (typeof callback !== 'function') {
          throw new TypeError('Promise.try callback must be a function');
        }
        var receiver = this;
        var args = Array.prototype.slice.call(arguments, 1);
        return new receiver(function (resolve) {
          resolve(callback.apply(undefined, args));
        });
      }
    });
  }
  if (typeof Array.prototype.toSorted !== 'function') {
    Object.defineProperty(Array.prototype, 'toSorted', {
      configurable: true,
      enumerable: false,
      writable: true,
      value: function toSorted(compareFn) {
        if (compareFn !== undefined && typeof compareFn !== 'function') {
          throw new TypeError('Comparator must be a function');
        }
        if (this === null || this === undefined) throw new TypeError('Invalid receiver');
        var object = Object(this);
        var length = arrayLength(object);
        // Array allocation intentionally preserves the native RangeError for excessive lengths.
        var copy = new Array(length);
        for (var index = 0; index < length; index++) copy[index] = object[index];
        return copy.sort(compareFn);
      }
    });
    if (typeof Symbol === 'function' && Symbol.unscopables) {
      var unscopables = Array.prototype[Symbol.unscopables];
      if (unscopables) unscopables.toSorted = true;
    }
  }
  if (typeof Array.prototype.findLast !== 'function') {
    Object.defineProperty(Array.prototype, 'findLast', {
      configurable: true,
      enumerable: false,
      writable: true,
      value: function findLast(predicate) {
        if (typeof predicate !== 'function') {
          throw new TypeError('Predicate must be a function');
        }
        if (this === null || this === undefined) throw new TypeError('Invalid receiver');
        var thisArg = arguments.length > 1 ? arguments[1] : undefined;
        var object = Object(this);
        var length = arrayLength(object);
        for (var index = length - 1; index >= 0; index--) {
          var value = object[index];
          if (predicate.call(thisArg, value, index, object)) return value;
        }
        return undefined;
      }
    });
    if (typeof Symbol === 'function' && Symbol.unscopables) {
      var findLastUnscopables = Array.prototype[Symbol.unscopables];
      if (findLastUnscopables) findLastUnscopables.findLast = true;
    }
  }
  if (typeof Array.prototype.findLastIndex !== 'function') {
    Object.defineProperty(Array.prototype, 'findLastIndex', {
      configurable: true,
      enumerable: false,
      writable: true,
      value: function findLastIndex(predicate) {
        if (typeof predicate !== 'function') {
          throw new TypeError('Predicate must be a function');
        }
        if (this === null || this === undefined) throw new TypeError('Invalid receiver');
        var thisArg = arguments.length > 1 ? arguments[1] : undefined;
        var object = Object(this);
        var length = arrayLength(object);
        for (var index = length - 1; index >= 0; index--) {
          if (predicate.call(thisArg, object[index], index, object)) return index;
        }
        return -1;
      }
    });
    if (typeof Symbol === 'function' && Symbol.unscopables) {
      var findLastIndexUnscopables = Array.prototype[Symbol.unscopables];
      if (findLastIndexUnscopables) findLastIndexUnscopables.findLastIndex = true;
    }
  }
  if (typeof Array.prototype.toReversed !== 'function') {
    Object.defineProperty(Array.prototype, 'toReversed', {
      configurable: true,
      enumerable: false,
      writable: true,
      value: function toReversed() {
        if (this === null || this === undefined) throw new TypeError('Invalid receiver');
        var object = Object(this);
        var length = arrayLength(object);
        var reversed = new Array(length);
        for (var index = 0; index < length; index++) {
          reversed[index] = object[length - index - 1];
        }
        return reversed;
      }
    });
    if (typeof Symbol === 'function' && Symbol.unscopables) {
      var toReversedUnscopables = Array.prototype[Symbol.unscopables];
      if (toReversedUnscopables) toReversedUnscopables.toReversed = true;
    }
  }
  if (typeof URL === 'function') {
    if (typeof URL.parse !== 'function') {
      Object.defineProperty(URL, 'parse', {
        configurable: true,
        enumerable: false,
        writable: true,
        value: function parseURL(input, base) {
          try {
            return arguments.length > 1 ? new URL(input, base) : new URL(input);
          } catch (_) {
            return null;
          }
        }
      });
    }
    if (typeof URL.canParse !== 'function') {
      Object.defineProperty(URL, 'canParse', {
        configurable: true,
        enumerable: false,
        writable: true,
        value: function canParseURL(input, base) {
          try {
            if (arguments.length > 1) new URL(input, base);
            else new URL(input);
            return true;
          } catch (_) {
            return false;
          }
        }
      });
    }
  }
  if (typeof Map === 'function') {
    if (typeof Map.prototype.getOrInsert !== 'function') {
      Object.defineProperty(Map.prototype, 'getOrInsert', {
        configurable: true,
        enumerable: false,
        writable: true,
        value: function getOrInsert(key, value) {
          if (this.has(key)) return this.get(key);
          this.set(key, value);
          return value;
        }
      });
    }
    if (typeof Map.prototype.getOrInsertComputed !== 'function') {
      Object.defineProperty(Map.prototype, 'getOrInsertComputed', {
        configurable: true,
        enumerable: false,
        writable: true,
        value: function getOrInsertComputed(key, callback) {
          if (typeof callback !== 'function') {
            throw new TypeError('Callback must be a function');
          }
          if (this.has(key)) return this.get(key);
          var value = callback(key);
          this.set(key, value);
          return value;
        }
      });
    }
  }
  if (typeof RegExp.escape !== 'function') {
    Object.defineProperty(RegExp, 'escape', {
      configurable: true,
      enumerable: false,
      writable: true,
      value: function escapeRegExp(value) {
        if (typeof value !== 'string') throw new TypeError('Argument must be a string');
        var syntax = '^$\\.*+?()[]{}|/';
        var punctuators = ',-=<>#&!%:;@~\'`"';
        var escaped = '';
        var first = true;
        for (var character of value) {
          var codePoint = character.codePointAt(0);
          if (first && ((codePoint >= 48 && codePoint <= 57) ||
              (codePoint >= 65 && codePoint <= 90) ||
              (codePoint >= 97 && codePoint <= 122))) {
            escaped += '\\x' + codePoint.toString(16).padStart(2, '0');
          } else if (syntax.indexOf(character) >= 0) {
            escaped += '\\' + character;
          } else if (punctuators.indexOf(character) >= 0) {
            escaped += '\\x' + codePoint.toString(16).padStart(2, '0');
          } else if (codePoint === 9) {
            escaped += '\\t';
          } else if (codePoint === 10) {
            escaped += '\\n';
          } else if (codePoint === 11) {
            escaped += '\\v';
          } else if (codePoint === 12) {
            escaped += '\\f';
          } else if (codePoint === 13) {
            escaped += '\\r';
          } else if (codePoint === 32) {
            escaped += '\\x20';
          } else if (codePoint <= 0x1f || codePoint === 0x7f) {
            escaped += '\\x' + codePoint.toString(16).padStart(2, '0');
          } else if (codePoint <= 0xffff &&
              /[\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]/.test(character)) {
            escaped += '\\u' + codePoint.toString(16).padStart(4, '0');
          } else {
            escaped += character;
          }
          first = false;
        }
        return escaped;
      }
    });
  }
  // Read descriptors without invoking getters on the unbranded prototype.
  if (typeof AbortSignal === 'function' && typeof AbortSignal.any !== 'function' &&
      typeof AbortController === 'function' && typeof EventTarget === 'function' &&
      typeof WeakRef === 'function' && typeof FinalizationRegistry === 'function') {
    var abortedDescriptor = Object.getOwnPropertyDescriptor(AbortSignal.prototype, 'aborted');
    var reasonDescriptor = Object.getOwnPropertyDescriptor(AbortSignal.prototype, 'reason');
    if (!abortedDescriptor || typeof abortedDescriptor.get !== 'function' ||
        !reasonDescriptor || typeof reasonDescriptor.get !== 'function') return;
    var signalAborted = abortedDescriptor.get;
    var signalReason = reasonDescriptor.get;
    var addListener = EventTarget.prototype.addEventListener;
    var removeListener = EventTarget.prototype.removeEventListener;
    if (typeof addListener !== 'function' || typeof removeListener !== 'function') return;
    var controllers = new WeakMap();
    var finalizers = new FinalizationRegistry(function (record) { detach(record); });
    function detach(record) {
      for (var k = 0; k < record.listeners.length; k++) {
        try { removeListener.call(record.listeners[k].signal, 'abort', record.listeners[k].listener); }
        catch (_) {}
      }
      record.listeners.length = 0;
      finalizers.unregister(record);
    }
    // Keep this factory outside any(): source listeners must not close over its
    // controller. Otherwise long-lived source signals retain every completed task.
    function watch(record, source) {
      var listener = function () {
        if (!signalAborted.call(source)) return;
        var derived = record.target.deref();
        if (!derived || signalAborted.call(derived)) { detach(record); return; }
        var controller = controllers.get(derived);
        var reason = signalReason.call(source);
        detach(record);
        controllers.delete(derived);
        if (controller) controller.abort(reason);
      };
      addListener.call(source, 'abort', listener);
      record.listeners.push({ signal: source, listener: listener });
    }
    Object.defineProperty(AbortSignal, 'any', {
      configurable: true,
      writable: true,
      value: function any(signals) {
        var collected = [];
        var seen = new Set();
        // for-of closes the iterator when element conversion fails. Validate all
        // elements before examining early aborts, as WebIDL sequence conversion does.
        for (var signal of signals) {
          signalAborted.call(signal);
          if (!seen.has(signal)) {
            seen.add(signal);
            collected.push(signal);
          }
        }
        var controller = new AbortController();
        if (collected.length === 0) return controller.signal;
        var firstAborted = null;
        for (var i = 0; i < collected.length; i++) {
          if (signalAborted.call(collected[i]) === true) {
            firstAborted = collected[i];
            break;
          }
        }
        if (firstAborted !== null) {
          controller.abort(signalReason.call(firstAborted));
          return controller.signal;
        }
        var record = { target: new WeakRef(controller.signal), listeners: [] };
        controllers.set(controller.signal, controller);
        finalizers.register(controller.signal, record, record);
        try {
          for (var j = 0; j < collected.length; j++) watch(record, collected[j]);
        } catch (failure) {
          detach(record);
          controllers.delete(controller.signal);
          throw failure;
        }
        return controller.signal;
      }
    });
  }
}());
