// Tiny i18n layer: JSON dictionaries, {placeholders}, DOM binding via data-i18n* attributes.
import { store, emit } from './state.js';

let dict = {};
let fallback = {};
let current = 'en';

export const lang = () => current;

export function detectLang() {
  const saved = store.get('fs.lang', '');
  if (saved === 'en' || saved === 'es') return saved;
  return (navigator.language || 'en').toLowerCase().startsWith('es') ? 'es' : 'en';
}

async function load(l) {
  const r = await fetch('/i18n/' + l + '.json');
  if (!r.ok) throw new Error('i18n ' + l);
  return r.json();
}

export function t(key, vars) {
  let s = dict[key] ?? fallback[key];
  if (s === undefined) return key;
  if (vars) s = s.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? vars[k] : m));
  return s;
}

// Simple plural helper: keys base.one / base.other.
export function tn(base, n, vars = {}) {
  return t(base + (n === 1 ? '.one' : '.other'), { n, ...vars });
}

export function applyDom(root) {
  root.querySelectorAll('[data-i18n]').forEach((el) => { el.textContent = t(el.dataset.i18n); });
  root.querySelectorAll('[data-i18n-title]').forEach((el) => { el.title = t(el.dataset.i18nTitle); });
  root.querySelectorAll('[data-i18n-aria]').forEach((el) => { el.setAttribute('aria-label', t(el.dataset.i18nAria)); });
  root.querySelectorAll('[data-i18n-ph]').forEach((el) => { el.placeholder = t(el.dataset.i18nPh); });
}

export async function setLang(l, persist = true) {
  try { dict = l === 'en' ? fallback : await load(l); } catch { dict = fallback; }
  current = l;
  document.documentElement.lang = l;
  if (persist) store.set('fs.lang', l);
  applyDom(document);
  document.title = t('app.title');
  emit('lang', l);
}

export async function initI18n() {
  fallback = await load('en');
  await setLang(detectLang(), false);
}
