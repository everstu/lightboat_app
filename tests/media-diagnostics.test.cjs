const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/media-diagnostics.js'), 'utf8');

test('Reports useful media state without logging URL queries or fragments', () => {
  let listener;
  const logs = [];
  class HTMLMediaElement {}
  const context = vm.createContext({
    URL,
    location: new URL('https://example.com/watch'),
    HTMLMediaElement,
    document: { addEventListener(type, callback, capture) {
      assert.equal(type, 'error'); assert.equal(capture, true); listener = callback;
    } },
    console: { error(message) { logs.push(message); } },
  });
  vm.runInContext(source, context);
  const media = new HTMLMediaElement();
  Object.assign(media, { error: { code: 2 }, networkState: 3, readyState: 0,
    currentSrc: 'http://media.example.com/video.m3u8?token=secret#part', src: '', querySelector: () => null });
  listener({ target: media });
  assert.match(logs[0], /网络错误；code=2；network=3；ready=0/);
  assert.match(logs[0], /src=http:\/\/media\.example\.com\/video\.m3u8/);
  assert.doesNotMatch(logs[0], /secret|#part/);
  listener({ target: {} });
  assert.equal(logs.length, 1);
});
