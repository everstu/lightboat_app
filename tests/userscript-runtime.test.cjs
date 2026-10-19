const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const assets = path.join(__dirname, '../app/src/main/assets');
const matcher = fs.readFileSync(path.join(assets, 'userscript-matcher.js'), 'utf8');
const runtime = fs.readFileSync(path.join(assets, 'userscript-runtime.js'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));

function harness(meta = {}, { url = 'https://example.com/page', frame = false, state = 'loading', values = {}, deferWrites = false, streamSnapshot = false,
  timers = { setTimeout, clearTimeout } } = {}) {
  const messages = [], nodes = [], listeners = {}, observers = [];
  const head = { appendChild(node) { nodes.push(node); } };
  const document = {
    readyState: state, head, documentElement: head, body: state === 'loading' ? null : {},
    createElement(tagName) { return { tagName, setAttribute(key, value) { this[key] = value; } }; },
    addEventListener(event, callback) { listeners[event] = callback; }
  };
  const location = new URL(url);
  const window = { addEventListener(event, callback) { listeners['window:' + event] = callback; } };
  window.top = frame ? {} : window;
  const bridge = { postMessage(text) {
    const msg = JSON.parse(text); messages.push(msg);
    if (msg.op === 'hello' && streamSnapshot) return;
    if (['valueBegin', 'valueChunk'].includes(msg.op)) {
      bridge.onmessage({data: JSON.stringify({doc: msg.doc, id: msg.id, result: null})}); return;
    }
    if (['hello', 'setValue', 'deleteValue', 'menuRegister', 'menuRemove', 'clipboard', 'notification'].includes(msg.op) &&
        !(deferWrites && ['setValue', 'deleteValue'].includes(msg.op))) {
      bridge.onmessage({ data: JSON.stringify({ doc: msg.doc, id: msg.id, result: msg.op === 'hello' ? values : null }) });
    }
  }};
  window.LightboatGM = bridge;
  const spec = { token: 'test-token', url: 'https://scripts.example.com/app.user.js', resources: {}, values: {},
    meta: { name: 'Test', runAt: 'document-idle', header: '// ==UserScript==', ...meta },
    parameters: ['GM', 'GM_info', 'unsafeWindow', 'GM_getValue', 'GM_setValue', 'GM_deleteValue', 'GM_listValues',
      'GM_getValues', 'GM_setValues', 'GM_deleteValues', 'GM_addValueChangeListener', 'GM_removeValueChangeListener',
      'GM_addStyle', 'GM_addElement', 'GM_getResourceText', 'GM_getResourceURL', 'GM_getResourceUrl',
      'GM_registerMenuCommand', 'GM_unregisterMenuCommand', 'GM_xmlhttpRequest', 'GM_setClipboard', 'GM_log',
      'GM_notification', 'GM_openInTab'] };
  const context = vm.createContext({ window, document, location, URL, URLSearchParams, console, DOMException,
    Blob, TextDecoder, Uint8Array, ArrayBuffer, ...timers,
    atob: text => Buffer.from(text, 'base64').toString('binary'), btoa: text => Buffer.from(text, 'binary').toString('base64'),
    crypto: { randomUUID: () => 'frame-1' },
    MutationObserver: class { constructor(callback) { this.callback = callback; observers.push(this); } observe() {} disconnect() { this.disconnected = true; } },
    MouseEvent: class { constructor(type) { this.type = type; } },
  });
  vm.runInContext(matcher + '\n' + runtime, context);
  return { context, spec, messages, nodes, listeners, observers, document,
    run(body) { vm.runInContext(`LightboatRuntime(spec, function(${spec.parameters.join(',')}) {${body}\n});`,
      vm.createContext(Object.assign(context, { spec }))); },
    ready() { document.readyState = 'interactive'; document.body = {}; listeners.DOMContentLoaded?.(); },
    reply(message) { bridge.onmessage({ data: JSON.stringify({ doc: 'frame-1', ...message }) }); },
  };
}

function deliverChunked(h, message, transfer = 'transfer-1') {
  const text = JSON.stringify({doc: 'frame-1', ...message});
  for (let offset = 0, index = 0; offset < text.length; index++) {
    let end = Math.min(offset + 32768, text.length);
    if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1])) end--;
    h.reply({event:'bridgeChunk', transfer, index, total:text.length, text:text.slice(offset,end), last:end===text.length});
    offset = end;
  }
}

test('Two userscripts use separate bridge handlers, storage snapshots, grants and menu callbacks', async () => {
  const h = harness({ runAt: 'document-start', grant: ['GM_getValue', 'GM_registerMenuCommand'] },
    { values: { owner: { type: 'json', value: 'first' } } });
  h.run(`window.firstOwner=GM_getValue('owner');window.firstMenu=GM_registerMenuCommand('Same',()=>window.firstClicked=true);`);
  await flush();
  const firstHandler = h.context.window.LightboatGM.onmessage;
  const secondMessages = [];
  const secondBridge = { postMessage(text) {
    const msg = JSON.parse(text); secondMessages.push(msg);
    if (['hello', 'menuRegister'].includes(msg.op)) secondBridge.onmessage({ data: JSON.stringify({ doc: msg.doc, id: msg.id,
      result: msg.op === 'hello' ? { owner: { type: 'json', value: 'second' } } : null }) });
  } };
  h.context.window.ScriptTwoBridge = secondBridge;
  h.context.secondSpec = { ...h.spec, token: 'second-token', bridgeName: 'ScriptTwoBridge' };
  vm.runInContext(`LightboatRuntime(secondSpec,function(${h.spec.parameters.join(',')}){
    window.secondOwner=GM_getValue('owner');window.secondMenu=GM_registerMenuCommand('Same',()=>window.secondClicked=true);
  });`, h.context);
  await flush();
  assert.equal(h.context.window.firstOwner, 'first');
  assert.equal(h.context.window.secondOwner, 'second');
  assert.equal(h.context.window.LightboatGM.onmessage, firstHandler);
  secondBridge.onmessage({ data: JSON.stringify({ doc: 'frame-1', event: 'menu', menuId: h.context.window.secondMenu }) });
  assert.equal(h.context.window.secondClicked, true);
  assert.equal(h.context.window.firstClicked, undefined);
  h.reply({ event: 'menu', menuId: h.context.window.firstMenu });
  assert.equal(h.context.window.firstClicked, true);
  assert.ok(secondMessages.every(msg => msg.token === 'second-token'));
});

test('Restores a large per-key snapshot before document-start and chunks large writes transparently', async () => {
  const h = harness({runAt:'document-start',grant:['GM_getValue','GM.setValue']}, {streamSnapshot:true});
  h.run('window.cached=GM_getValue("large"); window.save=()=>GM.setValue("large",window.cached);');
  assert.equal(h.context.window.cached,undefined);
  const value='中🙂'.repeat(300000);
  deliverChunked(h,{event:'snapshotValue',key:'large',value:{type:'json',value}});
  h.reply({id:h.messages.find(m=>m.op==='hello').id,result:{}});
  await flush();
  assert.equal(h.context.window.cached,value);
  await h.context.window.save();
  const begin=h.messages.find(m=>m.op==='valueBegin'), chunks=h.messages.filter(m=>m.op==='valueChunk');
  assert.ok(begin.characters>32768);
  assert.ok(chunks.length>10);
  assert.equal(chunks.map(m=>m.text).join(''),JSON.stringify({type:'json',value}));
  assert.ok(chunks.every((m,index)=>m.index===index&&m.text.length<=32768));
  assert.equal(chunks.at(-1).last,true);
  assert.ok(!h.messages.some(m=>m.op==='setValue'));
});

test('Assembles streamed text and binary responses without changing callback or response types', async () => {
  const h=harness({runAt:'document-start',grant:['GM.xmlHttpRequest']});
  h.run('window.text=GM.xmlHttpRequest({url:"/text",onload:r=>window.callbackText=r.responseText}); window.binary=GM.xmlHttpRequest({url:"/binary",responseType:"arraybuffer"});');
  const [text,binary]=h.messages.filter(m=>m.op==='xhr');
  const piece='hello 中🙂'.repeat(1000);
  for(let index=0;index<10;index++)deliverChunked(h,{id:text.id,event:'xhrChunk',result:{index,kind:'text',text:piece}},'text-'+index);
  h.reply({id:text.id,result:{status:200,chunked:true,responseText:''}});
  const a=Buffer.from([0,255,1]),b=Buffer.from([2,3]);
  [a,b].forEach((bytes,index)=>deliverChunked(h,{id:binary.id,event:'xhrChunk',result:{index,kind:'base64',text:bytes.toString('base64')}},'binary-'+index));
  h.reply({id:binary.id,result:{status:200,chunked:true,responseText:''}});
  assert.equal((await h.context.window.text).responseText,piece.repeat(10));
  assert.equal(h.context.window.callbackText,piece.repeat(10));
  assert.deepEqual(Buffer.from((await h.context.window.binary).response),Buffer.concat([a,b]));
});

test('Rejects out-of-order packets and ignores completed or aborted response chunks', async () => {
  const h=harness({runAt:'document-start',grant:['GM.xmlHttpRequest']});
  h.run('window.result=GM.xmlHttpRequest({url:"/large"}).catch(e=>e.event); window.request=GM.xmlHttpRequest({url:"/abort"}); window.cancel=window.request.catch(e=>e.event);');
  const requests=h.messages.filter(m=>m.op==='xhr');
  h.reply({event:'bridgeChunk',transfer:'bad',index:1,total:10,text:'bad',last:true});
  assert.ok(!h.messages.some(m=>m.op==='chunkAck'&&m.transfer==='bad'));
  deliverChunked(h,{id:requests[1].id,event:'xhrChunk',result:{index:0,kind:'text',text:'partial'}});
  h.context.window.request.abort();
  assert.equal(await h.context.window.cancel,'abort');
  deliverChunked(h,{id:requests[1].id,event:'xhrChunk',result:{index:1,kind:'text',text:'ignored'}},'late');
  h.reply({id:requests[0].id,error:'response too large',event:'error'});
  assert.equal(await h.context.window.result,'error');
});

test('Match patterns use host boundaries, schemes, paths and exclusion rules', () => {
  const h = harness();
  const check = (meta, url, main = true) => h.context.LightboatMatches(meta, url, main);
  const meta = { match: ['*://*.example.com/watch*'], exclude: ['*private*'] };
  assert.equal(check(meta, 'https://example.com/watch?a=1'), true);
  assert.equal(check(meta, 'http://a.example.com/watch'), true);
  assert.equal(check(meta, 'https://evil-example.com/watch'), false);
  assert.equal(check(meta, 'https://example.com/watch/private'), false);
  assert.equal(check(meta, 'https://elsewhere.com/?https://example.com/watch'), false);
  assert.equal(check({ include: ['/example\\.com/'], noFrames: true }, 'https://example.com/a', false), false);
  assert.equal(check({ match: ['https://example.com/*'], 'exclude-match': ['https://example.com/admin/*'] }, 'https://example.com/admin/one'), false);
});

test('Default idle execution waits for a body and executes once after DOMContentLoaded', async () => {
  const h = harness({ grant: ['none'] });
  h.run('window.runs=(window.runs||0)+1; document.body.appended=true;');
  assert.equal(h.context.window.runs, undefined);
  h.ready(); await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(h.context.window.runs, 1);
  assert.equal(h.document.body.appended, true);
  const executed = h.messages.find(message => message.op === 'log' && message.text.includes('已执行'));
  assert.match(executed.text, /主页面；example\.com\/page/);
});

test('Reports matching before execution and explains CSP dynamic-code failures', async () => {
  const h = harness({ grant: ['none'], name: 'Encrypted script' }, { state: 'complete' });
  h.run('window.injected=true;');
  assert.ok(h.messages.some(message => message.op === 'log' && message.text.includes('已匹配')));
  h.listeners.securitypolicyviolation({ blockedURI: 'eval', effectiveDirective: 'script-src' });
  assert.ok(h.messages.some(message => message.op === 'log' && message.level === 'error' &&
    message.text.includes('CSP 阻止动态代码执行')));
  await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(h.context.window.injected, true);
});

test('Document-start runs immediately, document-body waits only for the body', () => {
  const early = harness({ runAt: 'document-start', grant: ['none'] });
  early.run('window.started=true;');
  assert.equal(early.context.window.started, true);
  const body = harness({ runAt: 'document-body', grant: ['none'] });
  body.run('window.started=true;');
  assert.equal(body.context.window.started, undefined);
  body.document.body = {};
  body.observers[0].callback();
  assert.equal(body.context.window.started, true);
  assert.equal(body.observers[0].disconnected, true);
});

test('Document-end and already-loaded idle scripts execute at the right phase', async () => {
  const end = harness({ runAt: 'document-end', grant: ['none'] });
  end.run('window.started=true;'); end.ready();
  assert.equal(end.context.window.started, true);
  const idle = harness({ grant: ['none'] }, { state: 'complete' });
  idle.run('window.started=true;');
  assert.equal(idle.context.window.started, undefined);
  await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(idle.context.window.started, true);
});

test('Non-matching pages and noframes do not contact the native bridge or execute', () => {
  for (const h of [harness({ match: ['https://other.example/*'] }), harness({ noFrames: true }, { frame: true })]) {
    h.run('window.started=true;');
    assert.equal(h.context.window.started, undefined);
    assert.equal(h.messages.length, 0);
  }
});

test('Execution logs distinguish matching child frames', async () => {
  const h = harness({ grant: ['none'] }, { frame: true, state: 'complete' });
  h.run('window.started=true;');
  await new Promise(resolve => setTimeout(resolve, 5));
  const executed = h.messages.find(message => message.op === 'log' && message.text.includes('已执行'));
  assert.match(executed.text, /iframe；example\.com\/page/);
});

test('Legacy storage is synchronous after fresh native snapshot and updates listeners', async () => {
  const h = harness({ runAt: 'document-start', grant: ['GM_getValue', 'GM_setValue', 'GM_deleteValue',
    'GM_listValues', 'GM_addValueChangeListener'] }, { values: { existing: { type: 'json', value: 7 } } });
  h.run(`window.changes=[]; window.initial=GM_getValue('existing');
    GM_addValueChangeListener('count',(key,old,next,remote)=>window.changes.push([key,old,next,remote]));
    GM_setValue('count',3); window.local=GM_getValue('count'); window.keys=GM_listValues();
    window.missing=GM_getValue('missing','fallback');`);
  await flush();
  assert.equal(h.context.window.initial, 7);
  assert.equal(h.context.window.local, 3);
  assert.equal(h.context.window.missing, 'fallback');
  assert.equal(h.context.window.changes[0][3], false);
  h.reply({ event: 'value', key: 'count', value: { type: 'json', value: 9 } });
  assert.equal(h.context.window.changes[1][2], 9);
  assert.equal(h.context.window.changes[1][3], true);
  assert.ok(h.messages.some(msg => msg.op === 'setValue'));
});

test('Modern storage promises preserve nested values and undefined', async () => {
  const h = harness({ runAt: 'document-start', grant: ['GM.getValue', 'GM.setValue', 'GM.deleteValue'] });
  h.run(`window.finished=(async()=>{await GM.setValue('obj',{nested:[1,true]});
    window.stored=await GM.getValue('obj'); await GM.setValue('empty',undefined);
    window.empty=await GM.getValue('empty','fallback'); await GM.deleteValue('obj');
    window.deleted=await GM.getValue('obj','gone');})();`);
  await flush(); await h.context.window.finished;
  assert.equal(JSON.stringify(h.context.window.stored), '{"nested":[1,true]}');
  assert.equal(h.context.window.empty, undefined);
  assert.equal(h.context.window.deleted, 'gone');
});

test('Earlier remote notifications cannot overwrite a newer pending local write', async () => {
  const h = harness({ runAt: 'document-start', grant: ['GM_getValue', 'GM_setValue'] },
    { deferWrites: true, timers: fakeTimers().api });
  h.run(`window.read=()=>GM_getValue('count');window.write=n=>GM_setValue('count',n);`);
  await flush();
  h.context.window.write(2);
  const first = h.messages.find(msg => msg.op === 'setValue');
  h.context.window.write(3);
  const last = h.messages.filter(msg => msg.op === 'setValue').at(-1);
  h.reply({ event: 'value', key: 'count', value: { type: 'json', value: 1 } });
  assert.equal(h.context.window.read(), 3);
  h.reply({ id: first.id, result: null }); await flush();
  h.reply({ event: 'value', key: 'count', value: { type: 'json', value: 2 } });
  assert.equal(h.context.window.read(), 3);
  h.reply({ id: last.id, result: null }); await flush();
  h.reply({ event: 'value', key: 'count', value: { type: 'json', value: 4 } });
  assert.equal(h.context.window.read(), 4);
});

test('Grant none exposes info but no privileged APIs; grant list exposes only listed APIs', () => {
  const h = harness({ runAt: 'document-start', grant: ['none'] });
  h.run('window.info=GM_info; window.privileged=typeof GM_setValue; window.gm=typeof GM;');
  assert.equal(h.context.window.info.scriptHandler, 'Lightboat');
  assert.equal(h.context.window.privileged, 'undefined');
  assert.equal(h.context.window.gm, 'undefined');
  const limited = harness({ runAt: 'document-start', grant: ['GM_addStyle'] });
  limited.run('window.other=typeof GM_getValue; GM_addStyle("body { color:red }");');
  assert.equal(limited.context.window.other, 'undefined');
  assert.equal(limited.nodes[0].textContent, 'body { color:red }');
});

test('Resource APIs return preloaded text/data and menus invoke correct callbacks', async () => {
  const h = harness({ runAt: 'document-start', grant: ['GM_getResourceText', 'GM_getResourceURL', 'GM_registerMenuCommand', 'GM_unregisterMenuCommand'] });
  h.spec.resources.style = { text: 'body{}', base64: 'Ym9keXt9', mime: 'text/css' };
  h.run(`window.resource=GM_getResourceText('style'); window.resourceURL=GM_getResourceURL('style');
    window.menu=GM_registerMenuCommand('Run',()=>window.clicked=true);
    window.unregister=()=>GM_unregisterMenuCommand(window.menu);`);
  assert.equal(h.context.window.resource, 'body{}');
  assert.equal(h.context.window.resourceURL, 'data:text/css;base64,Ym9keXt9');
  h.reply({ event: 'menu', menuId: h.context.window.menu });
  assert.equal(h.context.window.clicked, true);
  h.context.window.unregister();
  assert.ok(h.messages.some(msg => msg.op === 'menuRemove'));
});

test('Restored documents reconnect storage and menu callbacks without rerunning the script', async () => {
  const snapshot = { current: { type: 'json', value: 'first' }, removed: { type: 'json', value: true } };
  const h = harness({ runAt: 'document-start', grant: ['GM_getValue', 'GM_registerMenuCommand'] },
    { values: snapshot });
  h.run(`window.runs=(window.runs||0)+1; window.read=()=>GM_getValue('current');
    window.removed=()=>GM_getValue('removed','gone');
    window.menu=GM_registerMenuCommand('Inspect',()=>window.clicked=true);`);
  await flush();
  assert.equal(h.context.window.runs, 1);
  h.listeners['window:pagehide']({ persisted: true });
  snapshot.current = { type: 'json', value: 'second' };
  delete snapshot.removed;
  h.listeners['window:pageshow']({ persisted: true });
  h.listeners['window:pageshow']({ persisted: true });
  await flush();
  assert.equal(h.messages.filter(msg => msg.op === 'hello').length, 2);
  const registrations = h.messages.filter(msg => msg.op === 'menuRegister');
  assert.equal(registrations.length, 2);
  assert.equal(registrations[1].title, 'Inspect');
  assert.equal(registrations[1].menuId, registrations[0].menuId);
  assert.equal(h.context.window.read(), 'second');
  assert.equal(h.context.window.removed(), 'gone');
  assert.equal(h.context.window.runs, 1);
  h.reply({ event: 'menu', menuId: h.context.window.menu });
  assert.equal(h.context.window.clicked, true);
});

test('Restoring a streamed snapshot only notifies actual value changes and preserves old values until ready', async () => {
  const h = harness({ runAt: 'document-start', grant: ['GM_getValue', 'GM_addValueChangeListener'] },
    { streamSnapshot: true, timers: fakeTimers().api });
  h.run(`window.changes=[]; window.read=key=>GM_getValue(key);
    for(const key of ['stable','current','removed','added','empty'])
      GM_addValueChangeListener(key,(...args)=>window.changes.push(args));`);
  const initial = { stable: { a: ['large'.repeat(10000), true], b: 2 }, current: 7, removed: true };
  for (const [key, value] of Object.entries(initial)) deliverChunked(h, { event: 'snapshotValue', key, value: { type: 'json', value } });
  deliverChunked(h, { event: 'snapshotValue', key: 'empty', value: { type: 'undefined' } });
  h.reply({ id: h.messages.find(m => m.op === 'hello').id, result: {} });
  await flush();
  h.listeners['window:pagehide']({ persisted: true });
  await flush();
  h.listeners['window:pageshow']({ persisted: true });
  assert.equal(h.context.window.read('current'), 7);
  for (const [key, value] of Object.entries({ stable: { b: 2, a: initial.stable.a }, current: 9, added: 42 }))
    deliverChunked(h, { event: 'snapshotValue', key, value: { type: 'json', value } });
  deliverChunked(h, { event: 'snapshotValue', key: 'empty', value: { type: 'undefined' } });
  assert.equal(h.context.window.changes.length, 0, 'Snapshot must not expose partially restored values');
  h.reply({ id: h.messages.filter(m => m.op === 'hello').at(-1).id, result: {} });
  await flush();
  assert.equal(h.context.window.read('current'), 9);
  assert.equal(h.context.window.read('removed'), undefined);
  assert.equal(h.context.window.changes.length, 3);
  const changes = Object.fromEntries(h.context.window.changes.map(([key, old, value, remote]) => [key, [old, value, remote]]));
  assert.deepEqual(changes.current, [7, 9, true]);
  assert.deepEqual(changes.removed, [true, undefined, true]);
  assert.deepEqual(changes.added, [undefined, 42, true]);
});

test('A document suspended during its initial snapshot executes once after restoration', async () => {
  const h = harness({ runAt: 'document-start', grant: ['GM_getValue', 'GM_registerMenuCommand'] },
    { streamSnapshot: true });
  h.run(`window.runs=(window.runs||0)+1; window.cached=GM_getValue('cache');
    GM_registerMenuCommand('Inspect',()=>{});`);
  h.listeners['window:pagehide']({ persisted: true });
  await flush();
  h.listeners['window:pageshow']({ persisted: true });
  const hello = h.messages.filter(msg => msg.op === 'hello').at(-1);
  deliverChunked(h, { event: 'snapshotValue', key: 'cache', value: { type: 'json', value: 'restored' } });
  h.reply({ id: hello.id, result: {} });
  await flush();
  assert.equal(h.context.window.runs, 1);
  assert.equal(h.context.window.cached, 'restored');
  assert.equal(h.messages.filter(msg => msg.op === 'menuRegister').length, 1);
});

test('Unavailable named resources return an empty value and warn without stopping page execution', () => {
  const h = harness({ runAt: 'document-start', grant: ['GM_getResourceText'] });
  h.spec.resources.style = { text: '', base64: '', mime: 'application/octet-stream', error: 'HTTP 418' };
  h.run('window.resource=GM_getResourceText("style"); window.pageContinues=true;');
  assert.equal(h.context.window.resource, '');
  assert.equal(h.context.window.pageContinues, true);
  assert.ok(h.messages.some(msg => msg.op === 'log' && msg.level === 'warning' && msg.text.includes('@resource style 下载失败，使用空资源：HTTP 418')));
});

test('GM XHR returns JSON, callback events, context and abort-capable modern promises', async () => {
  const h = harness({ runAt: 'document-start', grant: ['GM.xmlHttpRequest'] });
  h.run(`window.events=[]; window.response=GM.xmlHttpRequest({url:'/api',responseType:'json',context:'test',
    onprogress:r=>window.events.push(r.loaded),onload:r=>window.events.push(r.context)});`);
  const request = h.messages.find(msg => msg.op === 'xhr');
  assert.equal(request.target, 'https://example.com/api');
  assert.equal(typeof h.context.window.response.abort, 'function');
  h.reply({ id: request.id, event: 'progress', result: { loaded: 12 } });
  h.reply({ id: request.id, event: 'load', result: { status: 200, responseText: '{"ok":true}', finalUrl: request.target } });
  const response = await h.context.window.response;
  assert.equal(response.response.ok, true);
  assert.equal(response.context, 'test');
  assert.deepEqual([...h.context.window.events], [12, 'test']);
});

test('XHR abort and unsupported request options fail explicitly', async () => {
  const h = harness({ runAt: 'document-start', grant: ['GM.xmlHttpRequest'] });
  h.run(`window.response=GM.xmlHttpRequest({url:'/api'}); window.response.abort();
    try { GM.xmlHttpRequest({url:'/api',responseType:'stream'}); } catch(e) { window.unsupported=e.message; }`);
  const request = h.messages.find(msg => msg.op === 'xhr');
  assert.ok(h.messages.some(msg => msg.op === 'abort' && msg.requestId === request.id));
  h.reply({ id: request.id, event: 'abort', error: 'cancelled' });
  await assert.rejects(h.context.window.response, /已取消/);
  assert.match(h.context.window.unsupported, /暂不支持/);
});

function fakeTimers() {
  let next = 0;
  const pending = new Map();
  return { pending, api: {
    setTimeout(callback, delay) { const id = ++next; pending.set(id, { callback, delay }); return id; },
    clearTimeout(id) { pending.delete(id); },
  }, fire() { const first = pending.entries().next().value; pending.delete(first[0]); first[1].callback(); } };
}

test('XHR timeout includes time waiting for native work and ignores a late response', async () => {
  const timers = fakeTimers();
  const h = harness({ runAt: 'document-start', grant: ['GM.xmlHttpRequest'] }, { timers: timers.api });
  h.run(`window.events=[];window.response=GM.xmlHttpRequest({url:'/queued',timeout:25,
    ontimeout:()=>window.events.push('timeout'),onload:()=>window.events.push('load')});`);
  const request = h.messages.find(msg => msg.op === 'xhr');
  assert.equal([...timers.pending.values()][0].delay, 25);
  const rejected = assert.rejects(h.context.window.response, error => error.event === 'timeout');
  timers.fire(); await rejected;
  assert.ok(h.messages.some(msg => msg.op === 'abort' && msg.requestId === request.id));
  h.reply({ id: request.id, event: 'load', result: { status: 200, responseText: 'late' } });
  await flush();
  assert.deepEqual([...h.context.window.events], ['timeout']);
  assert.equal(timers.pending.size, 0);
});

test('XHR zero timeout and excessive timeout use the documented native cap', async () => {
  for (const timeout of [0, 999999]) {
    const timers = fakeTimers();
    const h = harness({ runAt: 'document-start', grant: ['GM.xmlHttpRequest'] }, { timers: timers.api });
    h.run(`window.response=GM.xmlHttpRequest({url:'/api',timeout:${timeout}});`);
    const request = h.messages.find(msg => msg.op === 'xhr');
    assert.equal(request.timeout, 120000);
    assert.equal([...timers.pending.values()][0].delay, 120000);
    h.reply({ id: request.id, event: 'load', result: { status: 200, responseText: '' } });
    await h.context.window.response;
  }
});

test('XHR abort settles immediately even when the native worker has not started', async () => {
  const timers = fakeTimers();
  const h = harness({ runAt: 'document-start', grant: ['GM.xmlHttpRequest'] }, { timers: timers.api });
  h.run(`window.events=[];window.response=GM.xmlHttpRequest({url:'/queued',
    onabort:()=>window.events.push('abort'),onload:()=>window.events.push('load')});`);
  const request = h.messages.find(msg => msg.op === 'xhr');
  const rejected = assert.rejects(h.context.window.response, error => error.event === 'abort');
  h.context.window.response.abort(); h.context.window.response.abort();
  assert.equal(timers.pending.size, 0);
  await rejected;
  h.reply({ id: request.id, event: 'load', result: { status: 200, responseText: 'late' } });
  await flush();
  assert.deepEqual([...h.context.window.events], ['abort']);
  assert.equal(h.messages.filter(msg => msg.op === 'abort' && msg.requestId === request.id).length, 1);
});

test('Script errors are reported without aborting browser page execution', () => {
  const h = harness({ runAt: 'document-start', grant: ['none'] });
  h.run('throw new Error("DOM not ready");');
  assert.ok(h.messages.some(msg => msg.op === 'log' && msg.level === 'error' && msg.text.includes('DOM not ready')));
  assert.ok(h.messages.some(msg => msg.op === 'executionFailed' && msg.text.includes('DOM not ready')));
});

test('Only startup failures request a visit decision; ordinary GM request errors stay in callbacks', async () => {
  const h = harness({ runAt:'document-start', grant:['GM.xmlHttpRequest'] });
  h.run('window.request=GM.xmlHttpRequest({url:"/missing",onerror:()=>window.failed=true}).catch(()=>{});');
  const request=h.messages.find(msg=>msg.op==='xhr');
  h.reply({id:request.id,event:'error',error:'network unavailable'});
  await flush();
  assert.equal(h.context.window.failed,true);
  assert.ok(!h.messages.some(msg=>msg.op==='executionFailed'));
});

test('Bulk storage retains reserved object keys without prototype mutation', async () => {
  const h = harness({ runAt: 'document-start', grant: ['GM_setValue', 'GM_getValues', 'GM.getValues'] });
  h.run(`GM_setValue('__proto__',{safe:42}); window.legacy=GM_getValues(['__proto__']);
    window.modern=GM.getValues(['__proto__']);`);
  await flush();
  assert.equal(Object.getPrototypeOf(h.context.window.legacy), null);
  assert.equal(h.context.window.legacy.__proto__.safe, 42);
  const modern = await h.context.window.modern;
  assert.equal(Object.getPrototypeOf(modern), null);
  assert.equal(modern.__proto__.safe, 42);
});

test('XHR state callbacks include loadstart, response headers, progress and final load', async () => {
  const h = harness({ runAt: 'document-start', grant: ['GM.xmlHttpRequest'] });
  h.run(`window.states=[];window.response=GM.xmlHttpRequest({url:'/api',context:'state',
    onreadystatechange:r=>window.states.push([r.readyState,r.context])});`);
  const request = h.messages.find(msg => msg.op === 'xhr');
  for (const [event, readyState] of [['loadstart', 1], ['readystatechange', 2], ['progress', 3]]) {
    h.reply({ id: request.id, event, result: { readyState, status: readyState === 1 ? 0 : 200 } });
  }
  h.reply({ id: request.id, event: 'load', result: { status: 200, responseText: '' } });
  await h.context.window.response;
  assert.equal(JSON.stringify(h.context.window.states), '[[1,"state"],[2,"state"],[3,"state"],[4,"state"]]');
});

test('Modern notifications accept text and reject invalid or unsupported options', async () => {
  const h = harness({ runAt: 'document-start', grant: ['GM.notification'] });
  h.run(`window.notified=GM.notification('hello','title');
    window.invalid=GM.notification(null);window.unsupported=GM.notification({text:'x',image:'https://example.com/a.png'});`);
  await h.context.window.notified;
  await assert.rejects(h.context.window.invalid, /参数无效/);
  await assert.rejects(h.context.window.unsupported, /仅支持/);
  assert.ok(h.messages.some(message => message.op === 'notification' && message.text === 'title\nhello'));
});
