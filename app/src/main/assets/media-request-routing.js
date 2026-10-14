(() => {
  'use strict';
  const base = __LIGHTBOAT_MEDIA_PROXY__;
  const originals = new WeakMap();
  const getAttribute = Element.prototype.getAttribute;
  const setAttribute = Element.prototype.setAttribute;
  const removeAttribute = Element.prototype.removeAttribute;
  const eligible = node => ['VIDEO', 'AUDIO', 'SOURCE'].includes(node.tagName);
  const absolute = value => new URL(String(value), document.baseURI).href;
  function route(node, value) {
    const text = String(value);
    if (!text.trim()) { originals.delete(node); return text; }
    let url;
    try { url = new URL(text, document.baseURI); } catch (_) { originals.delete(node); return text; }
    if (url.href.startsWith(base + '?')) return text;
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) {
      originals.delete(node); return text;
    }
    originals.set(node, text);
    const page = new URL(location.href); page.hash = '';
    let ref = page.href;
    const policy = document.referrerPolicy || 'strict-origin-when-cross-origin';
    if (policy === 'no-referrer' || (page.protocol === 'https:' && url.protocol === 'http:' && policy !== 'unsafe-url')) ref = '';
    else if (page.origin !== url.origin && ['same-origin'].includes(policy)) ref = '';
    else if (['origin', 'strict-origin'].includes(policy) ||
      (page.origin !== url.origin && ['origin-when-cross-origin', 'strict-origin-when-cross-origin'].includes(policy))) ref = page.origin + '/';
    return base + '?url=' + encodeURIComponent(url.href) + '&ref=' + encodeURIComponent(ref);
  }
  function wrapSrc(prototype) {
    const descriptor = Object.getOwnPropertyDescriptor(prototype, 'src');
    if (!descriptor?.set || !descriptor?.get) return;
    Object.defineProperty(prototype, 'src', { ...descriptor,
      get() { return originals.has(this) ? absolute(originals.get(this)) : descriptor.get.call(this); },
      set(value) { descriptor.set.call(this, route(this, value)); }
    });
  }
  wrapSrc(HTMLMediaElement.prototype);
  wrapSrc(HTMLSourceElement.prototype);
  Element.prototype.setAttribute = function (name, value) {
    return setAttribute.call(this, name, eligible(this) && String(name).toLowerCase() === 'src' ? route(this, value) : value);
  };
  Element.prototype.getAttribute = function (name) {
    if (eligible(this) && String(name).toLowerCase() === 'src' && originals.has(this)) return originals.get(this);
    return getAttribute.call(this, name);
  };
  Element.prototype.removeAttribute = function (name) {
    if (eligible(this) && String(name).toLowerCase() === 'src') originals.delete(this);
    return removeAttribute.call(this, name);
  };
  const current = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'currentSrc');
  if (current?.get) Object.defineProperty(HTMLMediaElement.prototype, 'currentSrc', { ...current, get() {
    const value = current.get.call(this);
    if (!value.startsWith(base + '?')) return value;
    return new URL(value).searchParams.get('url') || value;
  } });
  function fix(node) {
    if (eligible(node)) {
      const src = getAttribute.call(node, 'src');
      if (!src) originals.delete(node);
      else if (!src.startsWith(base + '?')) {
        const routed = route(node, src);
        // A same-value src write still queues an attribute mutation and can
        // restart MediaSource. Never rewrite blob/data/unsupported sources.
        if (routed !== src) setAttribute.call(node, 'src', routed);
      }
    }
    node.querySelectorAll?.('video[src],audio[src],source[src]').forEach(fix);
  }
  const observer = new MutationObserver(records => {
    for (const record of records) {
      if (record.type === 'attributes') fix(record.target);
      else record.addedNodes.forEach(fix);
    }
  });
  observer.observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
  fix(document);
  window.addEventListener('pagehide', () => observer.disconnect());
  window.addEventListener('pageshow', event => {
    if (!event.persisted) return;
    observer.observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
    fix(document);
  });
})();
//# sourceURL=lightboat-media-request-routing.js
