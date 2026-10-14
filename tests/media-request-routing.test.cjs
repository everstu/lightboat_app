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
  class Element {
    constructor(tagName) { this.tagName = tagName; this.attrs = {}; }
    setAttribute(name, value) { this.attrs[name] = String(value); }
    getAttribute(name) { return this.attrs[name] ?? null; }
    querySelectorAll() { return []; }
  }
  class HTMLMediaElement extends Element {
    constructor(tag = 'VIDEO') { super(tag); this.rawSrc = ''; }
  }
  Object.defineProperty(HTMLMediaElement.prototype, 'src', { configurable: true,
    get() { return this.rawSrc; }, set(value) { this.rawSrc = new URL(value, document.baseURI).href; } });
  Object.defineProperty(HTMLMediaElement.prototype, 'currentSrc', { configurable: true,
    get() { return this.rawSrc; } });
  class HTMLSourceElement extends Element {
    constructor() { super('SOURCE'); this.rawSrc = ''; }
  }
  Object.defineProperty(HTMLSourceElement.prototype, 'src', { configurable: true,
    get() { return this.rawSrc; }, set(value) { this.rawSrc = new URL(value, document.baseURI).href; } });
  const document = new Element('DOCUMENT');
  document.baseURI = 'https://site.example/watch/1';
  document.referrerPolicy = 'strict-origin-when-cross-origin';
  const window = { addEventListener() {} };
  const context = vm.createContext({ Element, HTMLMediaElement, HTMLSourceElement, document, window,
    location: new URL(document.baseURI), URL, encodeURIComponent,
    MutationObserver: class { constructor(callback) { this.callback = callback; observer = this; } observe() {} disconnect() {} } });
  vm.runInContext(source, context);
  return { HTMLMediaElement, HTMLSourceElement, observer };
}

test('Routes media properties and attributes while preserving the page-visible URL', () => {
  const h = harness();
  const video = new h.HTMLMediaElement();
  video.src = 'https://cdn.example/master.m3u8?token=a';
  assert.match(video.rawSrc, /^http:\/\/127\.0\.0\.1:43210\/session\/media\?/);
  assert.match(video.rawSrc, /url=https%3A%2F%2Fcdn\.example%2Fmaster\.m3u8%3Ftoken%3Da/);
  assert.equal(video.src, 'https://cdn.example/master.m3u8?token=a');
  assert.equal(video.currentSrc, 'https://cdn.example/master.m3u8?token=a');
  video.setAttribute('src', '/movie.mp4');
  assert.match(video.attrs.src, /^http:\/\/127\.0\.0\.1:43210/);
  assert.equal(video.getAttribute('src'), '/movie.mp4');
});

test('Routes parser-added media and leaves blob sources untouched', () => {
  const h = harness();
  const sourceNode = new h.HTMLSourceElement();
  sourceNode.attrs.src = 'https://cdn.example/audio.m3u8';
  h.observer.callback([{ type: 'childList', addedNodes: [sourceNode] }]);
  assert.match(sourceNode.attrs.src, /^http:\/\/127\.0\.0\.1:43210/);
  const video = new h.HTMLMediaElement();
  video.src = 'blob:https://site.example/id';
  assert.equal(video.rawSrc, 'blob:https://site.example/id');
});
