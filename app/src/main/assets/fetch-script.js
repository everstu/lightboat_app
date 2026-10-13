(async () => {
  const send = message => LightboatLoader.postMessage(JSON.stringify(message));
  try {
    // Chromium owns storage, freshness, ETag validation and eviction.
    const response = await fetch(scriptUrl, { cache: 'default', credentials: 'same-origin' });
    if (!response.ok) throw new Error(`脚本服务器返回 HTTP ${response.status}`);
    const contentType = response.headers.get('Content-Type') || '';
    const mime = contentType.split(';')[0].trim().toLowerCase();
    const accepted = ['application/javascript', 'text/javascript', 'application/ecmascript',
      'text/ecmascript', 'application/x-javascript', 'text/plain', 'application/octet-stream'];
    if (mime && !accepted.includes(mime)) throw new Error(`该地址未返回 JavaScript 文件（${mime}）`);
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
    if (!script.trim()) throw new Error('脚本内容为空');
    if (/^\s*<(?:!doctype|html|head|body)\b/i.test(script)) {
      throw new Error('该地址返回了 HTML 网页，请填写 JS 文件的直接下载地址');
    }
    send({ script });
  } catch (error) {
    send({ error: error.message || '无法获取脚本，请检查网络、地址或跨域重定向的 CORS 设置。' });
  }
})();
