// Selection model, selection bar and the file actions it triggers.
import { state, emit, on, itemKey, ref, kindOf, isEditable } from './state.js';
import { post, errorMessage, rawUrl, zipUrl, editorUrl, downloadUrl } from './api.js';
import { t, tn } from './i18n.js';
import { h, toast, confirmDialog, promptDialog, formDialog, menu, modalOpen } from './dialogs.js';
import { pickFolder } from './picker.js';
import { trackJob, toolMenuItems } from './jobs.js';

const map = new Map();
let anchorKey = null;

export const selected = () => [...map.values()];
export const isSelected = (it) => map.has(itemKey(it));

function changed() { emit('selection', selected()); updateBar(); }

export function clear() { if (map.size) { map.clear(); anchorKey = null; changed(); } }

export function selectOnly(it) { map.clear(); map.set(itemKey(it), it); anchorKey = itemKey(it); changed(); }

export function toggle(it, ev) {
  const k = itemKey(it);
  if (ev && ev.shiftKey && anchorKey) { range(it); return; }
  if (map.has(k)) map.delete(k); else map.set(k, it);
  anchorKey = k;
  changed();
}

export function range(it) {
  const keys = state.items.map(itemKey);
  const a = keys.indexOf(anchorKey);
  const b = keys.indexOf(itemKey(it));
  if (a < 0 || b < 0) { selectOnly(it); return; }
  map.clear();
  for (let i = Math.min(a, b); i <= Math.max(a, b); i++) map.set(keys[i], state.items[i]);
  changed();
}

export function selectAll() {
  map.clear();
  state.items.forEach((it) => map.set(itemKey(it), it));
  changed();
}

// Click semantics: modifier = selection, plain = caller decides (open vs select).
export function applyClick(it, ev) {
  if (ev.shiftKey) { range(it); return true; }
  if (ev.ctrlKey || ev.metaKey) { toggle(it); return true; }
  return false;
}

function inWritableDir() { return state.mode === 'files' && state.vol && !state.readOnly && !state.query; }

async function destination(title) {
  if (inWritableDir()) return { vol: state.vol, dir: state.path };
  return pickFolder({ title, writable: true });
}

function after(res, okMsg) {
  if (res && res.jobId) { trackJob(res.jobId); toast(t('jobs.started'), 'info'); }
  else if (okMsg) toast(okMsg, 'success');
  emit('reload');
}

export function download(items) {
  if (items.length === 1 && !items[0].isDir) { downloadUrl(rawUrl(items[0].vol, items[0].path, true)); return; }
  const byVol = new Map();
  items.forEach((it) => { if (!byVol.has(it.vol)) byVol.set(it.vol, []); byVol.get(it.vol).push(it.path); });
  let delay = 0;
  byVol.forEach((paths, vol) => { setTimeout(() => downloadUrl(zipUrl(vol, paths)), delay); delay += 600; });
}

export async function transfer(items, move) {
  const dest = await pickFolder({ title: t(move ? 'act.moveTo' : 'act.copyTo'), okLabel: t(move ? 'act.move' : 'act.copy') });
  if (!dest) return;
  try {
    const res = await post(move ? '/api/move' : '/api/copy', { items: items.map(ref), dstVol: dest.vol, dstDir: dest.dir, conflict: 'rename' });
    after(res, t(move ? 'toast.moved' : 'toast.copied'));
  } catch (e) { toast(errorMessage(e), 'error'); }
}

export async function rename(it) {
  const name = await promptDialog({ title: t('act.rename'), label: t('dlg.newName'), value: it.name, selectStem: !it.isDir, okLabel: t('act.rename') });
  if (!name || name === it.name) return;
  try { await post('/api/rename', { vol: it.vol, path: it.path, name }); emit('reload'); toast(t('toast.renamed'), 'success'); }
  catch (e) { toast(errorMessage(e), 'error'); }
}

export async function remove(items) {
  const msg = items.length === 1 ? t('dlg.deleteOne', { name: items[0].name }) : tn('dlg.deleteMany', items.length);
  if (!(await confirmDialog(msg, { title: t('act.delete'), okLabel: t('act.delete'), danger: true }))) return;
  try { const res = await post('/api/delete', { items: items.map(ref), permanent: false }); after(res, t('toast.deleted')); clear(); }
  catch (e) { toast(errorMessage(e), 'error'); }
}

async function compress(items) {
  const def = (items.length === 1 ? items[0].name.replace(/\.[^.]+$/, '') : 'archive') + '.zip';
  const dest = await destination(t('act.compressTo'));
  if (!dest) return;
  const v = await formDialog({ title: t('act.compress'), fields: [{ name: 'name', label: t('dlg.archiveName'), value: def, required: true }], okLabel: t('act.compress') });
  if (!v) return;
  const name = /\.zip$/i.test(v.name) ? v.name : v.name + '.zip';
  try { after(await post('/api/archive/zip', { items: items.map(ref), dstVol: dest.vol, dstDir: dest.dir, name }), null); }
  catch (e) { toast(errorMessage(e), 'error'); }
}

async function extract(it) {
  const dest = await destination(t('act.extractTo'));
  if (!dest) return;
  try { after(await post('/api/archive/extract', { vol: it.vol, path: it.path, dstVol: dest.vol, dstDir: dest.dir, conflict: 'rename' }), null); }
  catch (e) { toast(errorMessage(e), 'error'); }
}

function openEditor(it) { window.location.href = editorUrl(it.vol, it.path); }

function flags(items) {
  const one = items.length === 1 ? items[0] : null;
  const ro = items.some((i) => (state.volumes.find((v) => v.id === i.vol) || {}).readOnly);
  return { one, ro, hasSel: items.length > 0, canEdit: !!one && isEditable(one), canExtract: !!one && kindOf(one) === 'archive' };
}

function updateBar() {
  const bar = document.getElementById('selection-bar');
  if (!bar) return;
  const items = selected();
  bar.hidden = items.length === 0 || state.mode !== 'files';
  if (bar.hidden) return;
  const f = flags(items);
  document.getElementById('sel-count').textContent = tn('sel.count', items.length);
  const vis = { rename: f.one && !f.ro, edit: f.canEdit, extract: f.canExtract, delete: !f.ro, move: !f.ro };
  bar.querySelectorAll('[data-act]').forEach((b) => {
    const a = b.dataset.act;
    b.hidden = a in vis ? !vis[a] : false;
  });
}

export function actionsMenu(anchor, items) {
  const f = flags(items);
  menu(anchor, [
    { label: t('act.download'), icon: '⬇', onClick: () => download(items) },
    f.canEdit && { label: t('act.edit'), icon: '✏', onClick: () => openEditor(f.one) },
    { label: t('act.copy'), icon: '⧉', onClick: () => transfer(items, false) },
    !f.ro && { label: t('act.move'), icon: '➡', onClick: () => transfer(items, true) },
    f.one && !f.ro && { label: t('act.rename'), icon: '✎', onClick: () => rename(f.one) },
    { label: t('act.compress'), icon: '\u{1F5DC}', onClick: () => compress(items) },
    f.canExtract && { label: t('act.extract'), icon: '\u{1F4E6}', onClick: () => extract(f.one) },
    ...toolMenuItems(items, destination),
    !f.ro && { separator: true },
    !f.ro && { label: t('act.delete'), icon: '\u{1F5D1}', onClick: () => remove(items) },
  ]);
}

export function initSelection() {
  const bar = document.getElementById('selection-bar');
  bar.addEventListener('click', (e) => {
    const b = e.target.closest('[data-act]');
    if (!b) return;
    const items = selected();
    if (!items.length) return;
    const f = flags(items);
    switch (b.dataset.act) {
      case 'download': download(items); break;
      case 'copy': transfer(items, false); break;
      case 'move': transfer(items, true); break;
      case 'rename': if (f.one) rename(f.one); break;
      case 'delete': remove(items); break;
      case 'compress': compress(items); break;
      case 'extract': if (f.one) extract(f.one); break;
      case 'edit': if (f.one) openEditor(f.one); break;
      case 'tools': menu(b, toolMenuItems(items, destination)); break;
      case 'clear': clear(); break;
      default: break;
    }
  });
  document.addEventListener('keydown', (e) => {
    if (state.mode !== 'files' || modalOpen() || document.querySelector('.menu')) return;
    const tag = (e.target && e.target.tagName) || '';
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (e.target && e.target.isContentEditable)) return;
    if (document.querySelector('.preview-overlay')) return;
    const items = selected();
    if (e.key === 'Escape') { clear(); }
    else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a') { e.preventDefault(); selectAll(); }
    else if (e.key === 'Delete' && items.length && !flags(items).ro) { e.preventDefault(); remove(items); }
    else if (e.key === 'F2' && items.length === 1 && !flags(items).ro) { e.preventDefault(); rename(items[0]); }
  });
  on('lang', updateBar);
  on('listed', () => { map.clear(); anchorKey = null; changed(); });
}

