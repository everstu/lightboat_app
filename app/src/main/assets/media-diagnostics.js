(() => {
  'use strict';
  const names = ['未知错误', '加载中止', '网络错误', '解码失败', '媒体格式不支持'];
  const safeAddress = value => {
    try {
      const url = new URL(value, location.href);
      const address = url.origin + url.pathname;
      return address.length > 512 ? address.slice(0, 509) + '...' : address;
    } catch (_) {
      return '未知地址';
    }
  };
  document.addEventListener('error', event => {
    const media = event.target;
    if (!(media instanceof HTMLMediaElement)) return;
    const code = media.error?.code || 0;
    const source = media.currentSrc || media.src || media.querySelector('source')?.src || '';
    console.error('[Lightboat media] ' + (names[code] || names[0]) +
      '；code=' + code + '；network=' + media.networkState + '；ready=' + media.readyState +
      '；src=' + safeAddress(source));
  }, true);
})();
//# sourceURL=lightboat-media-diagnostics.js
