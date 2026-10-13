const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/native-guard.js'), 'utf8');
let passed = 0;
function harness(rootPresent) {
  const listeners = new Map();
  const styles = [];
  let observer;
  const document = {
    documentElement: rootPresent ? { appendChild: node => styles.push(node) } : null,
    createElement: tag => ({ tagName: tag, textContent: '' })
  };
  class MutationObserver {
    constructor(callback) { this.callback = callback; this.disconnected = false; observer = this; }
    observe(target, options) { assert.equal(target, document); assert.equal(options.childList, true); }
    disconnect() { this.disconnected = true; }
  }
  const window = { addEventListener(type, callback, options) {
    assert.equal(options.capture, true);
    assert.equal(options.passive, false);
    listeners.set(type, callback);
  }};
  vm.runInNewContext(source, { window, document, MutationObserver });
  function dispatch(type, extra = {}) {
    const event = {
      prevented: false, stopped: false,
      preventDefault() { this.prevented = true; },
      stopImmediatePropagation() { this.stopped = true; }, ...extra
    };
    listeners.get(type)?.(event);
    return event;
  }
  return { styles, listeners, dispatch, createRoot() {
    document.documentElement = { appendChild: node => styles.push(node) };
    observer.callback();
    assert.equal(observer.disconnected, true);
  }};
}
function test(name, body) { body(); passed++; console.log(`PASS ${name}`); }

test('Blocks browser menus, selection, cut and paste while allowing web copy handlers', () => {
  const h = harness(true);
  for (const type of ['contextmenu', 'selectstart', 'dragstart', 'cut', 'paste']) {
    const event = h.dispatch(type);
    assert.equal(event.prevented, true, type);
    assert.equal(event.stopped, true, type);
  }
  assert.equal(h.listeners.has('copy'), false);
  assert.equal(h.dispatch('copy').prevented, false);
});
test('Works before the document root exists', () => {
  const h = harness(false);
  assert.equal(h.styles.length, 0);
  h.createRoot();
  assert.equal(h.styles.length, 1);
});
test('Retains ordinary typing, touch scrolling and link clicks', () => {
  const h = harness(true);
  for (const type of ['touchstart', 'touchmove', 'click', 'input']) assert.equal(h.listeners.has(type), false);
  assert.equal(h.dispatch('beforeinput', { inputType: 'insertText' }).prevented, false);
  assert.equal(h.dispatch('keydown', { key: 'c', ctrlKey: false }).prevented, false);
  assert.equal(h.dispatch('keydown', { key: 'ArrowLeft', ctrlKey: false }).prevented, false);
  assert.match(h.styles[0].textContent, /input, textarea, \[contenteditable\]/);
  assert.match(h.styles[0].textContent, /user-select: text/);
});
test('Allows copy shortcuts while still blocking paste and cut', () => {
  const h = harness(true);
  for (const key of ['v', 'x', 'a']) {
    assert.equal(h.dispatch('keydown', { key, ctrlKey: true }).prevented, true);
  }
  for (const key of ['c', 'Insert']) assert.equal(h.dispatch('keydown', { key, ctrlKey: true }).prevented, false);
  assert.equal(h.dispatch('keydown', { key: 'v', metaKey: true }).prevented, true);
  assert.equal(h.dispatch('keydown', { key: 'Insert', shiftKey: true }).prevented, true);
  for (const inputType of ['insertFromPaste', 'insertFromPasteAsQuotation', 'insertFromDrop']) {
    assert.equal(h.dispatch('beforeinput', { inputType }).prevented, true);
  }
});
console.log(`${passed} interaction checks passed.`);
