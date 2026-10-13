function LightboatRuntime(spec, entry) {
  'use strict';
  const meta = spec.meta;
  if (!LightboatMatches(meta, location.href, window === window.top)) return;
  const bridge = window.LightboatGM;
  const doc = (globalThis.crypto?.randomUUID?.() || Date.now() + '-' + Math.random()).toString();
  const pending = new Map();
  const values = Object.assign(Object.create(null), spec.values);
  const valueListeners = new Map();
  const menus = new Map();
  let sequence = 0;
  const grants = new Set(meta.grant || []);
  const permitted = name => !grants.has('none') && grants.has(name);
  const errorText = error => error?.stack || error?.message || String(error);
  const post = data => bridge.postMessage(JSON.stringify({ token: spec.token, doc, url: location.href, ...data }));
  const report = (level, text) => {
    try { post({ op: 'log', level, text: String(text).slice(0, 8000) }); }
    catch (_) { console.error('[Lightboat]', text); }
  };
  const invoke = (fn, args) => {
    try { return fn(...args); } catch (error) { report('error', errorText(error)); }
  };
  const rpc = (op, data = {}, onEvent) => {
    const id = String(++sequence);
    const promise = new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error(op + ' 请求超时'));
      }, op === 'xhr' ? Math.max(120000, (data.timeout || 30000) + 10000) : 15000);
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
  bridge.onmessage = event => {
    let message;
    try { message = JSON.parse(event.data); } catch (_) { return; }
    if (message.doc !== doc) return;
    if (message.event === 'value') {
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
    if (message.event === 'progress' || message.event === 'loadstart' || message.event === 'readystatechange') {
      request.onEvent?.(message);
      return;
    }
    clearTimeout(request.timer);
    pending.delete(message.id);
    if (message.error) request.reject(Object.assign(new Error(message.error), { event: message.event || 'error' }));
    else request.resolve(message.result);
  };
  const sendDetached = (op, data) => rpc(op, data).promise.catch(error => report('error', errorText(error)));
  const getValue = (key, fallback) => Object.hasOwn ? (Object.hasOwn(values, key) ? decode(values[key]) : fallback) :
    (Object.prototype.hasOwnProperty.call(values, key) ? decode(values[key]) : fallback);
  const setValue = (key, value, wait) => {
    key = String(key);
    const old = values[key];
    const next = encode(value);
    values[key] = next;
    notifyValue(key, old, next, false);
    const request = rpc('setValue', { key, value: next }).promise;
    if (wait) return request;
    request.catch(error => report('error', errorText(error)));
  };
  const deleteValue = (key, wait) => {
    key = String(key);
    const old = values[key]; delete values[key]; notifyValue(key, old, undefined, false);
    const request = rpc('deleteValue', { key }).promise;
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
    return spec.resources[name];
  };
  const registerMenu = (title, callback, options) => {
    const menuId = String(typeof options === 'object' && options?.id != null ? options.id : ++sequence);
    menus.set(menuId, { callback });
    sendDetached('menuRegister', { menuId, title: String(title) });
    return menuId;
  };
  const unregisterMenu = id => { menus.delete(String(id)); sendDetached('menuRemove', { menuId: String(id) }); };
  const convertResponse = (result, type) => {
    const response = { ...result, readyState: 4, responseText: result.responseText || '', responseXML: null };
    if (type === 'json') { try { response.response = JSON.parse(response.responseText); } catch (_) { response.response = null; } }
    else if (type === 'arraybuffer' || type === 'blob') {
      const bytes = Uint8Array.from(atob(result.base64), char => char.charCodeAt(0));
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
    const request = rpc('xhr', { target: new URL(details.url, location.href).href, method: details.method || 'GET',
      headers: details.headers || {}, data, timeout: details.timeout || 30000, anonymous: !!details.anonymous,
      responseType: type }, event => {
      const callback = details['on' + event.event];
      if (typeof callback === 'function') invoke(callback, [{ ...event.result, context: details.context }]);
      if (event.event !== 'readystatechange' && details.onreadystatechange && event.result?.readyState) {
        invoke(details.onreadystatechange, [{ ...event.result, context: details.context }]);
      }
    });
    let abortSent = false;
    const abort = () => { if (!abortSent) { abortSent = true; post({ op: 'abort', requestId: request.id }); } };
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
  const run = () => {
    try { entry.apply(window, args); report('info', meta.name + ' 已执行（' + meta.runAt + '）'); }
    catch (error) { report('error', errorText(error)); }
  };
  const schedule = () => {
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
  window.addEventListener('pagehide', () => { try { post({ op: 'dispose' }); } catch (_) {} });
  const hello = rpc('hello').promise;
  if ([...grants].some(grant => /(?:Value|Values)/.test(grant))) {
    hello.then(snapshot => { Object.assign(values, snapshot); schedule(); }).catch(error => report('error', errorText(error)));
  } else { hello.catch(error => report('error', errorText(error))); schedule(); }
}
