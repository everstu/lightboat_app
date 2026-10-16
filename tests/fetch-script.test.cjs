const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/fetch-script.js'), 'utf8');
const scriptUrl = 'https://scripts.example.com/app.js';
async function fetchScript(body, headers = { 'Content-Type': 'application/javascript' }, status = 200) {
  const messages = [];
  const calls = [];
  await vm.runInNewContext(source, {
    scriptUrl, TextDecoder, Uint8Array,
    LightboatLoader: { postMessage: text => messages.push(JSON.parse(text)) },
    fetch: async (url, options) => {
      calls.push({ url, cache: options.cache, credentials: options.credentials, referrerPolicy: options.referrerPolicy });
      return new Response(body, { headers, status });
    }
  });
  assert.equal(messages.length, 1);
  return { message: messages[0], calls };
}

test('Forces a fresh fetch and returns raw code without executing it', async () => {
  const code = 'throw new Error("This code must not execute in the loader");';
  const result = await fetchScript(code);
  assert.equal(result.message.script, code);
  assert.deepEqual(result.calls, [{ url: scriptUrl, cache: 'no-store', credentials: 'same-origin', referrerPolicy: 'no-referrer' }]);
});
test('Decodes UTF-8 and retains Unicode and multiline source', async () => {
  const code = "window.name = '轻舟';\nwindow.ready = true;";
  assert.equal((await fetchScript('\uFEFF' + code)).message.script, code);
});
test('Rejects HTTP failures, HTML content and empty scripts', async () => {
  assert.match((await fetchScript('not found', undefined, 404)).message.error, /HTTP 404/);
  assert.match((await fetchScript('<html>login</html>', { 'Content-Type': 'text/html' })).message.error, /JavaScript/);
  assert.match((await fetchScript('<!doctype html><html>login</html>', { 'Content-Type': 'text/plain' })).message.error, /HTML/);
  assert.match((await fetchScript('  ')).message.error, /为空/);
});
test('Identifies a 418 CloudWAF block without passing the HTML or its contents to the script', async () => {
  const blocked = await fetchScript('<html>CloudWAF：您的请求疑似攻击行为</html>', { 'Content-Type': 'text/html' }, 418);
  assert.match(blocked.message.error, /HTTP 418（CloudWAF 拦截）/);
  assert.equal(blocked.message.script, undefined);
  assert.equal(blocked.calls.length, 1);
  assert.doesNotMatch(blocked.message.error, /<html>|疑似攻击行为/);
});
test('Bounds inspection of error responses and cancels their remaining stream', async () => {
  let cancelled = false;
  const messages = [];
  await vm.runInNewContext(source, {
    scriptUrl, TextDecoder, Uint8Array,
    LightboatLoader: { postMessage: text => messages.push(JSON.parse(text)) },
    fetch: async () => new Response(new ReadableStream({
      pull(controller) { controller.enqueue(new TextEncoder().encode('x'.repeat(4096))); },
      cancel() { cancelled = true; }
    }), { status: 418 })
  });
  assert.equal(cancelled, true);
  assert.match(messages[0].error, /HTTP 418/);
});
test('Limits both declared and actual response size', async () => {
  assert.match((await fetchScript('x', { 'Content-Type': 'text/javascript', 'Content-Length': '1048577' })).message.error, /1 MiB/);
  assert.match((await fetchScript('x'.repeat(1048577))).message.error, /1 MiB/);
});
test('Reports browser network or CORS errors', async () => {
  const messages = [];
  await vm.runInNewContext(source, {
    scriptUrl, TextDecoder, Uint8Array,
    LightboatLoader: { postMessage: text => messages.push(JSON.parse(text)) },
    fetch: async () => { throw new Error('Failed to fetch'); }
  });
  assert.equal(messages.length, 1);
  assert.equal(messages[0].error, 'Failed to fetch');
});

test('Preloads binary resource without JS MIME restriction and includes base64 bytes', async () => {
  const messages = [];
  const bytes = new Uint8Array([0, 255, 128, 65]);
  await vm.runInNewContext(source, {
    scriptUrl, assetMode: 'resource', TextDecoder, Uint8Array,
    btoa: text => Buffer.from(text, 'binary').toString('base64'),
    LightboatLoader: { postMessage: text => messages.push(JSON.parse(text)) },
    fetch: async () => new Response(bytes, { headers: { 'Content-Type': 'image/png' } })
  });
  assert.equal(messages[0].mime, 'image/png');
  assert.deepEqual([...Buffer.from(messages[0].base64, 'base64')], [...bytes]);
});

test('Required JS assets are returned as text plus bytes for integrity verification', async () => {
  const messages = [];
  await vm.runInNewContext(source, {
    scriptUrl, assetMode: 'require', TextDecoder, Uint8Array,
    btoa: text => Buffer.from(text, 'binary').toString('base64'),
    LightboatLoader: { postMessage: text => messages.push(JSON.parse(text)) },
    fetch: async () => new Response('window.lib=true;', { headers: { 'Content-Type': 'application/javascript' } })
  });
  assert.equal(messages[0].text, 'window.lib=true;');
  assert.equal(Buffer.from(messages[0].base64, 'base64').toString(), messages[0].text);
});
