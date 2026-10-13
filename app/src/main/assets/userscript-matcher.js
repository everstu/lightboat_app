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
    const parts = /^(\*|http\*|https?|file):\/\/([^/]+)(\/.*)$/.exec(pattern);
    if (!parts) return false;
    const [, scheme, host, path] = parts;
    if (scheme !== '*' && scheme !== 'http*' && scheme + ':' !== url.protocol) return false;
    const authority = host.toLowerCase();
    const hostname = url.hostname.toLowerCase();
    if (authority !== '*' && !(authority.startsWith('*.') &&
        (hostname === authority.slice(2) || hostname.endsWith('.' + authority.slice(2)))) &&
        authority !== url.host.toLowerCase() && authority !== hostname) return false;
    return glob(path, url.pathname + url.search);
  };
  const matches = meta.match || [];
  const includes = meta.include || [];
  const accepted = (!matches.length && !includes.length) || matches.some(match) || includes.some(include);
  return accepted && !(meta.exclude || []).some(include) && !(meta['exclude-match'] || []).some(match);
}
