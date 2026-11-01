(function () {
  'use strict';
  if (window.top !== window) return null;
  function css(property, value) {
    try { return CSS.supports(property, value); } catch (_) { return false; }
  }
  var hasSelector = false;
  try { hasSelector = CSS.supports('selector(:has(*))'); } catch (_) {}
  return {
    native: globalThis[Symbol.for('com.dshbox.renderer.native-capabilities.v1')] || null,
    effective: {
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
    },
    userAgent: String(navigator.userAgent).slice(0, 512)
  };
}())
