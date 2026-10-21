// DOM helper, toasts, modal dialogs, popup menus. No inline style attributes (CSP): positions use CSSOM only.
import { t } from './i18n.js';

export function h(tag, props, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'text') el.textContent = v;
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (['value', 'checked', 'disabled', 'selected', 'hidden'].includes(k)) el[k] = v;
    else el.setAttribute(k, v === true ? '' : String(v));
  }
  const add = (kid) => {
    if (kid === null || kid === undefined || kid === false) return;
    if (Array.isArray(kid)) kid.forEach(add);
    else el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  };
  kids.forEach(add);
  return el;
}

export const modalOpen = () => document.querySelector('.modal-backdrop') !== null;

export function toast(msg, kind = 'info', ms = 4500) {
  const root = document.getElementById('toasts');
  if (!root) return;
  const el = h('div', { class: 'toast ' + kind, role: kind === 'error' ? 'alert' : 'status', text: msg });
  root.append(el);
  while (root.children.length > 5) root.firstChild.remove();
  setTimeout(() => el.remove(), ms);
}

// buttons: [{label, value, primary, danger, onClick}]. Resolves with the pressed button value, null on cancel.
export function modal({ title, body, buttons = [], wide = false }) {
  const prev = document.activeElement;
  const backdrop = h('div', { class: 'modal-backdrop' });
  const box = h('div', { class: 'modal' + (wide ? ' modal-wide' : ''), role: 'dialog', 'aria-modal': 'true', 'aria-label': title });
  const bodyEl = h('div', { class: 'modal-body' }, body);
  const foot = h('div', { class: 'modal-foot' });
  let closed = false;
  let done;
  const promise = new Promise((r) => { done = r; });
  const close = (v = null) => {
    if (closed) return;
    closed = true;
    backdrop.remove();
    document.removeEventListener('keydown', onKey, true);
    if (prev && prev.focus) prev.focus();
    done(v);
  };
  const btnEls = buttons.map((b) => {
    const el = h('button', {
      type: 'button',
      class: 'btn' + (b.primary ? ' primary' : '') + (b.danger ? ' danger' : ''),
      text: b.label,
      onclick: async () => {
        if (b.onClick) { const r = await b.onClick(); if (r === false) return; }
        close(b.value === undefined ? true : b.value);
      },
    });
    foot.append(el);
    return el;
  });
  function onKey(e) {
    if (backdrop !== document.querySelector('.modal-backdrop:last-of-type')) return;
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(null); }
    else if (e.key === 'Enter' && e.target && e.target.tagName === 'INPUT' && e.target.type !== 'checkbox') {
      const p = buttons.findIndex((b) => b.primary);
      if (p >= 0 && !btnEls[p].disabled) { e.preventDefault(); btnEls[p].click(); }
    }
  }
  document.addEventListener('keydown', onKey, true);
  backdrop.addEventListener('mousedown', (e) => { if (e.target === backdrop) close(null); });
  box.append(h('div', { class: 'modal-head' }, h('h2', { text: title }),
    h('button', { class: 'icon-btn', type: 'button', 'aria-label': t('common.close'), text: '✕', onclick: () => close(null) })), bodyEl);
  if (buttons.length) box.append(foot);
  backdrop.append(box);
  document.body.append(backdrop);
  const focus = box.querySelector('input,select,textarea') || btnEls.find((_, i) => buttons[i].primary) || box.querySelector('button');
  if (focus) { focus.focus(); if (focus.select && focus.tagName === 'INPUT') focus.select(); }
  return { promise, close, el: box, body: bodyEl, buttons: btnEls };
}

export async function confirmDialog(message, { title, okLabel, danger = false } = {}) {
  const m = modal({
    title: title || t('common.confirm'),
    body: h('p', { class: 'modal-text', text: message }),
    buttons: [
      { label: t('common.cancel'), value: false },
      { label: okLabel || t('common.ok'), value: true, primary: true, danger },
    ],
  });
  return (await m.promise) === true;
}

export async function promptDialog({ title, label, value = '', okLabel, selectStem = false }) {
  const input = h('input', { type: 'text', class: 'input', value, 'aria-label': label || title, autocomplete: 'off', spellcheck: 'false' });
  const m = modal({
    title,
    body: [label ? h('label', { class: 'field-label', text: label }) : null, input],
    buttons: [
      { label: t('common.cancel'), value: null },
      { label: okLabel || t('common.ok'), value: 'ok', primary: true, onClick: () => (input.value.trim() ? true : (input.focus(), false)) },
    ],
  });
  if (selectStem) { const i = value.lastIndexOf('.'); input.setSelectionRange(0, i > 0 ? i : value.length); }
  const r = await m.promise;
  return r === 'ok' ? input.value.trim() : null;
}

// fields: [{name,label,type:'text'|'number'|'select',options:[{value,label}],value,min,max,required,placeholder}]
export async function formDialog({ title, fields, okLabel, intro }) {
  const inputs = {};
  const rows = fields.map((f) => {
    let el;
    if (f.type === 'select') {
      el = h('select', { class: 'input' }, f.options.map((o) => h('option', { value: o.value, text: o.label, selected: o.value === f.value })));
    } else {
      el = h('input', { class: 'input', type: f.type || 'text', value: f.value ?? '', min: f.min, max: f.max, placeholder: f.placeholder, autocomplete: 'off' });
    }
    inputs[f.name] = el;
    return h('label', { class: 'field' }, h('span', { class: 'field-label', text: f.label }), el);
  });
  const m = modal({
    title,
    body: [intro ? h('p', { class: 'modal-text', text: intro }) : null, ...rows],
    buttons: [
      { label: t('common.cancel'), value: null },
      {
        label: okLabel || t('common.ok'), value: 'ok', primary: true,
        onClick: () => {
          for (const f of fields) if (f.required && !String(inputs[f.name].value).trim()) { inputs[f.name].focus(); return false; }
          return true;
        },
      },
    ],
  });
  if ((await m.promise) !== 'ok') return null;
  const out = {};
  for (const f of fields) out[f.name] = String(inputs[f.name].value).trim();
  return out;
}

let openMenu = null;
export function closeMenu() { if (openMenu) { openMenu.remove(); openMenu = null; } }

// items: [{label, onClick, disabled, icon, separator}]
export function menu(anchor, items) {
  closeMenu();
  const el = h('div', { class: 'menu', role: 'menu' });
  items.filter(Boolean).forEach((it) => {
    if (it.separator) { el.append(h('hr', { class: 'menu-sep' })); return; }
    el.append(h('button', {
      type: 'button', role: 'menuitem', class: 'menu-item', disabled: !!it.disabled,
      onclick: () => { closeMenu(); it.onClick && it.onClick(); },
    }, it.icon ? h('span', { class: 'menu-icon', 'aria-hidden': 'true', text: it.icon }) : null, h('span', { class: 'menu-label', text: it.label })));
  });
  document.body.append(el);
  const r = anchor.getBoundingClientRect();
  const w = el.offsetWidth;
  el.style.left = Math.max(4, Math.min(r.left, window.innerWidth - w - 4)) + 'px';
  const below = window.innerHeight - r.bottom;
  if (below < 200 && r.top > below) { el.style.bottom = (window.innerHeight - r.top + 4) + 'px'; el.style.maxHeight = (r.top - 12) + 'px'; }
  else { el.style.top = (r.bottom + 4) + 'px'; el.style.maxHeight = (below - 12) + 'px'; }
  openMenu = el;
  const first = el.querySelector('button:not(:disabled)');
  if (first) first.focus();
  return el;
}
document.addEventListener('mousedown', (e) => { if (openMenu && !openMenu.contains(e.target)) closeMenu(); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && openMenu) { e.stopPropagation(); closeMenu(); } }, true);
window.addEventListener('resize', closeMenu);
