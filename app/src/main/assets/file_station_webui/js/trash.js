// Trash view: per-volume list, restore, delete forever, empty.
import { state, fmtSize, fmtDate, on } from './state.js';
import { get, post, errorMessage } from './api.js';
import { h, toast, confirmDialog } from './dialogs.js';
import { t, tn } from './i18n.js';

let curVol = null;
let items = [];
const chosen = new Set();

const root = () => document.getElementById('view-trash');

export async function showTrash() {
  const box = root();
  const vols = state.volumes.filter((v) => !v.readOnly);
  if (!vols.length) { box.replaceChildren(h('div', { class: 'empty' }, h('p', { text: t('trash.noVolumes') }))); return; }
  if (!curVol || !vols.some((v) => v.id === curVol)) curVol = vols[0].id;
  chosen.clear();
  box.replaceChildren(h('p', { class: 'muted pad', text: t('common.loading') }));
  try {
    items = (await get('/api/trash', { vol: curVol })).items || [];
  } catch (e) {
    box.replaceChildren(h('div', { class: 'empty' }, h('p', { text: errorMessage(e) }), h('button', { class: 'btn', type: 'button', text: t('common.retry'), onclick: showTrash })));
    return;
  }
  render(vols);
}

async function run(path, body, okMsg) {
  try { await post(path, { vol: curVol, ...body }); toast(okMsg, 'success'); } catch (e) { toast(errorMessage(e), 'error'); }
  showTrash();
}

function render(vols) {
  const box = root();
  const ids = () => [...chosen];
  const sel = h('select', { class: 'input vol-select', 'aria-label': t('trash.volume'), onchange: (e) => { curVol = e.target.value; showTrash(); } },
    vols.map((v) => h('option', { value: v.id, text: v.label, selected: v.id === curVol })));
  const has = chosen.size > 0;
  const bar = h('div', { class: 'view-bar' }, h('h2', { text: t('trash.title') }), sel,
    h('span', { class: 'spacer' }),
    h('button', { class: 'btn', type: 'button', disabled: !has, text: t('trash.restore'), onclick: () => run('/api/trash/restore', { ids: ids(), conflict: 'rename' }, t('toast.restored')) }),
    h('button', { class: 'btn danger', type: 'button', disabled: !has, text: t('trash.deleteForever'), onclick: async () => {
      if (await confirmDialog(tn('trash.confirmDelete', chosen.size), { danger: true, okLabel: t('trash.deleteForever') })) run('/api/trash/empty', { ids: ids() }, t('toast.deleted'));
    } }),
    h('button', { class: 'btn danger', type: 'button', disabled: !items.length, text: t('trash.empty'), onclick: async () => {
      if (await confirmDialog(t('trash.confirmEmpty'), { danger: true, okLabel: t('trash.empty') })) run('/api/trash/empty', {}, t('toast.trashEmptied'));
    } }));
  if (!items.length) { box.replaceChildren(bar, h('div', { class: 'empty' }, h('div', { class: 'empty-icon', 'aria-hidden': 'true', text: '\u{1F5D1}' }), h('p', { text: t('trash.none') }))); return; }
  const all = h('input', { type: 'checkbox', class: 'sel-box', 'aria-label': t('sel.all'), onclick: (e) => { chosen.clear(); if (e.target.checked) items.forEach((i) => chosen.add(i.id)); render(vols); } });
  all.checked = chosen.size === items.length;
  const rows = items.map((it) => h('div', { class: 'row' + (chosen.has(it.id) ? ' selected' : ''), role: 'row' },
    h('input', { type: 'checkbox', class: 'sel-box', checked: chosen.has(it.id), 'aria-label': t('sel.select', { name: it.name }), onclick: (e) => { if (e.target.checked) chosen.add(it.id); else chosen.delete(it.id); render(vols); } }),
    h('span', { class: 'file-icon', 'aria-hidden': 'true', text: it.isDir ? '\u{1F4C1}' : '\u{1F4C4}' }),
    h('div', { class: 'cell-name' }, h('span', { class: 'name-link', title: it.name, text: it.name }), h('span', { class: 'row-path muted small', text: it.originalPath || '' })),
    h('div', { class: 'cell-size', text: it.isDir ? '' : fmtSize(it.size) }),
    h('div', { class: 'cell-date', text: fmtDate(it.deletedAt) })));
  box.replaceChildren(bar, h('div', { class: 'file-table' },
    h('div', { class: 'row head' }, all, h('span', { class: 'file-icon' }), h('div', { class: 'cell-name', text: t('col.name') }),
      h('div', { class: 'cell-size', text: t('col.size') }), h('div', { class: 'cell-date', text: t('trash.deletedAt') })), rows));
}

on('lang', () => { if (state.mode === 'trash') showTrash(); });
