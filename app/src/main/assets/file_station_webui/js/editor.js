// Editor page: loads /api/text, edits Markdown (Toast UI) or plain text, saves with If-Match.
import { initI18n, t, lang, applyDom } from './i18n.js';
import { request, qs, errorMessage, ApiError, get } from './api.js';
import { store, baseName, parentPath, filesHash, extOf, encPath } from './state.js';
import { modal, toast, h } from './dialogs.js';
import { createPlainEditor, loadToastUi, sanitize } from './plaintext.js';

const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(window.location.search);
const vol = params.get('vol') || '';
const path = params.get('path') || '';
const name = baseName(path);
const isMd = ['md', 'markdown'].includes(extOf(name));

let etag = '';
let savedText = '';
let dirty = false;
let readOnly = false;
let saving = false;
let ed = null; // {getValue, setValue, focus}

function applyTheme() {
  const pref = store.get('fs.theme', 'system');
  const dark = pref === 'dark' || (pref === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  return dark;
}

function setDirty(v) {
  dirty = v;
  $('ed-dot').hidden = !v;
  document.title = (v ? '● ' : '') + name + ' - ' + t('editor.title');
}

function showStatus(msg, isError) {
  const s = $('ed-status');
  s.hidden = false;
  s.textContent = msg;
  s.classList.toggle('error-text', !!isError);
  $('ed-host').hidden = true;
}

const textUrl = () => '/api/text' + qs({ vol, path });

async function fetchText() {
  const res = await request('GET', textUrl());
  return { text: await res.text(), etag: res.headers.get('ETag') || '' };
}

function currentText() { return ed.getValue(); }
function checkDirty() { setDirty(currentText() !== savedText); }

// Computed before the Markdown editor starts so relative attachment paths resolve against the document folder.
function setBase() {
  const dir = parentPath(path);
  const base = document.createElement('base');
  base.href = '/raw/' + encodeURIComponent(vol) + '/' + (dir ? encPath(dir) + '/' : '');
  document.head.prepend(base);
}

async function uploadImage(blob) {
  const ext = (blob.type.split('/')[1] || 'png').replace(/[^a-z0-9]/gi, '').slice(0, 5) || 'png';
  const fname = blob.name && blob.name !== 'image.png' ? blob.name : 'image-' + Date.now().toString(36) + '.' + ext;
  const res = await request('PUT', '/api/attachment' + qs({ vol, doc: path, name: fname }), {
    body: blob, headers: { 'Content-Type': blob.type || 'application/octet-stream' },
  });
  const data = await res.json();
  return { url: data.url || data.path, alt: fname.replace(/\.[^.]+$/, '') };
}

async function mountMarkdown(text, dark) {
  await loadToastUi({ dark, es: lang() === 'es' });
  setBase();
  const host = $('ed-host');
  host.replaceChildren();
  const el = h('div', { class: dark ? 'toastui-editor-dark' : '' });
  host.append(el);
  if (readOnly) {
    host.classList.add('ed-viewer');
    const v = window.toastui.Editor.factory({ el, viewer: true, initialValue: text, customHTMLSanitizer: sanitize });
    ed = { getValue: () => text, setValue: () => {}, focus: () => {}, viewer: v };
    return;
  }
  host.classList.remove('ed-viewer');
  const editor = new window.toastui.Editor({
    el, height: '100%', initialEditType: 'wysiwyg', previewStyle: 'vertical', initialValue: text,
    theme: dark ? 'dark' : 'light', language: lang() === 'es' ? 'es-ES' : 'en-US',
    usageStatistics: false, customHTMLSanitizer: sanitize,
    hooks: {
      addImageBlobHook: async (blob, callback) => {
        try { const r = await uploadImage(blob); callback(r.url, r.alt); } catch (e) { toast(errorMessage(e), 'error'); }
      },
    },
  });
  editor.on('change', checkDirty);
  ed = { getValue: () => editor.getMarkdown(), setValue: (v) => editor.setMarkdown(v, false), focus: () => editor.focus() };
}

function mountPlain(text) {
  const host = $('ed-host');
  host.classList.remove('ed-viewer');
  const p = createPlainEditor(host, { text, readOnly, onChange: checkDirty, onSave: save });
  const wrapBtn = $('ed-wrap');
  wrapBtn.hidden = false;
  wrapBtn.addEventListener('click', () => {
    const on = wrapBtn.getAttribute('aria-pressed') !== 'true';
    wrapBtn.setAttribute('aria-pressed', String(on));
    p.setWrap(on);
  });
  ed = p;
}

async function load(dark) {
  showStatus(t('common.loading'));
  let r;
  try { r = await fetchText(); } catch (e) {
    if (e instanceof ApiError && e.status === 401) { window.location.href = '/'; return false; }
    showStatus(e instanceof ApiError && e.status === 415 ? t('editor.binary') : errorMessage(e), true);
    return false;
  }
  etag = r.etag;
  $('ed-status').hidden = true;
  $('ed-host').hidden = false;
  if (ed) { ed.setValue(r.text); } else if (isMd) { await mountMarkdown(r.text, dark); } else { mountPlain(r.text); }
  savedText = ed.getValue();
  setDirty(false);
  return true;
}

async function put(ifMatch) {
  const text = currentText();
  const res = await request('PUT', textUrl(), {
    body: text, headers: { 'If-Match': ifMatch, 'Content-Type': 'text/plain; charset=utf-8' },
  });
  let data = {};
  try { data = await res.json(); } catch { /* no body */ }
  etag = data.etag ? (data.etag.startsWith('"') ? data.etag : '"' + data.etag + '"') : (res.headers.get('ETag') || etag);
  savedText = text;
  checkDirty();
}

async function conflict(currentEtag) {
  const m = modal({
    title: t('editor.conflict.title'),
    body: h('p', { class: 'modal-text', text: t('editor.conflict.body') }),
    buttons: [
      { label: t('common.cancel'), value: 'cancel' },
      { label: t('editor.reload'), value: 'reload' },
      { label: t('editor.overwrite'), value: 'overwrite', primary: true, danger: true },
    ],
  });
  const choice = await m.promise;
  if (choice === 'reload') { await load(applyTheme()); return; }
  if (choice === 'overwrite') {
    try { await put(currentEtag); toast(t('editor.saved'), 'success'); } catch (e) { toast(errorMessage(e), 'error'); }
  }
}

async function save() {
  if (readOnly || saving || !ed) return;
  saving = true;
  $('ed-save').disabled = true;
  try {
    await put(etag || '*');
    toast(t('editor.saved'), 'success');
  } catch (e) {
    if (e instanceof ApiError && e.status === 412) await conflict(e.body && e.body.currentEtag);
    else toast(errorMessage(e), 'error');
  } finally {
    saving = false;
    $('ed-save').disabled = readOnly;
  }
}

async function boot() {
  await initI18n();
  const dark = applyTheme();
  applyDom(document);
  $('ed-name').textContent = name;
  $('ed-name').title = path;
  document.title = name + ' - ' + t('editor.title');
  $('ed-back').href = '/' + filesHash(vol, parentPath(path));
  if (!vol || !path) { showStatus(t('editor.nofile'), true); $('ed-save').hidden = true; return; }
  try {
    const v = ((await get('/api/volumes')).volumes || []).find((x) => x.id === vol);
    readOnly = !!(v && v.readOnly);
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) { window.location.href = '/'; return; }
  }
  $('ed-readonly').hidden = !readOnly;
  $('ed-save').hidden = readOnly;
  $('ed-save').addEventListener('click', save);
  document.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') { e.preventDefault(); save(); }
  });
  window.addEventListener('beforeunload', (e) => { if (dirty) { e.preventDefault(); e.returnValue = ''; } });
  if (!(await load(dark))) $('ed-save').hidden = true;
}

boot();
