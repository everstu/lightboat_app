(async () => {
  const send = message => LightboatLoader.postMessage(JSON.stringify(message));
  try {
    const mode = typeof assetMode === 'undefined' ? 'script' : assetMode;
    // Force a fresh script for every configuration launch. Chromium still owns
    // cookies, connections and storage; no script body is persisted by us.
    // These are standalone script downloads, not resources referred by the
    // synthetic helper page. Do not invent a Referer for the target server.
    const response = await fetch(scriptUrl, {
      cache: 'no-store', credentials: 'same-origin', referrerPolicy: 'no-referrer'
    });
    if (!response.ok) {
      let reason = '';
      if (response.status === 418) {
        const reader = response.body?.getReader();
        if (reader) {
          let preview = '', size = 0;
          const decoder = new TextDecoder();
          try {
            while (size < 4096) {
              const { value, done } = await reader.read();
              if (done) break;
              const chunk = value.subarray(0, 4096 - size);
              size += chunk.byteLength;
              preview += decoder.decode(chunk, { stream: true });
            }
            if (/CloudWAF|疑似攻击行为|访问被拦截/i.test(preview)) reason = '（CloudWAF 拦截）';
          } catch (_) {
            // Preserve the HTTP status even if its diagnostic body is unreadable.
          } finally { await reader.cancel().catch(() => {}); }
        }
      }
      throw new Error(`脚本服务器返回 HTTP ${response.status}${reason}`);
    }
    const contentType = response.headers.get('Content-Type') || '';
    const mime = contentType.split(';')[0].trim().toLowerCase();
    const accepted = ['application/javascript', 'text/javascript', 'application/ecmascript',
      'text/ecmascript', 'application/x-javascript', 'text/plain', 'application/octet-stream'];
    if (mode !== 'resource' && mime && !accepted.includes(mime)) throw new Error(`该地址未返回 JavaScript 文件（${mime}）`);
    const limit = 1024 * 1024;
    if (Number(response.headers.get('Content-Length')) > limit) throw new Error('脚本不能超过 1 MiB');
    const reader = response.body.getReader();
    const chunks = [];
    let length = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > limit) {
        await reader.cancel();
        throw new Error('脚本不能超过 1 MiB');
      }
      chunks.push(value);
    }
    const bytes = new Uint8Array(length);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    const charset = /charset\s*=\s*["']?([^;\s"']+)/i.exec(contentType)?.[1] || 'utf-8';
    const script = new TextDecoder(charset).decode(bytes).replace(/^\uFEFF/, '');
    if (mode !== 'resource' && !script.trim()) throw new Error('脚本内容为空');
    if (mode !== 'resource' && /^\s*<(?:!doctype|html|head|body)\b/i.test(script)) {
      throw new Error('该地址返回了 HTML 网页，请填写 JS 文件的直接下载地址');
    }
    if (mode === 'script') send({ script });
    else {
      let binary = '';
      for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
      send({ text: script, base64: btoa(binary), mime: mime || 'application/octet-stream' });
    }
  } catch (error) {
    send({ error: error.message || '无法获取脚本，请检查网络、地址或跨域重定向的 CORS 设置。' });
  }
})();
