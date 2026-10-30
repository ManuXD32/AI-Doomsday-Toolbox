// Resumable chunked uploads (8 MiB chunks), drag and drop, folder upload, progress panel.
import { state, emit, fmtSize } from './state.js';
import { get, qs, request, errorMessage, ApiError } from './api.js';
import { h, toast } from './dialogs.js';
import { t, tn } from './i18n.js';

const CHUNK = 8 * 1024 * 1024;
const MAX_PARALLEL = 2;
const MAX_RETRIES = 3;
const tasks = [];
let reloadTimer = null;

const $ = (id) => document.getElementById(id);

function newId() {
  const a = new Uint8Array(16);
  crypto.getRandomValues(a);
  return Array.from(a, (b) => (b % 36).toString(36)).join('') + Date.now().toString(36);
}

function params(task, extra) {
  return { vol: task.vol, dir: task.dir, rel: task.rel, uploadId: task.uploadId, ...extra };
}

function putChunk(task, blob, offset) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    task.xhr = xhr;
    xhr.open('PUT', '/api/upload' + qs(params(task, { offset, total: task.total, conflict: 'rename' })));
    xhr.setRequestHeader('X-FileStation', '1');
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
    xhr.upload.onprogress = (e) => { task.sent = offset + e.loaded; renderTask(task); };
    xhr.onload = () => {
      let body = null;
      try { body = JSON.parse(xhr.responseText); } catch { /* not JSON */ }
      if (xhr.status >= 200 && xhr.status < 300) { resolve(body || {}); return; }
      const e = body && body.error ? body.error : {};
      const retry = Number(xhr.getResponseHeader('Retry-After')) || 0;
      reject(new ApiError(xhr.status, e.code || 'HTTP', e.message || xhr.statusText, body, retry));
    };
    xhr.onerror = () => reject(new ApiError(0, 'NETWORK', t('err.NETWORK')));
    xhr.onabort = () => reject(new ApiError(0, 'ABORTED', 'aborted'));
    xhr.send(blob);
  });
}

async function run(task) {
  task.state = 'uploading';
  task.error = '';
  renderTask(task);
  let failures = 0;
  try {
    if (task.resume) {
      const s = await get('/api/upload/status', params(task));
      task.received = Math.min(s.received || 0, task.total);
    }
    for (;;) {
      if (task.cancelled) return;
      const end = Math.min(task.received + CHUNK, task.total);
      try {
        const res = await putChunk(task, task.file.slice(task.received, end), task.received);
        failures = 0;
        task.received = typeof res.received === 'number' ? res.received : end;
        task.sent = task.received;
        renderTask(task);
        if (res.done || task.received >= task.total) break;
      } catch (e) {
        if (task.cancelled || e.code === 'ABORTED') return;
        if (e.code === 'CONFLICT' && e.body && typeof e.body.received === 'number' && failures++ < MAX_RETRIES) { task.received = e.body.received; continue; }
        if ((e.code === 'NETWORK' || e.status >= 500) && e.code !== 'NO_SPACE' && failures++ < MAX_RETRIES) {
          await new Promise((r) => setTimeout(r, 1000 * failures));
          try { const s = await get('/api/upload/status', params(task)); task.received = Math.min(s.received || 0, task.total); } catch { /* retry loop decides */ }
          continue;
        }
        throw e;
      }
    }
    task.state = 'done';
  } catch (e) {
    task.state = 'error';
    task.error = e instanceof ApiError && e.code === 'TOO_MANY_REQUESTS' ? t('login.wait', { s: e.retryAfter }) : errorMessage(e);
  } finally {
    task.xhr = null;
    renderTask(task);
    pump();
  }
}

function pump() {
  const running = tasks.filter((x) => x.state === 'uploading').length;
  let slots = MAX_PARALLEL - running;
  for (const task of tasks) {
    if (slots <= 0) break;
    if (task.state === 'queued') { slots--; run(task); }
  }
  updateSummary();
  if (!tasks.some((x) => x.state === 'queued' || x.state === 'uploading')) scheduleReload();
}

function scheduleReload() {
  if (!tasks.some((x) => x.state === 'done' && !x.reloaded)) return;
  clearTimeout(reloadTimer);
  reloadTimer = setTimeout(() => {
    const here = state.mode === 'files' && !state.query;
    tasks.forEach((x) => { if (x.state === 'done') x.reloaded = true; });
    if (here) emit('reload');
    emit('volumes');
  }, 400);
}

async function cancel(task) {
  task.cancelled = true;
  if (task.xhr) task.xhr.abort();
  task.state = 'cancelled';
  renderTask(task);
  try { await request('DELETE', '/api/upload' + qs(params(task))); } catch { /* partial may not exist */ }
  pump();
}

function retry(task) {
  task.cancelled = false;
  task.resume = true;
  task.state = 'queued';
  renderTask(task);
  pump();
}

function statusText(task) {
  switch (task.state) {
    case 'queued': return t('upload.queued');
    case 'uploading': return task.total ? Math.floor(Math.min(task.sent, task.total) * 100 / task.total) + '% · ' + fmtSize(Math.min(task.sent, task.total)) + ' / ' + fmtSize(task.total) : '0%';
    case 'done': return t('upload.done');
    case 'cancelled': return t('upload.cancelled');
    default: return t('upload.failed') + (task.error ? ': ' + task.error : '');
  }
}

function renderTask(task) {
  if (!task.el) {
    task.progress = h('progress', { max: 100, value: 0, 'aria-label': task.rel });
    task.status = h('div', { class: 'muted small ellipsis' });
    task.btn = h('button', { type: 'button', class: 'btn small' });
    task.el = h('li', { class: 'upload-item' },
      h('div', { class: 'ellipsis', title: task.rel, text: task.rel }), task.progress, task.status, task.btn);
    $('upload-list').append(task.el);
  }
  task.progress.value = task.total ? Math.min(100, task.sent * 100 / task.total) : (task.state === 'done' ? 100 : 0);
  task.status.textContent = statusText(task);
  task.el.dataset.state = task.state;
  const active = task.state === 'queued' || task.state === 'uploading';
  task.btn.hidden = task.state === 'done';
  task.btn.textContent = active ? t('common.cancel') : t('common.retry');
  task.btn.onclick = () => (active ? cancel(task) : retry(task));
  updateSummary();
}

function updateSummary() {
  const panel = $('upload-panel');
  panel.hidden = tasks.length === 0;
  const left = tasks.filter((x) => x.state === 'queued' || x.state === 'uploading').length;
  $('upload-title').textContent = left ? tn('upload.progress', left) : t('upload.finished');
  $('upload-clear').hidden = left > 0;
}

export function uploadFiles(list) {
  if (state.mode !== 'files' || !state.vol || state.query) { toast(t('upload.nodest'), 'error'); return; }
  if (state.readOnly) { toast(t('err.READ_ONLY'), 'error'); return; }
  const vol = state.vol;
  const dir = state.path;
  list.forEach(({ file, rel }) => {
    tasks.push({ file, rel: rel || file.name, vol, dir, total: file.size, received: 0, sent: 0, uploadId: newId(), state: 'queued', resume: false });
  });
  $('upload-panel').classList.remove('collapsed');
  tasks.slice(-list.length).forEach(renderTask);
  pump();
}

export function pickFiles(folder) {
  const input = $(folder ? 'folder-input' : 'file-input');
  input.value = '';
  input.click();
}

async function readEntries(reader) {
  const all = [];
  for (;;) {
    const batch = await new Promise((res, rej) => reader.readEntries(res, rej));
    if (!batch.length) return all;
    all.push(...batch);
  }
}

async function walk(entry, prefix, out) {
  if (entry.isFile) {
    const file = await new Promise((res, rej) => entry.file(res, rej));
    out.push({ file, rel: prefix + entry.name });
  } else if (entry.isDirectory) {
    for (const child of await readEntries(entry.createReader())) await walk(child, prefix + entry.name + '/', out);
  }
}

async function collectDrop(dt) {
  const entries = [...(dt.items || [])].map((i) => (i.webkitGetAsEntry ? i.webkitGetAsEntry() : null)).filter(Boolean);
  const out = [];
  if (!entries.length) return [...dt.files].map((file) => ({ file, rel: file.name }));
  for (const e of entries) await walk(e, '', out);
  return out;
}

export function initUpload() {
  const main = $('main');
  const overlay = $('drop-overlay');
  let depth = 0;
  const hasFiles = (e) => e.dataTransfer && [...e.dataTransfer.types].includes('Files');
  main.addEventListener('dragenter', (e) => { if (hasFiles(e)) { e.preventDefault(); depth++; overlay.hidden = false; } });
  main.addEventListener('dragover', (e) => { if (hasFiles(e)) e.preventDefault(); });
  main.addEventListener('dragleave', () => { depth = Math.max(0, depth - 1); if (!depth) overlay.hidden = true; });
  main.addEventListener('drop', async (e) => {
    if (!hasFiles(e)) return;
    e.preventDefault();
    depth = 0;
    overlay.hidden = true;
    try { const list = await collectDrop(e.dataTransfer); if (list.length) uploadFiles(list); }
    catch (err) { toast(errorMessage(err), 'error'); }
  });
  $('file-input').addEventListener('change', (e) => uploadFiles([...e.target.files].map((file) => ({ file, rel: file.name }))));
  $('folder-input').addEventListener('change', (e) => uploadFiles([...e.target.files].map((file) => ({ file, rel: file.webkitRelativePath || file.name }))));
  $('upload-toggle').addEventListener('click', () => $('upload-panel').classList.toggle('collapsed'));
  $('upload-clear').addEventListener('click', () => {
    for (let i = tasks.length - 1; i >= 0; i--) if (tasks[i].state !== 'queued' && tasks[i].state !== 'uploading') { tasks[i].el.remove(); tasks.splice(i, 1); }
    updateSummary();
  });
  window.addEventListener('beforeunload', (e) => {
    if (tasks.some((x) => x.state === 'queued' || x.state === 'uploading')) { e.preventDefault(); e.returnValue = ''; }
  });
}

