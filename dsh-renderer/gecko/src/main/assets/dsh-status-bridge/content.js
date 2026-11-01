(function (root) {
  'use strict';

  const TRUSTED_ORIGIN = 'http://127.0.0.1:3080';
  const NATIVE_APPLICATION = 'dshbox-status';

  function startContentBridge(browserApi, page) {
    const windowObject = page && page.window;
    const locationObject = page && page.location;
    if (!windowObject || !locationObject ||
        windowObject.top !== windowObject ||
        locationObject.origin !== TRUSTED_ORIGIN) {
      return Promise.resolve(false);
    }
    return browserApi.runtime.sendMessage({ kind: 'document' }).then((status) => {
      if (!status || status.kind !== 'document-status') return false;
      return browserApi.runtime.sendNativeMessage(NATIVE_APPLICATION, status)
        .then(() => true);
    }).catch(() => false);
  }

  const api = {
    TRUSTED_ORIGIN,
    startContentBridge,
  };
  if (typeof module === 'object' && module.exports) {
    module.exports = api;
  } else if (root && root.browser) {
    startContentBridge(root.browser, root);
  }
}(typeof globalThis === 'object' ? globalThis : this));
