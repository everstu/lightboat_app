const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/clipboard-support.js'), 'utf8');
function harness({ secure = true, active = true, nativeClipboard = false } = {}) {
  const messages = [];
  const timers = new Map();
  let timerId = 0;
  const navigator = { userActivation: { isActive: active } };
  const readText = () => Promise.reject(new Error('Not supported'));
  if (nativeClipboard) navigator.clipboard = { readText };
  const bridge = { postMessage: text => messages.push(JSON.parse(text)) };
  vm.runInNewContext(source, {
    window: { isSecureContext: secure, LightboatClipboard: bridge }, navigator, DOMException,
    setTimeout(callback) { timers.set(++timerId, callback); return timerId; },
    clearTimeout(id) { timers.delete(id); }
  });
  return { navigator, messages, timers, readText, reply(message) { bridge.onmessage({ data: JSON.stringify(message) }); } };
}

test('Writes plain text and resolves only when native clipboard acknowledges', async () => {
  const h = harness();
  const promise = h.navigator.clipboard.writeText('轻舟\nhttps://example.com');
  assert.deepEqual(h.messages, [{ id: '1', text: '轻舟\nhttps://example.com' }]);
  assert.equal(h.timers.size, 1);
  h.reply({ id: '1' });
  await promise;
  assert.equal(h.timers.size, 0);
});

test('Requires a user gesture and limits clipboard text size', async () => {
  const h = harness({ active: false });
  await assert.rejects(h.navigator.clipboard.writeText('x'), { name: 'NotAllowedError' });
  assert.equal(h.messages.length, 0);
  h.navigator.userActivation.isActive = true;
  await assert.rejects(h.navigator.clipboard.writeText('x'.repeat(65537)), { name: 'DataError' });
  assert.equal(h.messages.length, 0);
});

test('Does not expose clipboard reading or add an API on insecure origins', () => {
  const h = harness();
  assert.equal(h.navigator.clipboard.readText, undefined);
  assert.equal(harness({ secure: false }).navigator.clipboard, undefined);
  const native = harness({ nativeClipboard: true });
  assert.equal(native.navigator.clipboard.readText, native.readText);
});

test('Rejects native failures, ignores unrelated replies and clears timeouts', async () => {
  const h = harness();
  const promise = h.navigator.clipboard.writeText('x');
  h.reply({ id: 'unknown' });
  assert.equal(h.timers.size, 1);
  h.reply({ id: '1', error: 'Permission denied' });
  await assert.rejects(promise, { name: 'NotAllowedError', message: 'Permission denied' });
  assert.equal(h.timers.size, 0);
});

test('Times out when native side does not reply', async () => {
  const h = harness();
  const promise = h.navigator.clipboard.writeText('x');
  [...h.timers.values()][0]();
  await assert.rejects(promise, { name: 'NotAllowedError' });
  // A late native acknowledgement must not settle a new request.
  h.reply({ id: '1' });
});
