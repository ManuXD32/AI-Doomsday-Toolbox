// Boot: i18n, session/login, theme, language switch, hash router.
import { state, on, store, filesHash } from './state.js';
import { get, post, errorMessage, ApiError } from './api.js';
import { initI18n, setLang, lang, t, applyDom } from './i18n.js';
import { h, toast, menu } from './dialogs.js';
import { initSidebar, renderSidebar, loadTools, getTools, toolLabel } from './sidebar.js';
import { initBrowser, loadDir } from './browser.js';
import { initSelection } from './selection.js';
import { initUpload } from './upload.js';
import { initJobs, showJobs } from './jobs.js';
import { showTrash } from './trash.js';

const $ = (id) => document.getElementById(id);
let started = false;
let activeTool = null;

// ---- Theme ----
const media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
function applyTheme() {
  const pref = store.get('fs.theme', 'system');
  const dark = pref === 'dark' || (pref === 'system' && media && media.matches);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  const icon = { light: '☀', dark: '☾', system: '\u{1F313}' }[pref] || '◐';
  $('theme-btn').textContent = icon;
  $('theme-btn').title = t('theme.' + pref);
}
function initTheme() {
  applyTheme();
  if (media && media.addEventListener) media.addEventListener('change', applyTheme);
  $('theme-btn').addEventListener('click', (e) => menu(e.currentTarget, ['light', 'dark', 'system'].map((p) => ({
    label: t('theme.' + p), onClick: () => { store.set('fs.theme', p); applyTheme(); },
  }))));
  on('lang', applyTheme);
}

function initLangSwitch() {
  const sync = () => $('lang-switch').querySelectorAll('button').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.lang === lang())));
  $('lang-switch').addEventListener('click', (e) => { const b = e.target.closest('button'); if (b) setLang(b.dataset.lang); });
  on('lang', sync);
  sync();
}

// ---- Views / router ----
function setMode(mode) {
  state.mode = mode;
  document.body.dataset.mode = mode;
  ['files', 'trash', 'jobs', 'tool'].forEach((m) => { $('view-' + m).hidden = m !== mode; });
  if (mode !== 'tool' && activeTool && activeTool.unmount) { try { activeTool.unmount(); } catch { /* ignore */ } activeTool = null; }
  $('selection-bar').hidden = true;
}

function parseHash() {
  const parts = window.location.hash.replace(/^#\/?/, '').split('/').filter((x) => x !== '').map((x) => { try { return decodeURIComponent(x); } catch { return x; } });
  return parts;
}

async function route() {
  const parts = parseHash();
  state.toolId = null;
  if (parts[0] === 'trash') { setMode('trash'); renderSidebar(); showTrash(); return; }
  if (parts[0] === 'jobs') { setMode('jobs'); renderSidebar(); showJobs(); return; }
  if (parts[0] === 'tool' && parts[1]) {
    const tool = getTools().find((x) => x.id === parts[1]);
    setMode('tool');
    state.toolId = parts[1];
    renderSidebar(parts[1]);
    const box = $('view-tool');
    box.replaceChildren();
    if (!tool) { box.append(h('div', { class: 'empty' }, h('p', { text: t('tools.missing') }))); return; }
    box.append(h('div', { class: 'view-bar' }, h('h2', { text: toolLabel(tool) })));
    const holder = h('div', { class: 'tool-host' });
    box.append(holder);
    activeTool = tool;
    try { await tool.mount(holder, { t, lang, get, post }); } catch (e) { holder.append(h('p', { class: 'error-text', text: errorMessage(e) })); }
    return;
  }
  // files
  const vol = parts[0] === 'files' ? parts[1] || null : null;
  const path = vol ? parts.slice(2).join('/') : '';
  if (!vol && state.volumes.length === 1 && !window.location.hash.includes('#/files')) { window.location.hash = filesHash(state.volumes[0].id, ''); return; }
  setMode('files');
  if (!state.keepQuery && (vol !== state.vol || path !== state.path)) state.query = '';
  state.keepQuery = false;
  if (!state.query) $('search-input').value = '';
  state.vol = vol;
  state.path = path;
  state.readOnly = vol ? !!(state.volumes.find((v) => v.id === vol) || {}).readOnly : true;
  renderSidebar();
  loadDir();
}

async function refreshVolumes() {
  try { state.volumes = (await get('/api/volumes')).volumes || []; } catch (e) { toast(errorMessage(e), 'error'); }
  renderSidebar(state.toolId);
}

async function startApp() {
  $('login-view').hidden = true;
  $('app').hidden = false;
  $('logout-btn').hidden = !(state.session && state.session.authRequired);
  const warn = $('unprotected-warn');
  warn.hidden = !(state.session && !state.session.authRequired) || store.get('fs.warnDismissed', '') === '1';
  await refreshVolumes();
  if (!started) {
    started = true;
    await loadTools();
    initSidebar(); initBrowser(); initSelection(); initUpload(); initJobs();
    on('volumes', refreshVolumes);
    on('lang', () => renderSidebar(state.toolId));
    window.addEventListener('hashchange', route);
  }
  renderSidebar();
  route();
}

function showLogin() {
  $('app').hidden = true;
  $('login-view').hidden = false;
  $('login-error').textContent = '';
  $('login-password').value = '';
  $('login-password').focus();
}

function initLogin() {
  $('login-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = $('login-submit');
    btn.disabled = true;
    $('login-error').textContent = '';
    try {
      await post('/api/login', { password: $('login-password').value });
      state.session = await get('/api/session');
      await startApp();
    } catch (err) {
      let msg = errorMessage(err);
      if (err instanceof ApiError && err.status === 429) msg = t('login.wait', { s: err.retryAfter || 30 });
      else if (err instanceof ApiError && err.status === 401) msg = t('login.bad');
      $('login-error').textContent = msg;
    } finally { btn.disabled = false; }
  });
  $('logout-btn').addEventListener('click', async () => { try { await post('/api/logout'); } catch { /* ignore */ } state.session = await get('/api/session').catch(() => state.session); showLogin(); });
  $('warn-dismiss').addEventListener('click', () => { store.set('fs.warnDismissed', '1'); $('unprotected-warn').hidden = true; });
  on('unauthorized', showLogin);
}

async function boot() {
  try { await initI18n(); } catch { /* English strings missing: keys are shown */ }
  initTheme();
  initLangSwitch();
  initLogin();
  applyDom(document);
  try {
    state.session = await get('/api/session');
  } catch (e) {
    toast(errorMessage(e), 'error', 10000);
    return;
  }
  if (state.session.authRequired && !state.session.authenticated) showLogin(); else await startApp();
}

boot();
