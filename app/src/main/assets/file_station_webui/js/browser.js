// File view: breadcrumb, list/grid rendering, search, New menu, view toggle.
import { state, on, emit, store, itemKey, kindOf, fmtSize, fmtDate, joinPath, parentPath, filesHash, ICONS } from './state.js';
import { get, post, errorMessage, thumbUrl, editorUrl } from './api.js';
import { h, toast, menu, promptDialog } from './dialogs.js';
import { t } from './i18n.js';
import * as sel from './selection.js';
import { openPreview } from './preview.js';
import { pickFiles } from './upload.js';

const rowEls = new Map();
let loadSeq = 0;

const $ = (id) => document.getElementById(id);

export function navigate(vol, path) { window.location.hash = filesHash(vol, path); }

export function volumeLabel(id) { return (state.volumes.find((v) => v.id === id) || {}).label || id; }

function renderBreadcrumb() {
  const bc = $('breadcrumb');
  bc.replaceChildren();
  const part = (label, hash, current) => {
    const el = current ? h('span', { class: 'crumb current', 'aria-current': 'page', text: label })
      : h('a', { class: 'crumb', href: hash, text: label });
    bc.append(el);
  };
  const sep = () => bc.append(h('span', { class: 'crumb-sep', 'aria-hidden': 'true', text: '/' }));
  part(t('nav.home'), filesHash(), !state.vol && !state.query);
  if (state.query) {
    if (state.vol) { sep(); part(volumeLabel(state.vol), filesHash(state.vol, state.path), false); }
    sep(); part(t('search.results', { q: state.query }), '', true);
    return;
  }
  if (!state.vol) return;
  sep();
  const segs = state.path ? state.path.split('/') : [];
  part(volumeLabel(state.vol), filesHash(state.vol, ''), segs.length === 0);
  let acc = '';
  segs.forEach((s, i) => { acc = joinPath(acc, s); sep(); part(s, filesHash(state.vol, acc), i === segs.length - 1); });
}

function updateTopButtons() {
  const canWrite = state.mode === 'files' && !!state.vol && !state.readOnly && !state.query;
  $('new-btn').disabled = !canWrite;
  $('upload-btn').disabled = !canWrite;
  $('view-toggle').textContent = state.view === 'list' ? '▦' : '☰';
  $('view-toggle').title = t(state.view === 'list' ? 'view.grid' : 'view.list');
  $('view-toggle').setAttribute('aria-label', t(state.view === 'list' ? 'view.grid' : 'view.list'));
  $('search-input').placeholder = t(state.vol ? 'search.inVolume' : 'search.all');
}

export async function loadDir() {
  const seq = ++loadSeq;
  const box = $('files-content');
  renderBreadcrumb();
  updateTopButtons();
  if (!state.vol && !state.query) { showHome(box); return; }
  box.replaceChildren(h('p', { class: 'muted pad', text: t('common.loading') }));
  try {
    let items;
    if (state.query) {
      const data = await get('/api/search', { vol: state.vol || undefined, path: state.vol ? state.path : undefined, q: state.query, limit: 500 });
      if (seq !== loadSeq) return;
      items = (data.results || []).map((e) => ({ ...e, vol: e.vol || state.vol }));
      if (data.truncated) toast(t('search.truncated'), 'info');
    } else {
      const data = await get('/api/list', { vol: state.vol, path: state.path, sort: state.sort, order: state.order });
      if (seq !== loadSeq) return;
      state.readOnly = !!data.readOnly;
      items = (data.entries || []).map((e) => ({ ...e, vol: state.vol }));
    }
    state.items = items;
    updateTopButtons();
    render();
    emit('listed', items);
  } catch (e) {
    if (seq !== loadSeq) return;
    box.replaceChildren(h('div', { class: 'empty' }, h('p', { text: errorMessage(e) }),
      h('button', { class: 'btn', type: 'button', text: t('common.retry'), onclick: loadDir })));
  }
}

function showHome(box) {
  state.items = [];
  state.readOnly = true;
  updateTopButtons();
  rowEls.clear();
  if (!state.volumes.length) { box.replaceChildren(h('div', { class: 'empty' }, h('p', { text: t('home.none') }))); emit('listed', []); return; }
  box.replaceChildren(h('div', { class: 'volume-cards' }, state.volumes.map((v) => h('a', { class: 'volume-card', href: filesHash(v.id, '') },
    h('span', { class: 'vc-icon', 'aria-hidden': 'true', text: '\u{1F5C4}' }),
    h('span', { class: 'vc-name', text: v.label }),
    v.readOnly ? h('span', { class: 'badge', text: t('vol.readOnly') }) : null,
    v.freeBytes >= 0 ? h('span', { class: 'muted small', text: t('vol.free', { size: fmtSize(v.freeBytes) }) }) : null))));
  emit('listed', []);
}

export function openItem(it) {
  if (it.isDir) { navigate(it.vol, it.path); return; }
  openPreview(it, state.items.filter((i) => !i.isDir));
}

function checkbox(it) {
  return h('input', {
    type: 'checkbox', class: 'sel-box', 'aria-label': t('sel.select', { name: it.name }),
    onclick: (e) => { e.stopPropagation(); sel.toggle(it, e); },
  });
}

function moreBtn(it) {
  return h('button', {
    type: 'button', class: 'icon-btn more-btn', 'aria-label': t('act.more', { name: it.name }), text: '⋯',
    onclick: (e) => { e.stopPropagation(); if (!sel.isSelected(it) || sel.selected().length !== 1) sel.selectOnly(it); sel.actionsMenu(e.currentTarget, sel.selected()); },
  });
}

function nameLink(it) {
  return h('a', {
    class: 'name-link', href: it.isDir ? filesHash(it.vol, it.path) : '#', text: it.name, title: it.name,
    onclick: (e) => { if (e.ctrlKey || e.metaKey || e.shiftKey) return; e.preventDefault(); e.stopPropagation(); openItem(it); },
  });
}

function icon(it, big) {
  const k = kindOf(it);
  const box = h('span', { class: 'file-icon' + (big ? ' big' : ''), 'aria-hidden': 'true', text: ICONS[k] || ICONS.file });
  if (big && k === 'image') {
    const img = h('img', { class: 'thumb', loading: 'lazy', alt: '', src: thumbUrl(it.vol, it.path, 256) });
    img.addEventListener('error', () => img.remove());
    box.textContent = '';
    box.append(h('span', { class: 'thumb-fallback', text: ICONS.image }), img);
  }
  return box;
}

function rowClick(it, ev) {
  if (sel.applyClick(it, ev)) return;
  if (state.view === 'grid' && sel.selected().length === 0) { openItem(it); return; }
  if (state.view === 'grid') { sel.toggle(it); return; }
  sel.selectOnly(it);
}

function makeRow(it) {
  const where = state.query ? h('a', { class: 'row-path muted small', href: filesHash(it.vol, parentPath(it.path)), text: volumeLabel(it.vol) + '/' + parentPath(it.path), onclick: (e) => e.stopPropagation() }) : null;
  const row = h('div', { class: 'row', role: 'row', onclick: (e) => rowClick(it, e), ondblclick: () => openItem(it) },
    checkbox(it), icon(it, false),
    h('div', { class: 'cell-name' }, nameLink(it), where),
    h('div', { class: 'cell-size', text: it.isDir ? '' : fmtSize(it.size) }),
    h('div', { class: 'cell-date', text: fmtDate(it.mtime) }), moreBtn(it));
  return row;
}

function makeCard(it) {
  return h('div', { class: 'card', role: 'gridcell', onclick: (e) => rowClick(it, e), ondblclick: () => openItem(it) },
    checkbox(it), moreBtn(it), icon(it, true),
    h('div', { class: 'card-name' }, nameLink(it)),
    h('div', { class: 'card-meta muted small', text: it.isDir ? '' : fmtSize(it.size) }));
}

function sortHeader(label, field) {
  const active = state.sort === field;
  return h('button', {
    type: 'button', class: 'sort-btn' + (active ? ' active' : ''), 'aria-label': t('sort.by', { field: label }),
    text: label + (active ? (state.order === 'asc' ? ' ▲' : ' ▼') : ''),
    onclick: () => {
      if (state.query) return;
      if (state.sort === field) state.order = state.order === 'asc' ? 'desc' : 'asc'; else { state.sort = field; state.order = 'asc'; }
      loadDir();
    },
  });
}

export function render() {
  const box = $('files-content');
  rowEls.clear();
  if (!state.items.length) {
    box.replaceChildren(h('div', { class: 'empty' }, h('div', { class: 'empty-icon', 'aria-hidden': 'true', text: state.query ? '\u{1F50D}' : '\u{1F4C2}' }),
      h('p', { text: t(state.query ? 'search.empty' : 'folder.empty') }),
      !state.query && !state.readOnly ? h('p', { class: 'muted', text: t('folder.emptyHint') }) : null));
    return;
  }
  const mk = state.view === 'grid' ? makeCard : makeRow;
  const els = state.items.map((it) => { const el = mk(it); rowEls.set(itemKey(it), el); return el; });
  if (state.view === 'grid') {
    box.replaceChildren(h('div', { class: 'file-grid', role: 'grid', 'aria-label': t('files.label') }, els));
  } else {
    const all = h('input', { type: 'checkbox', class: 'sel-box', 'aria-label': t('sel.all'), onclick: (e) => (e.target.checked ? sel.selectAll() : sel.clear()) });
    box.replaceChildren(h('div', { class: 'file-table', role: 'grid', 'aria-label': t('files.label') },
      h('div', { class: 'row head', role: 'row' }, all, h('span', { class: 'file-icon' }),
        h('div', { class: 'cell-name' }, sortHeader(t('col.name'), 'name')),
        h('div', { class: 'cell-size' }, sortHeader(t('col.size'), 'size')),
        h('div', { class: 'cell-date' }, sortHeader(t('col.modified'), 'mtime')), h('span', { class: 'more-spacer' })),
      els));
  }
  syncSelection(sel.selected());
}

function syncSelection(items) {
  const keys = new Set(items.map(itemKey));
  rowEls.forEach((el, k) => {
    const on_ = keys.has(k);
    el.classList.toggle('selected', on_);
    const cb = el.querySelector('.sel-box');
    if (cb) cb.checked = on_;
  });
  const all = document.querySelector('.row.head .sel-box');
  if (all) { all.checked = items.length > 0 && items.length === state.items.length; }
}

async function createNew(kind) {
  const defs = { folder: ['dlg.newFolder', 'Folder', ''], md: ['dlg.newDoc', 'Untitled', '.md'], txt: ['dlg.newText', 'Untitled', '.txt'] };
  const [titleKey, base, ext] = defs[kind];
  const name = await promptDialog({ title: t(titleKey), label: t('dlg.name'), value: kind === 'folder' ? t('dlg.folderDefault') : base + ext, selectStem: kind !== 'folder', okLabel: t('common.create') });
  if (!name) return;
  try {
    if (kind === 'folder') {
      await post('/api/mkdir', { vol: state.vol, path: joinPath(state.path, name) });
      toast(t('toast.created'), 'success'); loadDir();
    } else {
      const final = /\.[A-Za-z0-9]+$/.test(name) ? name : name + ext;
      const r = await post('/api/newfile', { vol: state.vol, dir: state.path, name: final, kind });
      const entry = r.entry || { path: joinPath(state.path, final) };
      window.location.href = editorUrl(state.vol, entry.path);
    }
  } catch (e) { toast(errorMessage(e), 'error'); }
}

export function initBrowser() {
  state.view = store.get('fs.view', 'list') === 'grid' ? 'grid' : 'list';
  document.body.dataset.view = state.view;
  $('view-toggle').addEventListener('click', () => {
    state.view = state.view === 'list' ? 'grid' : 'list';
    store.set('fs.view', state.view);
    document.body.dataset.view = state.view;
    updateTopButtons();
    if (state.vol || state.query) render();
  });
  $('new-btn').addEventListener('click', (e) => menu(e.currentTarget, [
    { label: t('new.folder'), icon: '\u{1F4C1}', onClick: () => createNew('folder') },
    { label: t('new.doc'), icon: '\u{1F4DD}', onClick: () => createNew('md') },
    { label: t('new.text'), icon: '\u{1F4C4}', onClick: () => createNew('txt') },
  ]));
  $('upload-btn').addEventListener('click', (e) => menu(e.currentTarget, [
    { label: t('upload.files'), icon: '\u{1F4C4}', onClick: () => pickFiles(false) },
    { label: t('upload.folder'), icon: '\u{1F4C1}', onClick: () => pickFiles(true) },
  ]));
  $('search-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const q = $('search-input').value.trim();
    state.query = q;
    if (state.mode !== 'files') { state.keepQuery = true; window.location.hash = filesHash(state.vol, state.path); return; }
    loadDir();
  });
  $('search-input').addEventListener('input', (e) => { if (!e.target.value && state.query) { state.query = ''; loadDir(); } });
  $('search-input').addEventListener('keydown', (e) => { if (e.key === 'Escape') { e.target.value = ''; if (state.query) { state.query = ''; loadDir(); } e.target.blur(); } });
  on('selection', syncSelection);
  on('reload', () => { if (state.mode === 'files') loadDir(); });
  on('lang', () => { if (state.mode === 'files' && (state.vol || state.query || state.volumes.length)) { renderBreadcrumb(); updateTopButtons(); if (!state.vol && !state.query) showHome($('files-content')); else render(); } });
}

