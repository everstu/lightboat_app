const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const proxy = 'http://127.0.0.1:43210/session/media';
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/media-request-routing.js'), 'utf8')
  .replace('__LIGHTBOAT_MEDIA_PROXY__', JSON.stringify(proxy));

function harness() {
  let observer;
  const records = [];
  const nodes = new Set();
  const windowListeners = new Map();
  const nativeFetch = async () => ({ ok: true });
  class NativeXHR {}
  function changed(node) {
    if (observer?.connected) records.push({ type: 'attributes', target: node });
  }
  class Element {
    constructor(tagName) { this.tagName = tagName; this.attrs = {}; this.writes = 0; }
    setAttribute(name, value) {
      name = String(name).toLowerCase();
      this.attrs[name] = String(value);
      this.writes++;
      // DOM queues a mutation even when the new attribute value equals the old one.
      if (name === 'src') changed(this);
    }
    getAttribute(name) { return this.attrs[String(name).toLowerCase()] ?? null; }
    removeAttribute(name) {
      name = String(name).toLowerCase();
      delete this.attrs[name];
      if (name === 'src') changed(this);
    }
    querySelectorAll() { return []; }
  }
  const rawSet = Element.prototype.setAttribute;
  class HTMLMediaElement extends Element { constructor(tag = 'VIDEO') { super(tag); } }
  class HTMLSourceElement extends Element { constructor() { super('SOURCE'); } }
  const document = new Element('DOCUMENT');
  document.baseURI = 'https://site.example/watch/1';
  document.referrerPolicy = 'strict-origin-when-cross-origin';
  document.querySelectorAll = () => [...nodes].filter(node => node.attrs.src != null);
  for (const prototype of [HTMLMediaElement.prototype, HTMLSourceElement.prototype]) {
    Object.defineProperty(prototype, 'src', { configurable: true,
      get() { return this.attrs.src ? new URL(this.attrs.src, document.baseURI).href : ''; },
      set(value) { rawSet.call(this, 'src', String(value)); }
    });
  }
  Object.defineProperty(HTMLMediaElement.prototype, 'currentSrc', { configurable: true,
    get() { return this.attrs.src ? new URL(this.attrs.src, document.baseURI).href : ''; }
  });
  const window = {
    fetch: nativeFetch, XMLHttpRequest: NativeXHR,
    addEventListener(name, callback) { windowListeners.set(name, callback); }
  };
  const context = vm.createContext({ Element, HTMLMediaElement, HTMLSourceElement, document, window,
    fetch: nativeFetch, XMLHttpRequest: NativeXHR,
    location: new URL(document.baseURI), URL, encodeURIComponent,
    MutationObserver: class {
      constructor(callback) { this.callback = callback; observer = this; }
      observe() { this.connected = true; }
      disconnect() { this.connected = false; records.length = 0; }
    }
  });
  vm.runInContext(source, context);
  const attach = node => {
    nodes.add(node);
    records.push({ type: 'childList', addedNodes: [node] });
    return node;
  };
  const flush = () => {
    let batches = 0;
    while (records.length) {
      assert.ok(++batches <= 8, 'media observer keeps writing src and never becomes idle');
      observer.callback(records.splice(0));
    }
    return batches;
  };
  return { HTMLMediaElement, HTMLSourceElement, attach, flush, window, windowListeners, nativeFetch, NativeXHR };
}

test('Routes direct HTTP media while preserving the page-visible URL', () => {
  const h = harness();
  const video = h.attach(new h.HTMLMediaElement());
  video.src = 'https://cdn.example/master.m3u8?token=a';
  h.flush();
  assert.match(video.attrs.src, /^http:\/\/127\.0\.0\.1:43210\/session\/media\.m3u8\?/);
  assert.equal(video.src, 'https://cdn.example/master.m3u8?token=a');
  assert.equal(video.currentSrc, 'https://cdn.example/master.m3u8?token=a');
  assert.equal(video.getAttribute('src'), 'https://cdn.example/master.m3u8?token=a');
  assert.equal(video.writes, 1);
});

test('Rewrites parser-added HTTP sources once and drains its own mutation', () => {
  const h = harness();
  const node = new h.HTMLSourceElement();
  node.attrs.src = 'https://cdn.example/audio.m3u8';
  h.attach(node);
  h.flush();
  assert.match(node.attrs.src, /^http:\/\/127\.0\.0\.1/);
  assert.equal(node.writes, 1);
});

test('Explicit HLS MIME selects a typed route without rewriting it again', () => {
  const h = harness();
  const source = new h.HTMLSourceElement();
  source.attrs.type = 'application/x-mpegURL';
  source.attrs.src = 'https://cdn.example/signed-source?token=1';
  h.attach(source); h.flush();
  assert.ok(source.attrs.src.startsWith(proxy + '.m3u8?'));
  assert.equal(source.getAttribute('src'), 'https://cdn.example/signed-source?token=1');
  h.windowListeners.get('pageshow')({ persisted: true }); h.flush();
  assert.equal(source.writes, 1);
});

test('A typed HLS redirect remains transparent through currentSrc', () => {
  const h = harness();
  const video = h.attach(new h.HTMLMediaElement());
  video.src = 'https://cdn.example/signed-source'; h.flush();
  video.attrs.src = proxy + '.m3u8?url=' + encodeURIComponent('https://cdn.example/signed-source');
  assert.equal(video.currentSrc, 'https://cdn.example/signed-source');
  h.windowListeners.get('pageshow')({ persisted: true }); h.flush();
  assert.equal(video.writes, 1);
});

test('Blob MediaSource assignments never cause repeated source writes', () => {
  const h = harness();
  const video = h.attach(new h.HTMLMediaElement());
  video.src = 'blob:https://site.example/id';
  h.flush();
  assert.equal(video.src, 'blob:https://site.example/id');
  assert.equal(video.attrs.src, 'blob:https://site.example/id');
  assert.equal(video.writes, 1);
});

test('Parser blob/data/unsupported sources are left untouched without an observer loop', () => {
  for (const address of ['blob:https://site.example/id', 'data:video/mp4;base64,AAAA',
    'https://user:password@cdn.example/movie.mp4', 'invalid://source']) {
    const h = harness();
    const video = new h.HTMLMediaElement();
    video.attrs.src = address;
    h.attach(video);
    h.flush();
    assert.equal(video.attrs.src, address);
    assert.equal(video.writes, 0);
  }
});

test('Switching HTTP to blob and removing src clears the remembered original URL', () => {
  const h = harness();
  const video = h.attach(new h.HTMLMediaElement());
  video.src = 'https://cdn.example/movie.mp4';
  h.flush();
  video.src = 'blob:https://site.example/id';
  h.flush();
  assert.equal(video.src, 'blob:https://site.example/id');
  assert.equal(video.writes, 2);
  video.src = 'https://cdn.example/next.mp4';
  video.removeAttribute('src');
  assert.equal(video.src, '');
  assert.equal(video.getAttribute('src'), null);
  h.flush();
});

test('Fetch and XMLHttpRequest retain their original implementations', () => {
  const h = harness();
  assert.equal(h.window.fetch, h.nativeFetch);
  assert.equal(h.window.XMLHttpRequest, h.NativeXHR);
});

test('Restored pages resume observation without rewriting an existing blob source', () => {
  const h = harness();
  const video = h.attach(new h.HTMLMediaElement());
  video.src = 'blob:https://site.example/id';
  h.flush();
  h.windowListeners.get('pagehide')();
  h.windowListeners.get('pageshow')({ persisted: true });
  h.flush();
  assert.equal(video.writes, 1);
  assert.equal(video.src, 'blob:https://site.example/id');
});
