function LightboatRuntime(spec, entry) {
  'use strict';
  const meta = spec.meta;
  const bridgeName = spec.bridgeName || 'LightboatGM';
  const bridge = window[bridgeName];
  const nativePost = bridge.postMessage.bind(bridge);
  const stringify = JSON.stringify.bind(JSON), parse = JSON.parse.bind(JSON);
  const create = Object.create.bind(Object), assign = Object.assign.bind(Object);
  const apply = Reflect.apply.bind(Reflect);
  if (!delete window[bridgeName] || window[bridgeName] !== undefined) throw new Error('无法保护脚本通信接口，请更新系统 WebView');
  if (!LightboatMatches(meta, location.href, window === window.top)) return;
  const doc = (globalThis.crypto?.randomUUID?.() || Date.now() + '-' + Math.random()).toString();
  const pending = new Map();
  const incoming = new Map();
  const MAX_BYTES = 10 * 1024 * 1024, CHUNK_CHARS = 32 * 1024;
  const values = Object.assign(Object.create(null), spec.values);
  const valueWrites = new Map();
  const valueListeners = new Map();
  const menus = new Map();
  let suspended = false, reconnecting = false, executed = false, scheduled = false;
  let restoredValues = null;
  const unavailableResourceWarnings = new Set();
  let sequence = 0;
  const grants = new Set(meta.grant || []);
  const permitted = name => !grants.has('none') && grants.has(name);
  const errorText = error => error?.stack || error?.message || String(error);
  const post = data => nativePost(stringify(assign(create(null), data, { token: spec.token, doc, url: location.href })));
  const report = (level, text) => {
    try { post({ op: 'log', level, text: String(text).slice(0, 8000) }); }
    catch (_) { console.error('[Lightboat]', text); }
  };
  const executionFailed = error => {
    report('error', errorText(error));
    try { post({ op: 'executionFailed', text: errorText(error).slice(0, 1000) }); } catch (_) {}
  };
  const invoke = (fn, args) => {
    try { return apply(fn, undefined, args); } catch (error) { report('error', errorText(error)); }
  };
  const rpc = (op, data = {}, onEvent) => {
    const id = String(++sequence);
    const promise = new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        const request = pending.get(id);
        if (request) request.chunks = null;
        pending.delete(id);
        if (op === 'xhr') { try { post({ op: 'abort', requestId: id }); } catch (_) {} }
        if (op === 'valueBegin' || op === 'valueChunk') { try { post({ op: 'valueAbort' }); } catch (_) {} }
        reject(Object.assign(new Error(op + ' 请求超时'), { event: 'timeout' }));
      }, op === 'xhr' ? data.timeout : 60000);
      pending.set(id, { resolve, reject, timer, onEvent });
      try { post({ op, id, ...data }); } catch (error) { clearTimeout(timer); pending.delete(id); reject(error); }
    });
    return { id, promise };
  };
  const encode = value => {
    if (value === undefined) return { type: 'undefined' };
    const serialized = JSON.stringify(value);
    if (serialized === undefined) throw new TypeError('GM 值必须可以序列化为 JSON');
    return { type: 'json', value: JSON.parse(serialized) };
  };
  const decode = value => value?.type === 'undefined' ? undefined : value?.value;
  const notifyValue = (key, previous, next, remote) => {
    for (const listener of valueListeners.values()) {
      if (listener.key === key) invoke(listener.callback, [key, decode(previous), decode(next), remote]);
    }
  };
  // Compare decoded JSON without serializing large storage values a second time.
  const sameValue = (a, b) => {
    if (a === b) return true;
    if (!a || !b || typeof a !== 'object' || typeof b !== 'object' || Array.isArray(a) !== Array.isArray(b)) return false;
    const keys = Object.keys(a);
    return keys.length === Object.keys(b).length && keys.every(key =>
      Object.prototype.hasOwnProperty.call(b, key) && sameValue(a[key], b[key]));
  };
  const receive = message => {
    if (message.event === 'value' || message.event === 'snapshotValue') {
      // Native replies are ordered. Until our newer local writes are acknowledged,
      // an earlier frame notification must not roll back the optimistic snapshot.
      const writing = valueWrites.get(message.key);
      if (writing) { writing.confirmed = message.deleted ? undefined : message.value; return; }
      if (restoredValues) {
        if (message.deleted) delete restoredValues[message.key]; else restoredValues[message.key] = message.value;
        return;
      }
      const old = values[message.key];
      if (message.deleted) delete values[message.key]; else values[message.key] = message.value;
      notifyValue(message.key, old, values[message.key], true);
      return;
    }
    if (message.event === 'menu') {
      const menu = menus.get(message.menuId);
      if (menu) invoke(menu.callback, [new MouseEvent('click')]);
      return;
    }
    const request = pending.get(message.id);
    if (!request) return;
    if (message.event === 'xhrChunk') {
      const part = message.result;
      request.chunks ||= [];
      request.chunkSize ||= 0;
      if (!part || part.index !== request.chunks.length || !['text', 'base64'].includes(part.kind) ||
          request.chunkKind && part.kind !== request.chunkKind) throw new Error('GM 响应分片顺序无效');
      request.chunkKind = part.kind;
      const chunk = part.kind === 'base64' ? Uint8Array.from(atob(part.text), c => c.charCodeAt(0)) : part.text;
      request.chunkSize += chunk.length;
      if (request.chunkSize > MAX_BYTES) throw new Error('GM 请求响应不能超过 10 MiB');
      request.chunks.push(chunk);
      return;
    }
    if (message.event === 'progress' || message.event === 'loadstart' || message.event === 'readystatechange') {
      request.onEvent?.(message);
      return;
    }
    clearTimeout(request.timer);
    pending.delete(message.id);
    if (message.error) { request.chunks = null; request.reject(Object.assign(new Error(message.error), { event: message.event || 'error' })); }
    else {
      const result = message.result;
      if (result?.chunked) {
        if (request.chunkKind === 'base64') {
          const bytes = new Uint8Array(request.chunkSize || 0);
          let offset = 0;
          for (const chunk of request.chunks || []) { bytes.set(chunk, offset); offset += chunk.length; }
          result.bytes = bytes;
        } else result.responseText = (request.chunks || []).join('');
        request.chunks = null;
      }
      request.resolve(result);
    }
  };
  bridge.onmessage = event => {
    let message;
    try { message = parse(event.data); } catch (_) { return; }
    if (message.doc !== doc) return;
    if (message.event !== 'bridgeChunk') { receive(message); return; }
    const id = message.transfer;
    let transfer = incoming.get(id);
    try {
      if (!transfer) {
        if (message.index !== 0 || incoming.size >= 16 || message.total > MAX_BYTES * 6 + 65536) throw new Error('GM 分片传输无效');
        const timer = setTimeout(() => incoming.delete(id), 15000);
        transfer = { chunks: [], size: 0, total: message.total, timer }; incoming.set(id, transfer);
      }
      if (message.index !== transfer.chunks.length || typeof message.text !== 'string' || message.text.length > CHUNK_CHARS ||
          transfer.total !== message.total) throw new Error('GM 分片顺序无效');
      clearTimeout(transfer.timer);
      transfer.timer = setTimeout(() => incoming.delete(id), 15000);
      transfer.chunks.push(message.text); transfer.size += message.text.length;
      if (transfer.size > transfer.total) throw new Error('GM 分片长度无效');
      if (message.last) {
        incoming.delete(id); clearTimeout(transfer.timer);
        if (transfer.size !== transfer.total) throw new Error('GM 分片不完整');
        receive(parse(transfer.chunks.join('')));
      }
      post({ op: 'chunkAck', transfer: id, index: message.index });
    } catch (error) {
      if (transfer) clearTimeout(transfer.timer);
      incoming.delete(id); report('error', errorText(error));
    }
  };
  const sendDetached = (op, data) => rpc(op, data).promise.catch(error => report('error', errorText(error)));
  const getValue = (key, fallback) => Object.hasOwn ? (Object.hasOwn(values, key) ? decode(values[key]) : fallback) :
    (Object.prototype.hasOwnProperty.call(values, key) ? decode(values[key]) : fallback);
  let storageQueue = Promise.resolve();
  const writeValue = (op, key, previous, data = {}) => {
    let state = valueWrites.get(key);
    if (!state) { state = { confirmed: previous, writes: [] }; valueWrites.set(key, state); }
    const write = { value: op === 'setValue' ? data.value : undefined };
    state.writes.push(write);
    const text = op === 'setValue' ? stringify(data.value) : '';
    const send = () => {
      if (text.length <= CHUNK_CHARS) return rpc(op, { key, ...data }).promise;
      if (text.length > MAX_BYTES) return Promise.reject(new Error('每个脚本的 GM 存储不能超过 10 MiB'));
      return (async () => {
        const transfer = await rpc('valueBegin', { key, characters: text.length }).promise;
        let index = 0;
        for (let start = 0; start < text.length;) {
          let end = Math.min(start + CHUNK_CHARS, text.length);
          if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1])) end--;
          await rpc('valueChunk', { transfer, index: index++, text: text.slice(start, end), last: end === text.length }).promise;
          start = end;
        }
      })();
    };
    const settle = success => {
      if (success) state.confirmed = write.value;
      state.writes.shift();
      if (state.writes.length) return;
      valueWrites.delete(key);
      const old = values[key];
      if (state.confirmed === undefined) delete values[key]; else values[key] = state.confirmed;
      if (!sameValue(old, state.confirmed)) notifyValue(key, old, state.confirmed, false);
    };
    // Native storage is serial too. Settle each write before sending the next;
    // failed older writes must never replace a newer optimistic value.
    const request = storageQueue.then(send).then(result => { settle(true); return result; }, error => { settle(false); throw error; });
    storageQueue = request.catch(() => {});
    return request;
  };
  const setValue = (key, value, wait) => {
    key = String(key);
    const old = values[key];
    const next = encode(value);
    values[key] = next;
    const request = writeValue('setValue', key, old, { value: next });
    notifyValue(key, old, next, false);
    if (wait) return request;
    request.catch(error => report('error', errorText(error)));
  };
  const deleteValue = (key, wait) => {
    key = String(key);
    const old = values[key]; delete values[key];
    const request = writeValue('deleteValue', key, old);
    notifyValue(key, old, undefined, false);
    if (wait) return request;
    request.catch(error => report('error', errorText(error)));
  };
  const append = (node, parent) => {
    if (parent || document.head || document.documentElement) (parent || document.head || document.documentElement).appendChild(node);
    else {
      const observer = new MutationObserver(() => {
        if (document.head || document.documentElement) { (document.head || document.documentElement).appendChild(node); observer.disconnect(); }
      });
      observer.observe(document, { childList: true, subtree: true });
    }
    return node;
  };
  const addStyle = css => { const style = document.createElement('style'); style.textContent = String(css); return append(style); };
  const addElement = (...args) => {
    const parent = typeof args[0] === 'string' ? null : args.shift();
    const [tag, attributes = {}] = args;
    const node = document.createElement(tag);
    for (const [name, value] of Object.entries(attributes)) {
      if (name === 'textContent') node.textContent = value; else node.setAttribute(name, value);
    }
    return append(node, parent);
  };
  const resource = name => {
    if (!Object.prototype.hasOwnProperty.call(spec.resources, name)) throw new Error('未声明 @resource ' + name);
    const item = spec.resources[name];
    if (item.error && !unavailableResourceWarnings.has(name)) {
      unavailableResourceWarnings.add(name);
      report('warning', '@resource ' + name + ' 下载失败，使用空资源：' + item.error);
    }
    return item;
  };
  const registerMenu = (title, callback, options) => {
    const menuId = String(typeof options === 'object' && options?.id != null ? options.id : ++sequence);
    const menuTitle = String(title);
    menus.set(menuId, { callback, title: menuTitle });
    sendDetached('menuRegister', { menuId, title: menuTitle });
    return menuId;
  };
  const unregisterMenu = id => { menus.delete(String(id)); sendDetached('menuRemove', { menuId: String(id) }); };
  const convertResponse = (result, type) => {
    const response = { ...result, readyState: 4, responseText: result.responseText || '', responseXML: null };
    if (type === 'json') { try { response.response = JSON.parse(response.responseText); } catch (_) { response.response = null; } }
    else if (type === 'arraybuffer' || type === 'blob') {
      const bytes = result.bytes || Uint8Array.from(atob(result.base64 || ''), char => char.charCodeAt(0));
      delete response.bytes;
      delete response.base64;
      response.response = type === 'blob' ? new Blob([bytes], { type: result.mime }) : bytes.buffer;
    } else if (type === 'document') {
      response.response = new DOMParser().parseFromString(response.responseText, 'text/html');
      response.responseXML = response.response;
    } else response.response = response.responseText;
    return response;
  };
  const xhr = (details, modern) => {
    const type = details.responseType || 'text';
    if (!['text', 'json', 'arraybuffer', 'blob', 'document'].includes(type) || details.synchronous || details.cookie || details.cookiePartition || details.user || details.password) {
      throw new Error('该 GM 请求选项暂不支持，请查看脚本日志和兼容表');
    }
    let data = details.data;
    if (data instanceof ArrayBuffer || ArrayBuffer.isView(data)) {
      const bytes = data instanceof ArrayBuffer ? new Uint8Array(data) : new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
      let text = ''; for (let i = 0; i < bytes.length; i += 8192) text += String.fromCharCode(...bytes.subarray(i, i + 8192));
      data = { base64: btoa(text) };
    } else if (data instanceof URLSearchParams) data = data.toString();
    else if (data != null && typeof data !== 'string') throw new TypeError('请求正文仅支持字符串、URLSearchParams 或字节数组');
    const rawTimeout = Number(details.timeout ?? 30000);
    const timeout = Number.isFinite(rawTimeout) && rawTimeout > 0 ? Math.max(1, Math.min(120000, Math.trunc(rawTimeout))) : 120000;
    const request = rpc('xhr', { target: new URL(details.url, location.href).href, method: details.method || 'GET',
      headers: details.headers || {}, data, timeout, anonymous: !!details.anonymous,
      responseType: type }, event => {
      const callback = details['on' + event.event];
      if (typeof callback === 'function') invoke(callback, [{ ...event.result, context: details.context }]);
      if (event.event !== 'readystatechange' && details.onreadystatechange && event.result?.readyState) {
        invoke(details.onreadystatechange, [{ ...event.result, context: details.context }]);
      }
    });
    let abortSent = false;
    const abort = () => {
      const pendingRequest = pending.get(request.id);
      if (abortSent || !pendingRequest) return;
      pendingRequest.chunks = null;
      abortSent = true; clearTimeout(pendingRequest.timer); pending.delete(request.id);
      try { post({ op: 'abort', requestId: request.id }); } catch (_) {}
      pendingRequest.reject(Object.assign(new Error('请求已取消'), { event: 'abort' }));
    };
    const promise = request.promise.then(result => {
      const response = convertResponse(result, type); response.context = details.context;
      if (details.onreadystatechange) invoke(details.onreadystatechange, [response]);
      if (details.onload) invoke(details.onload, [response]);
      return response;
    }).catch(error => {
      const callback = details['on' + (error.event || 'error')];
      if (callback) invoke(callback, [{ status: 0, readyState: 4, error: error.message, context: details.context }]);
      throw error;
    });
    promise.abort = abort;
    if (modern) return promise;
    promise.catch(() => {});
    return { abort };
  };
  const definitions = {
    getValue, setValue: (key, value) => setValue(key, value, false), deleteValue: key => deleteValue(key, false),
    listValues: () => Object.keys(values),
    getValues: keys => {
      const result = Object.create(null);
      if (keys == null) keys = Object.keys(values);
      if (Array.isArray(keys)) for (const key of keys) result[key] = getValue(key);
      else for (const [key, fallback] of Object.entries(keys)) result[key] = getValue(key, fallback);
      return result;
    },
    setValues: entries => { for (const [key, value] of Object.entries(entries)) setValue(key, value, false); },
    deleteValues: keys => { for (const key of keys) deleteValue(key, false); },
    addValueChangeListener: (key, callback) => { const id = String(++sequence); valueListeners.set(id, { key, callback }); return id; },
    removeValueChangeListener: id => valueListeners.delete(String(id)),
    addStyle, addElement,
    getResourceText: name => resource(name).text,
    getResourceURL: name => { const item = resource(name); return 'data:' + item.mime + ';base64,' + item.base64; },
    getResourceUrl: name => { const item = resource(name); return 'data:' + item.mime + ';base64,' + item.base64; },
    registerMenuCommand: registerMenu, unregisterMenuCommand: unregisterMenu,
    xmlhttpRequest: details => xhr(details, false),
    setClipboard: (text, info, callback) => {
      if (info && info !== 'text' && info !== 'text/plain' && info.type !== 'text') throw new Error('仅支持文本剪贴板');
      const promise = rpc('clipboard', { text: String(text) }).promise;
      if (callback) promise.then(() => invoke(callback, [])).catch(() => {});
      promise.catch(error => report('error', errorText(error)));
    },
    log: (...args) => report('info', args.map(String).join(' ')),
    notification: (details, title, image, onclick) => {
      if (typeof details === 'string') details = { text: details, title, onclick };
      if (!details || typeof details !== 'object') throw new TypeError('notification 参数无效');
      if (details.onclick || details.ondone || details.image || details.url || details.highlight) {
        throw new Error('GM_notification 仅支持应用内文字提示；点击、图片和系统通知暂不支持');
      }
      sendDetached('notification', { text: [details.title, details.text].filter(Boolean).join('\n') });
    },
    openInTab: () => { throw new Error('独立标签页暂不支持；请使用页面内导航'); }
  };
  const GM_info = { scriptHandler: 'Lightboat', version: '1.1.0', sandboxMode: 'raw', isIncognito: false,
    scriptMetaStr: meta.header, script: { name: meta.name, namespace: meta.namespace?.[0] || '', version: meta.version?.[0] || '',
      description: meta.description?.[0] || '', matches: meta.match || [], includes: meta.include || [], excludes: meta.exclude || [],
      runAt: meta.runAt, resources: Object.keys(spec.resources).map(name => ({ name })), grants: [...grants] } };
  const scope = { GM_info, unsafeWindow: permitted('unsafeWindow') ? window : undefined };
  const GM = { info: GM_info };
  for (const [name, fn] of Object.entries(definitions)) {
    if (permitted('GM_' + name)) scope['GM_' + name] = fn;
    const modern = name === 'xmlhttpRequest' ? 'xmlHttpRequest' : name;
    if (permitted('GM.' + modern)) {
      if (modern === 'xmlHttpRequest') GM[modern] = details => xhr(details, true);
      else if (modern === 'setValue') GM[modern] = (key, value) => setValue(key, value, true);
      else if (modern === 'deleteValue') GM[modern] = key => deleteValue(key, true);
      else if (modern === 'setValues') GM[modern] = entries => Promise.all(Object.entries(entries).map(([key, value]) => setValue(key, value, true))).then(() => undefined);
      else if (modern === 'deleteValues') GM[modern] = keys => Promise.all(keys.map(key => deleteValue(key, true))).then(() => undefined);
      else if (modern === 'setClipboard') GM[modern] = (text, info) => {
        if (info && info !== 'text' && info !== 'text/plain' && info.type !== 'text') return Promise.reject(new Error('仅支持文本剪贴板'));
        return rpc('clipboard', { text: String(text) }).promise;
      };
      else if (modern === 'notification') GM[modern] = (details, title) => {
        if (typeof details === 'string') details = { text: details, title };
        if (!details || typeof details !== 'object') return Promise.reject(new TypeError('notification 参数无效'));
        if (details.onclick || details.ondone || details.image || details.url || details.highlight) return Promise.reject(new Error('仅支持应用内文字提示'));
        return rpc('notification', { text: [details.title, details.text].filter(Boolean).join('\n') }).promise;
      };
      else GM[modern] = (...args) => Promise.resolve().then(() => fn(...args));
    }
  }
  scope.GM = grants.has('none') ? undefined : GM;
  const unknown = [...grants].filter(grant => grant !== 'none' && grant !== 'unsafeWindow' && grant !== 'GM_info' &&
    grant !== 'GM.info' && !(grant.startsWith('GM.') ? GM[grant.slice(3)] : scope[grant]));
  if (unknown.length) report('warning', '未实现的授权接口：' + unknown.join(', '));
  if (permitted('GM_openInTab') || permitted('GM.openInTab')) report('warning', '独立标签页暂不支持；GM_openInTab 调用将报错');
  if (meta['sandbox'] || meta['run-in'] || meta['webRequest']) report('warning', '@sandbox/@run-in/@webRequest 扩展隔离指令暂不支持');
  const args = spec.parameters.map(name => scope[name]);
  const executionContext = (() => {
    let page = location.hostname + location.pathname;
    if (page.length > 160) page = page.slice(0, 157) + '...';
    return (window === window.top ? '主页面' : 'iframe') + '；' + page;
  })();
  const run = () => {
    scheduled = false;
    if (suspended || executed) return;
    executed = true;
    try { apply(entry, window, args); report('info', meta.name + ' 已执行（' + meta.runAt + '；' + executionContext + '）'); }
    catch (error) { executionFailed(error); }
  };
  const schedule = () => {
    if (suspended || scheduled || executed) return;
    scheduled = true;
    if (meta.runAt === 'document-start') run();
    else if (meta.runAt === 'document-body') {
      if (document.body) run();
      else { const observer = new MutationObserver(() => { if (document.body) { observer.disconnect(); run(); } }); observer.observe(document, { childList: true, subtree: true }); }
    } else {
      const ready = () => meta.runAt === 'document-idle' ? setTimeout(run, 0) : run();
      if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ready, { once: true }); else ready();
    }
  };
  window.addEventListener('error', event => {
    if (String(event.filename || '').includes('lightboat-userscript')) report('error', (event.message || '脚本错误') + ':' + event.lineno);
  });
  window.addEventListener('unhandledrejection', event => {
    if (String(event.reason?.stack || '').includes('lightboat-userscript')) report('error', errorText(event.reason));
  });
  document.addEventListener('securitypolicyviolation', event => {
    if (['eval', 'wasm-eval'].includes(event.blockedURI)) {
      report('error', '网页 CSP 阻止动态代码执行（' + event.effectiveDirective +
        '）；混淆脚本已注入，但其中的 eval/new Function/WebAssembly 可能无法运行');
    }
  });
  window.addEventListener('pagehide', () => {
    suspended = true;
    for (const transfer of incoming.values()) clearTimeout(transfer.timer);
    incoming.clear();
    for (const request of pending.values()) { clearTimeout(request.timer); request.chunks = null; request.reject(new Error('脚本文档已关闭')); }
    pending.clear();
    try { post({ op: 'dispose' }); } catch (_) {}
  });
  window.addEventListener('pageshow', event => {
    if (!event.persisted || !suspended || reconnecting) return;
    reconnecting = true;
    // A restored document retains its JS hooks and callbacks. Reconnect its native
    // session and menu registrations without executing the userscript a second time.
    restoredValues = Object.create(null);
    rpc('hello').promise.then(snapshot => {
      Object.assign(restoredValues, snapshot);
      const next = restoredValues;
      restoredValues = null;
      const changes = [];
      for (const key of new Set([...Object.keys(values), ...Object.keys(next)])) {
        if (valueWrites.has(key)) continue;
        const previous = values[key], value = next[key];
        const existed = Object.prototype.hasOwnProperty.call(values, key);
        const exists = Object.prototype.hasOwnProperty.call(next, key);
        if (existed === exists && sameValue(previous, value)) continue;
        if (exists) values[key] = value; else delete values[key];
        changes.push([key, previous, value]);
      }
      // All keys are current before a callback can read the restored snapshot.
      for (const [key, previous, value] of changes) notifyValue(key, previous, value, true);
      for (const [menuId, menu] of menus) sendDetached('menuRegister', { menuId, title: menu.title });
      suspended = false;
      schedule();
    }).catch(error => report('error', errorText(error))).finally(() => { restoredValues = null; reconnecting = false; });
  });
  const hello = rpc('hello').promise;
  report('info', meta.name + ' 已匹配（' + executionContext + '）；等待 ' + meta.runAt);
  if ([...grants].some(grant => /(?:Value|Values)/.test(grant))) {
    hello.then(snapshot => { Object.assign(values, snapshot); schedule(); }).catch(error => { if (!suspended) executionFailed(error); });
  } else { hello.catch(error => report('error', errorText(error))); schedule(); }
}
