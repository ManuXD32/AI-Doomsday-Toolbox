// Plain text editor (textarea with line numbers) and the Toast UI Markdown viewer helper.
import { h } from './dialogs.js';

const loaded = new Map();

// Loads a classic vendor script once (CSP: script-src 'self', so only same-origin files).
export function loadScript(src) {
  if (loaded.has(src)) return loaded.get(src);
  const p = new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = src;
    s.onload = () => resolve();
    s.onerror = () => { loaded.delete(src); reject(new Error('script ' + src)); };
    document.head.append(s);
  });
  loaded.set(src, p);
  return p;
}

// Resolves once the stylesheet is applied: Toast UI measures its toolbar at construction time, so an
// unstyled toolbar would collapse most buttons into the "more" menu.
export function loadStyle(href) {
  const existing = document.querySelector('link[href="' + href + '"]');
  if (existing) return existing.sheet ? Promise.resolve() : new Promise((done) => {
    existing.addEventListener('load', done, { once: true });
    existing.addEventListener('error', done, { once: true });
  });
  return new Promise((done) => {
    const l = document.createElement('link');
    l.rel = 'stylesheet';
    l.href = href;
    l.addEventListener('load', done, { once: true });
    l.addEventListener('error', done, { once: true });
    document.head.append(l);
  });
}

export async function loadToastUi({ dark = false, es = false } = {}) {
  await Promise.all([loadScript('/vendor/dompurify/purify.min.js'), loadScript('/vendor/toastui-editor/toastui-editor-all.min.js')]);
  await Promise.all([
    loadStyle('/vendor/toastui-editor/toastui-editor.min.css'),
    dark ? loadStyle('/vendor/toastui-editor/toastui-editor-dark.min.css') : Promise.resolve(),
  ]);
  if (es) await loadScript('/vendor/toastui-editor/es-es.min.js');
}

export const sanitize = (html) => window.DOMPurify.sanitize(html);

// Read-only Markdown rendering (used by the shell preview and the read-only editor).
export async function renderMarkdown(container, text) {
  const dark = document.documentElement.dataset.theme === 'dark';
  await loadToastUi({ dark, es: document.documentElement.lang === 'es' });
  container.replaceChildren();
  const host = document.createElement('div');
  if (dark) host.classList.add('toastui-editor-dark');
  container.append(host);
  return window.toastui.Editor.factory({
    el: host, viewer: true, initialValue: text, customHTMLSanitizer: sanitize,
    language: document.documentElement.lang === 'es' ? 'es-ES' : 'en-US',
  });
}

// Simple code/text editor. Returns {getValue, setValue, setReadOnly, setWrap, focus}.
export function createPlainEditor(host, { text = '', readOnly = false, onChange, onSave }) {
  const gutter = h('pre', { class: 'pt-gutter', 'aria-hidden': 'true' });
  const area = h('textarea', {
    class: 'pt-area', spellcheck: 'false', autocapitalize: 'off', autocomplete: 'off', wrap: 'off',
    'aria-label': document.title,
  });
  const wrap = h('div', { class: 'pt-wrap' }, gutter, area);
  host.replaceChildren(wrap);
  let lines = 0;
  const number = () => {
    const n = area.value.split('\n').length;
    if (n === lines) return;
    lines = n;
    gutter.textContent = Array.from({ length: n }, (_, i) => i + 1).join('\n');
  };
  const sync = () => { gutter.scrollTop = area.scrollTop; };
  area.addEventListener('input', () => { number(); if (onChange) onChange(); });
  area.addEventListener('scroll', sync);
  area.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') { e.preventDefault(); if (onSave) onSave(); return; }
    if (e.key === 'Tab' && !e.shiftKey && !e.ctrlKey && !e.altKey && !e.metaKey && !area.readOnly) {
      e.preventDefault();
      const s = area.selectionStart;
      area.setRangeText('  ', s, area.selectionEnd, 'end');
      number();
      if (onChange) onChange();
    }
  });
  area.value = text;
  area.readOnly = readOnly;
  number();
  return {
    getValue: () => area.value,
    setValue: (v) => { area.value = v; lines = 0; number(); },
    setReadOnly: (r) => { area.readOnly = r; },
    setWrap: (on) => { wrap.classList.toggle('wrap', on); area.wrap = on ? 'soft' : 'off'; },
    focus: () => area.focus(),
  };
}
