package com.dshbox.browser.runtime

import org.json.JSONObject

internal object BrowserSameDocumentHistoryScript {
    val install = """
        (() => {
          const key = '__dshBrowserHistoryV1';
          if (window[key]) return true;
          const state = window[key] = {
            entries: [location.href], index: 0, pending: null, lastTraversal: null, limit: 64
          };
          const originalPush = history.pushState;
          const originalReplace = history.replaceState;
          const originalBack = history.back;
          state.pushHook = function(...args) {
            const result = originalPush.apply(this, args);
            state.entries.splice(state.index + 1);
            state.entries.push(location.href);
            state.index = state.entries.length - 1;
            if (state.entries.length > state.limit) {
              const removed = state.entries.length - state.limit;
              state.entries.splice(0, removed);
              state.index = Math.max(0, state.index - removed);
            }
            return result;
          };
          state.replaceHook = function(...args) {
            const result = originalReplace.apply(this, args);
            state.entries[state.index] = location.href;
            return result;
          };
          history.pushState = state.pushHook;
          history.replaceState = state.replaceHook;
          addEventListener('popstate', () => {
            const pending = state.pending;
            if (pending && location.href === pending.url) {
              state.index = pending.index;
              state.lastTraversal = { token: pending.token, index: pending.index, url: location.href };
              state.pending = null;
              return;
            }
            const previous = state.index - 1;
            if (previous >= 0 && state.entries[previous] === location.href) state.index = previous;
          });
          state.nativeBack = originalBack;
          return true;
        })()
    """.trimIndent()

    val target = """
        (() => {
          const state = window.__dshBrowserHistoryV1;
          if (!state || history.pushState !== state.pushHook || history.replaceState !== state.replaceHook ||
              state.entries[state.index] !== location.href) {
            return JSON.stringify({known: false, state: 'unknown'});
          }
          if (state.index <= 0) return JSON.stringify({known: true, state: 'no_history'});
          return JSON.stringify({known: true, state: 'target', index: state.index - 1, url: state.entries[state.index - 1]});
        })()
    """.trimIndent()

    fun dispatchBack(expectedUrl: String, token: String, authorizationNonce: String): String = """
        (() => {
          const state = window.__dshBrowserHistoryV1;
          if (!state || history.pushState !== state.pushHook || history.replaceState !== state.replaceHook ||
              state.entries[state.index] !== location.href || state.index <= 0 ||
              state.entries[state.index - 1] !== __EXPECTED_URL__) return false;
          if (!window.__dshBrowserActionGate ||
              !window.__dshBrowserActionGate.authorize(__AUTHORIZATION_NONCE__)) return false;
          state.pending = {token:__TOKEN__, index:state.index - 1, url:__EXPECTED_URL__};
          state.nativeBack.call(history);
          return true;
        })()
    """.trimIndent().replace("__EXPECTED_URL__", JSONObject.quote(expectedUrl))
        .replace("__AUTHORIZATION_NONCE__", JSONObject.quote(authorizationNonce))
        .replace("__TOKEN__", JSONObject.quote(token))

    fun verifyBack(expectedUrl: String, token: String): String = """
        (() => {
          const state = window.__dshBrowserHistoryV1;
          const last = state && state.lastTraversal;
          return !!last && last.token === __TOKEN__ &&
            last.url === __EXPECTED_URL__ && location.href === __EXPECTED_URL__;
        })()
    """.trimIndent().replace("__EXPECTED_URL__", JSONObject.quote(expectedUrl))
        .replace("__TOKEN__", JSONObject.quote(token))
}
