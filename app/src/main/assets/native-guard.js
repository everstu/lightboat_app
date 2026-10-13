(() => {
  'use strict';
  const stop = event => {
    event.preventDefault();
    event.stopImmediatePropagation();
  };
  for (const type of ['contextmenu', 'selectstart', 'dragstart', 'copy', 'cut', 'paste']) {
    window.addEventListener(type, stop, { capture: true, passive: false });
  }
  // Some keyboards insert clipboard content via beforeinput instead of paste.
  window.addEventListener('beforeinput', event => {
    if (['insertFromPaste', 'insertFromPasteAsQuotation', 'insertFromDrop'].includes(event.inputType)) {
      stop(event);
    }
  }, { capture: true, passive: false });
  window.addEventListener('keydown', event => {
    const key = event.key.toLowerCase();
    if (((event.ctrlKey || event.metaKey) && ['a', 'c', 'v', 'x', 'insert'].includes(key)) ||
        (event.shiftKey && ['insert', 'delete'].includes(key))) stop(event);
  }, { capture: true, passive: false });
  const style = document.createElement('style');
  style.textContent = `
    * { -webkit-touch-callout: none !important; }
    html, body, body * { -webkit-user-select: none !important; user-select: none !important; }
    input, textarea, [contenteditable], [contenteditable] * {
      -webkit-user-select: text !important; user-select: text !important;
    }
    a, button, input, select, textarea, [role="button"] { touch-action: manipulation; }
    img, a { -webkit-user-drag: none !important; }
  `;
  const install = () => {
    if (!document.documentElement) return false;
    document.documentElement.appendChild(style);
    return true;
  };
  // At document start even <html> may not exist yet. Observe only root creation.
  if (!install()) {
    const observer = new MutationObserver(() => {
      if (install()) observer.disconnect();
    });
    observer.observe(document, { childList: true });
  }
})();
