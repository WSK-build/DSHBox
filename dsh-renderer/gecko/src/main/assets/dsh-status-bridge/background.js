(function (root) {
  'use strict';

  const TRUSTED_ORIGIN = 'http://127.0.0.1:3080';
  const TRUSTED_MATCH = TRUSTED_ORIGIN + '/*';
  const NATIVE_APPLICATION = 'dshbox-status';
  const NAVIGATION_HEADER = 'x-dshbox-navigation';
  const MAX_PENDING_PER_TAB = 8;
  const MAX_PENDING_AGE_MS = 30000;

  function trustedUrl(url) {
    try {
      return new URL(url).origin === TRUSTED_ORIGIN;
    } catch {
      return false;
    }
  }

  function canonicalUrl(url) {
    try {
      const parsed = new URL(url);
      parsed.hash = '';
      return parsed.href;
    } catch {
      return '';
    }
  }

  function messageUrl(url) {
    try {
      const parsed = new URL(url);
      return parsed.origin + parsed.pathname;
    } catch {
      return '';
    }
  }

  function documentKey(tabId, documentId) {
    return String(tabId) + ':' + String(documentId);
  }

  function createStatusBridge(browserApi, options) {
    const settings = options || {};
    const now = settings.now || Date.now;
    const requestsByTab = new Map();
    const noncesByRequest = new Map();
    const requestsByDocument = new Map();

    function tabRecords(tabId) {
      let records = requestsByTab.get(tabId);
      if (!records) {
        records = new Map();
        requestsByTab.set(tabId, records);
      }
      return records;
    }

    function prune(records) {
      const cutoff = now() - MAX_PENDING_AGE_MS;
      for (const [requestId, record] of records) {
        if (record.createdAt >= cutoff) continue;
        records.delete(requestId);
        if (record.documentId) {
          requestsByDocument.delete(documentKey(record.tabId, record.documentId));
        }
      }
      let excess = records.size - MAX_PENDING_PER_TAB;
      if (excess <= 0) return;
      const oldest = Array.from(records.values())
        .sort((left, right) => left.createdAt - right.createdAt);
      for (const record of oldest) {
        if (excess-- <= 0) break;
        records.delete(record.requestId);
        if (record.documentId) {
          requestsByDocument.delete(documentKey(record.tabId, record.documentId));
        }
      }
    }

    function accepts(details) {
      return details && details.type === 'main_frame' &&
        details.frameId === 0 && trustedUrl(details.url);
    }

    function getOrCreateRecord(details) {
      if (!accepts(details)) return null;
      const records = tabRecords(details.tabId);
      const requestId = String(details.requestId);
      let record = records.get(requestId);
      if (!record) {
        record = {
          requestId,
          tabId: details.tabId,
          frameId: 0,
          url: details.url,
          status: null,
          nonce: noncesByRequest.get(requestId) || null,
          documentId: details.documentId || null,
          createdAt: now(),
        };
        records.set(requestId, record);
      }
      if (details.documentId) {
        record.documentId = String(details.documentId);
        requestsByDocument.set(
          documentKey(record.tabId, record.documentId),
          record.requestId,
        );
      }
      prune(records);
      return record;
    }

    function onBeforeRequest(details) {
      const requestId = details ? String(details.requestId) : '';
      const existing = accepts(details)
        ? tabRecords(details.tabId).get(requestId)
        : null;
      const record = getOrCreateRecord(details);
      if (!record) return;
      record.url = details.url;
      // 同 requestId 重入（重定向链）不得清空已观测状态，否则已发出的 401
      // 记录会被后续 onBeforeRequest 抹掉；新建记录的 status 已在
      // getOrCreateRecord 初始化为 null。
      if (existing) record.nonce = existing.nonce || record.nonce;
    }

    function onBeforeSendHeaders(details) {
      const record = getOrCreateRecord(details);
      if (!record) return;
      const header = (details.requestHeaders || []).find(
        (entry) => entry.name && entry.name.toLowerCase() === NAVIGATION_HEADER,
      );
      if (!header || !header.value) return;
      const requestId = String(details.requestId);
      noncesByRequest.set(requestId, header.value);
      record.nonce = header.value;
    }

    function sendNative(message) {
      try {
        const result = browserApi.runtime.sendNativeMessage(
          NATIVE_APPLICATION,
          message,
        );
        if (result && typeof result.catch === 'function') result.catch(() => {});
      } catch {
        // The host rejects stale observations; transport errors need no page action.
      }
    }

    function onHeadersReceived(details) {
      const record = getOrCreateRecord(details);
      if (!record) return;
      record.url = details.url;
      record.status = details.statusCode;
      record.frameId = details.frameId;
      const requestId = String(details.requestId);
      record.nonce = noncesByRequest.get(requestId) || record.nonce || null;
      noncesByRequest.delete(requestId);
      if (details.documentId) {
        record.documentId = String(details.documentId);
        requestsByDocument.set(
          documentKey(record.tabId, record.documentId),
          record.requestId,
        );
      }
      sendNative({
        kind: 'headers',
        requestId: record.requestId,
        tabId: record.tabId,
        frameId: 0,
        status: record.status,
        url: messageUrl(record.url),
        nonce: record.nonce,
      });
    }

    function bindDocument(message, sender) {
      if (!message || message.kind !== 'document' ||
          !sender || !sender.tab || sender.tab.id === undefined ||
          sender.frameId !== 0 || !trustedUrl(sender.url) ||
          !sender.documentId) {
        return undefined;
      }
      const records = requestsByTab.get(sender.tab.id);
      if (!records) return undefined;
      prune(records);

      const senderUrl = canonicalUrl(sender.url);
      let requestId = requestsByDocument.get(
        documentKey(sender.tab.id, sender.documentId),
      );
      let record = requestId === undefined ? null : records.get(requestId);
      if (!record || record.status === null ||
          canonicalUrl(record.url) !== senderUrl) {
        record = null;
      }

      if (!record) {
        const candidates = Array.from(records.values()).filter((entry) =>
          entry.frameId === 0 &&
          entry.status !== null &&
          canonicalUrl(entry.url) === senderUrl
        );
        if (candidates.length !== 1) return undefined;
        record = candidates[0];
      }

      records.delete(record.requestId);
      if (record.documentId) {
        requestsByDocument.delete(documentKey(record.tabId, record.documentId));
      }
      return {
        kind: 'document-status',
        requestId: record.requestId,
        tabId: record.tabId,
        frameId: 0,
        status: record.status,
        url: messageUrl(record.url),
        nonce: record.nonce,
        documentId: String(sender.documentId),
      };
    }

    function onRuntimeMessage(message, sender) {
      return bindDocument(message, sender);
    }

    const mainFrameFilter = {
      urls: [TRUSTED_MATCH],
      types: ['main_frame'],
    };

    function install() {
      browserApi.webRequest.onBeforeSendHeaders.addListener(
        onBeforeSendHeaders,
        mainFrameFilter,
        ['requestHeaders'],
      );
      browserApi.webRequest.onBeforeRequest.addListener(
        onBeforeRequest,
        mainFrameFilter,
      );
      browserApi.webRequest.onHeadersReceived.addListener(
        onHeadersReceived,
        mainFrameFilter,
        ['responseHeaders'],
      );
      browserApi.runtime.onMessage.addListener(onRuntimeMessage);
    }

    return {
      install,
      onBeforeRequest,
      onBeforeSendHeaders,
      onHeadersReceived,
      onRuntimeMessage,
      pendingNonceCount: () => noncesByRequest.size,
      pendingRequestCount: (tabId) => {
        const records = requestsByTab.get(tabId);
        return records ? records.size : 0;
      },
    };
  }

  const api = {
    TRUSTED_ORIGIN,
    TRUSTED_MATCH,
    NATIVE_APPLICATION,
    createStatusBridge,
  };
  if (typeof module === 'object' && module.exports) {
    module.exports = api;
  } else if (root && root.browser) {
    createStatusBridge(root.browser).install();
  }
}(typeof globalThis === 'object' ? globalThis : this));
