function LightboatMatches(meta, address, mainFrame) {
  if (meta.noFrames && !mainFrame) return false;
  let url;
  try { url = new URL(address); } catch (_) { return false; }
  if (!['http:', 'https:'].includes(url.protocol)) return false;
  const escaped = text => text.replace(/[.+?^${}()|[\]\\]/g, '\\$&');
  const glob = (pattern, text) => new RegExp('^' + escaped(pattern).replace(/\*/g, '.*') + '$').test(text);
  const include = pattern => {
    if (pattern.startsWith('/') && pattern.endsWith('/')) {
      try { return new RegExp(pattern.slice(1, -1)).test(url.href.split('#')[0]); } catch (_) { return false; }
    }
    return glob(pattern, url.href.split('#')[0]);
  };
  const match = pattern => {
    const parts = /^(\*|http\*|https?):\/\/([^/]+)(\/.*)$/.exec(pattern);
    if (!parts) return false;
    const [, scheme, host, path] = parts;
    if (scheme !== '*' && scheme !== 'http*' && scheme + ':' !== url.protocol) return false;
    const authority = /^(\*\.)?(\[[^\]]+\]|[^:]+)(?::([0-9]+))?$/.exec(host);
    if (!authority) return false;
    if (authority[3] && Number(authority[3]) !== Number(url.port || (url.protocol === 'https:' ? 443 : 80))) return false;
    let ruleHost;
    try { ruleHost = authority[2] === '*' ? '*' : new URL('http://' + authority[2] + '/').hostname.toLowerCase(); }
    catch (_) { return false; }
    const hostname = url.hostname.toLowerCase();
    if (ruleHost !== '*' && ruleHost !== hostname && !(authority[1] && hostname.endsWith('.' + ruleHost))) return false;
    return glob(path, url.pathname + url.search);
  };
  const matches = meta.match || [];
  const includes = meta.include || [];
  const accepted = (!matches.length && !includes.length) || matches.some(match) || includes.some(include);
  return accepted && !(meta.exclude || []).some(include) && !(meta['exclude-match'] || []).some(match);
}
