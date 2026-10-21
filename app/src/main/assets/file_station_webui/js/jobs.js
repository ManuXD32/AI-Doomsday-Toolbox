// Jobs panel (polling /api/jobs) and the generic server-tool dialog (/api/tools/...).
import { state, emit, on, kindOf, ref, filesHash, parentPath, fmtSize } from './state.js';
import { get, post, errorMessage, rawUrl } from './api.js';
import { h, toast, formDialog } from './dialogs.js';
import { t } from './i18n.js';

const known = new Map(); // id -> last state
const ACTIVE = (j) => j.state === 'queued' || j.state === 'running';
let timer = null;
let polling = false;

export async function refreshJobs() {
  if (polling) return;
  polling = true;
  try {
    const data = await get('/api/jobs');
    const jobs = data.jobs || [];
    let outputsChanged = false;
    jobs.forEach((j) => {
      const before = known.get(j.id);
      if (before && ACTIVE({ state: before }) && !ACTIVE(j)) {
        if (j.state === 'done') { toast(t('jobs.doneToast', { title: j.title || j.kind }), 'success'); outputsChanged = true; }
        else if (j.state === 'failed') toast(t('jobs.failedToast', { title: j.title || j.kind }) + ': ' + failText(j), 'error');
      }
      known.set(j.id, j.state);
    });
    state.jobs = jobs;
    emit('jobs', jobs);
    if (outputsChanged && state.mode === 'files') emit('reload');
    if (outputsChanged) emit('volumes');
  } catch { /* transient; polling continues */ }
  polling = false;
  schedule();
}

function schedule() {
  clearTimeout(timer);
  if (state.jobs.some(ACTIVE) || state.mode === 'jobs') timer = setTimeout(refreshJobs, state.jobs.some(ACTIVE) ? 1200 : 4000);
}

export function trackJob(id) {
  known.set(id, 'queued');
  refreshJobs();
}

function failText(j) {
  const k = 'err.' + j.errorCode;
  return j.errorCode && t(k) !== k ? t(k) : (j.message || t('err.HTTP'));
}

function resultView(r) {
  if (!r) return null;
  if (Array.isArray(r.groups)) {
    if (!r.groups.length) return h('p', { class: 'muted small', text: t('jobs.noDuplicates') });
    return h('div', { class: 'job-result' }, r.groups.map((g) => h('div', { class: 'dup-group' },
      h('div', { class: 'small', text: t('jobs.dupGroup', { size: fmtSize(g.size), n: (g.paths || []).length }) }),
      h('ul', {}, (g.paths || []).map((p) => h('li', { class: 'ellipsis small', title: p, text: p }))))));
  }
  let txt = JSON.stringify(r, null, 2);
  if (txt.length > 4000) txt = txt.slice(0, 4000) + '\n…';
  return h('pre', { class: 'text-pre job-result', text: txt });
}

function card(j) {
  const active = ACTIVE(j);
  const prog = h('progress', { max: 100, 'aria-label': j.title || j.kind });
  if (j.progress >= 0 && j.state !== 'queued') prog.value = Math.round(j.progress * 100); else if (!active) prog.value = j.state === 'done' ? 100 : 0;
  const stateLabel = t('jobs.state.' + j.state);
  return h('li', { class: 'job-card ' + j.state },
    h('div', { class: 'job-head' }, h('strong', { class: 'ellipsis', text: j.title || j.kind }), h('span', { class: 'badge job-' + j.state, text: stateLabel }),
      active ? h('button', { class: 'btn small', type: 'button', text: t('common.cancel'), onclick: async () => { try { await post('/api/jobs/' + encodeURIComponent(j.id) + '/cancel'); } catch (e) { toast(errorMessage(e), 'error'); } refreshJobs(); } }) : null),
    active || j.state === 'failed' ? prog : null,
    j.state === 'failed' ? h('p', { class: 'error-text small', text: failText(j) }) : (j.message ? h('p', { class: 'muted small ellipsis', text: j.message }) : null),
    (j.outputs || []).length ? h('ul', { class: 'job-outputs' }, j.outputs.slice(0, 20).map((o) => h('li', { class: 'ellipsis' },
      h('a', { href: filesHash(o.vol, parentPath(o.path)), text: o.path }), ' ', h('a', { class: 'small', href: rawUrl(o.vol, o.path, true), text: t('act.download') })))) : null,
    resultView(j.result));
}

export function showJobs() {
  showJobsOnly();
  refreshJobs();
}

export function initJobs() {
  on('jobs', () => { if (state.mode === 'jobs') showJobsDraw(); });
  on('lang', () => { if (state.mode === 'jobs') showJobsDraw(); });
  refreshJobs();
}
function showJobsDraw() {
  const box = document.getElementById('view-jobs');
  const top = box.scrollTop;
  showJobsOnly();
  box.scrollTop = top;
}
function showJobsOnly() {
  const box = document.getElementById('view-jobs');
  box.replaceChildren(h('div', { class: 'view-bar' }, h('h2', { text: t('jobs.title') }), h('span', { class: 'spacer' }),
    h('button', { class: 'btn', type: 'button', text: t('common.refresh'), onclick: refreshJobs })),
  state.jobs.length ? h('ul', { class: 'job-list' }, state.jobs.map(card))
    : h('div', { class: 'empty' }, h('div', { class: 'empty-icon', 'aria-hidden': 'true', text: '⚙' }), h('p', { text: t('jobs.none') })));
}

// ---- Server tools -------------------------------------------------------------------------------------------

const isK = (...kinds) => (it) => kinds.includes(kindOf(it));
const allOf = (items, pred) => items.length > 0 && items.every(pred);
const FORMATS = [{ value: 'jpeg', label: 'JPEG' }, { value: 'png', label: 'PNG' }, { value: 'webp', label: 'WebP' }];
const PRESETS = ['mp3-128', 'mp3-192', 'aac-128', 'opus-96', 'flac', 'wav', 'mp4-720p', 'mp4-480p', 'webm-480p', 'gif'];
const num = (name, label, value, min, max, required) => ({ name, label: t(label), type: 'number', value, min, max, required });
const dims = () => [num('maxWidth', 'tf.maxWidth', '', 1, 16384), num('maxHeight', 'tf.maxHeight', '', 1, 16384)];
const fmt = (name = 'format') => ({ name, label: t('tf.format'), type: 'select', options: FORMATS, value: 'jpeg' });
const nameField = (v) => ({ name: 'name', label: t('tf.name'), value: v, required: true });

// accepts(items) decides visibility; single => body uses `item`; data => no destination; fields() built lazily for i18n.
const SPECS = [
  { id: 'image.convert', cap: 'image', url: '/api/tools/image/convert', accepts: (i) => allOf(i, isK('image')), fields: () => [fmt(), num('quality', 'tf.quality', 85, 1, 100, true), ...dims()] },
  { id: 'image.resize', cap: 'image', url: '/api/tools/image/resize', accepts: (i) => allOf(i, isK('image')), fields: () => [...dims(), { ...fmt(), options: [{ value: '', label: t('tf.keepFormat') }, ...FORMATS], value: '' }] },
  { id: 'image.compress', cap: 'image', url: '/api/tools/image/compress', accepts: (i) => allOf(i, isK('image')), fields: () => [num('quality', 'tf.quality', 70, 1, 100, true)] },
  { id: 'pdf.merge', cap: 'pdf', url: '/api/tools/pdf/merge', accepts: (i) => i.length > 1 && allOf(i, isK('pdf')), fields: () => [nameField('merged.pdf')] },
  { id: 'pdf.split', cap: 'pdf', url: '/api/tools/pdf/split', single: true, accepts: (i) => i.length === 1 && allOf(i, isK('pdf')), fields: () => [{ name: 'ranges', label: t('tf.ranges'), placeholder: '1-3,5', required: true }] },
  { id: 'pdf.images2pdf', cap: 'pdf', url: '/api/tools/pdf/images2pdf', accepts: (i) => allOf(i, isK('image')), fields: () => [nameField('images.pdf')] },
  { id: 'pdf.pdf2images', cap: 'pdf', url: '/api/tools/pdf/pdf2images', single: true, accepts: (i) => i.length === 1 && allOf(i, isK('pdf')), fields: () => [{ ...fmt(), options: [{ value: 'png', label: 'PNG' }, { value: 'jpeg', label: 'JPEG' }], value: 'png' }, num('dpi', 'tf.dpi', 150, 72, 300, true)] },
  { id: 'pdf.pdf2text', cap: 'pdf', url: '/api/tools/pdf/pdf2text', single: true, accepts: (i) => i.length === 1 && allOf(i, isK('pdf')), fields: () => [] },
  { id: 'doc.topdf', cap: 'doc', url: '/api/tools/doc/topdf', single: true, accepts: (i) => i.length === 1 && allOf(i, (x) => ['md', 'html', 'htm', 'txt'].includes(x.name.split('.').pop().toLowerCase())), fields: () => [] },
  { id: 'doc.totext', cap: 'doc', url: '/api/tools/doc/totext', single: true, accepts: (i) => i.length === 1 && allOf(i, (x) => ['docx', 'odt'].includes(x.name.split('.').pop().toLowerCase())), fields: () => [{ name: 'format', label: t('tf.format'), type: 'select', options: [{ value: 'txt', label: 'TXT' }, { value: 'html', label: 'HTML' }], value: 'txt' }] },
  { id: 'doc.csv2json', cap: 'doc', url: '/api/tools/doc/csv2json', single: true, accepts: (i) => i.length === 1 && allOf(i, (x) => /\.csv$/i.test(x.name)), fields: () => [] },
  { id: 'doc.json2csv', cap: 'doc', url: '/api/tools/doc/json2csv', single: true, accepts: (i) => i.length === 1 && allOf(i, (x) => /\.json$/i.test(x.name)), fields: () => [] },
  { id: 'media.convert', cap: 'media', url: '/api/tools/media/convert', single: true, accepts: (i) => i.length === 1 && allOf(i, isK('video', 'audio')), fields: (caps) => [{ name: 'preset', label: t('tf.preset'), type: 'select', options: (caps.mediaPresets || PRESETS).map((p) => ({ value: p, label: p })), value: 'mp3-128' }] },
  { id: 'media.trim', cap: 'media', url: '/api/tools/media/trim', single: true, accepts: (i) => i.length === 1 && allOf(i, isK('video', 'audio')), fields: (caps) => [num('startSec', 'tf.start', 0, 0, undefined, true), num('endSec', 'tf.end', 10, 0, undefined, true), { name: 'preset', label: t('tf.preset'), type: 'select', options: [{ value: '', label: t('tf.streamCopy') }, ...(caps.mediaPresets || PRESETS).map((p) => ({ value: p, label: p }))], value: '' }] },
  { id: 'data.sha256', url: '/api/tools/sha256', data: true, accepts: (i) => i.length > 0 && i.every((x) => !x.isDir), fields: () => [], body: (items) => ({ items: items.map(ref) }) },
  { id: 'data.duplicates', url: '/api/tools/duplicates', data: true, accepts: (i) => i.length === 1 && i[0].isDir, fields: () => [num('minSize', 'tf.minSize', 1, 0)], body: (items, v) => ({ vol: items[0].vol, path: items[0].path, minSize: Number(v.minSize) || 0 }) },
  { id: 'data.usage', url: '/api/tools/usage', data: true, accepts: (i) => i.length === 1 && i[0].isDir, fields: () => [], body: (items) => ({ vol: items[0].vol, path: items[0].path }) },
];

async function capabilities() {
  if (!state.caps) { try { state.caps = await get('/api/tools/capabilities'); } catch { state.caps = {}; } }
  return state.caps;
}

async function runTool(spec, items, pickDest) {
  const caps = await capabilities();
  const fields = spec.fields(caps);
  let values = {};
  if (fields.length) {
    values = await formDialog({ title: t('tool.' + spec.id), fields, okLabel: t('tool.run') });
    if (!values) return;
  }
  let body;
  if (spec.body) body = spec.body(items, values);
  else {
    const dest = await pickDest(t('tool.saveTo'));
    if (!dest) return;
    const extra = {};
    Object.entries(values).forEach(([k, v]) => { if (v !== '') extra[k] = ['maxWidth', 'maxHeight', 'quality', 'dpi', 'startSec', 'endSec'].includes(k) ? Number(v) : v; });
    body = { ...(spec.single ? { item: ref(items[0]) } : { items: items.map(ref) }), ...extra, dstVol: dest.vol, dstDir: dest.dir, conflict: 'rename' };
  }
  try {
    const res = await post(spec.url, body);
    if (res.jobId) { trackJob(res.jobId); toast(t('jobs.started'), 'info'); }
  } catch (e) { toast(errorMessage(e), 'error'); }
}

// Menu entries for the selection "Tools" menu / row actions menu.
export function toolMenuItems(items, pickDest) {
  const tools = (state.session && state.session.tools) || {};
  const list = SPECS.filter((s) => (s.cap ? tools[s.cap] !== false : true) && s.accepts(items));
  if (!list.length) return [{ label: t('tool.none'), disabled: true }];
  return list.map((s) => ({ label: t('tool.' + s.id), icon: '\u{1F9F0}', onClick: () => runTool(s, items, pickDest) }));
}
