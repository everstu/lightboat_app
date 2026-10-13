(() => {
  'use strict';
  // Clipboard writes require a user action. No native clipboard-reading API is exposed.
  if (!window.isSecureContext || !window.LightboatClipboard) return;
  const bridge = window.LightboatClipboard;
  const pending = new Map();
  let sequence = 0;
  bridge.onmessage = event => {
    let message;
    try { message = JSON.parse(event.data); } catch (_) { return; }
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    clearTimeout(request.timer);
    if (message.error) request.reject(new DOMException(message.error, 'NotAllowedError'));
    else request.resolve();
  };
  const writeText = value => {
    const text = String(value);
    if (!navigator.userActivation?.isActive) {
      return Promise.reject(new DOMException('复制需要用户点击', 'NotAllowedError'));
    }
    if (text.length > 65536) return Promise.reject(new DOMException('复制文本过长', 'DataError'));
    return new Promise((resolve, reject) => {
      const id = String(++sequence);
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new DOMException('复制请求超时', 'NotAllowedError'));
      }, 5000);
      pending.set(id, { resolve, reject, timer });
      try { bridge.postMessage(JSON.stringify({ id, text })); }
      catch (error) { clearTimeout(timer); pending.delete(id); reject(error); }
    });
  };
  const clipboard = navigator.clipboard || {};
  try {
    Object.defineProperty(clipboard, 'writeText', { value: writeText, configurable: true });
    if (!navigator.clipboard) Object.defineProperty(navigator, 'clipboard', { value: clipboard, configurable: true });
  } catch (_) {
    // Preserve the browser implementation if this WebView makes it non-configurable.
  }
})();
