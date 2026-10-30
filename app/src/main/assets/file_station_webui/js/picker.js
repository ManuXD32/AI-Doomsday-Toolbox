// Folder picker across volumes (copy/move/extract/tool destinations).
import { get, errorMessage } from './api.js';
import { h, modal, toast } from './dialogs.js';
import { t } from './i18n.js';
import { state, joinPath, parentPath, baseName } from './state.js';

// Resolves {vol, dir} or null. writable=true hides read-only volumes.
export function pickFolder({ title, okLabel, writable = true, vol = null, path = '' } = {}) {
  let curVol = vol;
  let curPath = path;
  const crumb = h('div', { class: 'picker-crumb' });
  const list = h('div', { class: 'picker-list', role: 'listbox', 'aria-label': title || t('picker.title') });
  const m = modal({
    title: title || t('picker.title'),
    body: [crumb, list],
    buttons: [
      { label: t('common.cancel'), value: null },
      { label: okLabel || t('picker.choose'), value: 'ok', primary: true },
    ],
  });
  const okBtn = m.buttons[1];

  function row(label, icon, onClick, extra) {
    return h('button', { type: 'button', class: 'picker-row', role: 'option', onclick: onClick },
      h('span', { class: 'picker-icon', 'aria-hidden': 'true', text: icon }), h('span', { class: 'picker-name', text: label }), extra || null);
  }

  async function show() {
    okBtn.disabled = !curVol;
    list.replaceChildren();
    crumb.replaceChildren();
    if (!curVol) {
      crumb.append(h('span', { text: t('picker.volumes') }));
      const vols = state.volumes.filter((v) => !writable || !v.readOnly);
      if (!vols.length) list.append(h('p', { class: 'muted', text: t('picker.none') }));
      vols.forEach((v) => list.append(row(v.label, '\u{1F5C4}', () => { curVol = v.id; curPath = ''; show(); })));
      return;
    }
    const vlabel = (state.volumes.find((v) => v.id === curVol) || {}).label || curVol;
    crumb.append(h('button', { type: 'button', class: 'link', text: t('picker.volumes'), onclick: () => { curVol = null; curPath = ''; show(); } }),
      ' / ', h('span', { text: vlabel + (curPath ? '/' + curPath : '') }));
    if (curPath) list.append(row('..', '⬆', () => { curPath = parentPath(curPath); show(); }));
    try {
      const data = await get('/api/list', { vol: curVol, path: curPath });
      const dirs = (data.entries || []).filter((e) => e.isDir);
      if (!dirs.length) list.append(h('p', { class: 'muted', text: t('picker.nofolders') }));
      dirs.forEach((e) => list.append(row(e.name || baseName(e.path), '\u{1F4C1}', () => { curPath = joinPath(curPath, e.name || baseName(e.path)); show(); })));
    } catch (err) { toast(errorMessage(err), 'error'); }
  }
  show();
  return m.promise.then((r) => (r === 'ok' && curVol ? { vol: curVol, dir: curPath } : null));
}
