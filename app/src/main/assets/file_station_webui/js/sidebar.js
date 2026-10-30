// Sidebar: volumes, trash, jobs, tools registry (js/tools/index.js, optional) and the mobile drawer.
import { state, on, filesHash, fmtSize } from './state.js';
import { h } from './dialogs.js';
import { t, lang } from './i18n.js';

let tools = [];
const $ = (id) => document.getElementById(id);

export const getTools = () => tools;

export function toolLabel(tool) {
  const l = tool.label || tool.title || tool.id;
  return typeof l === 'string' ? l : (l[lang()] || l.en || tool.id);
}

// Registry contract (FS6b): export const tools = [{ id, label: string | {en, es}, icon?, mount(container, ctx), unmount?() }]
export async function loadTools() {
  try {
    const mod = await import('./tools/index.js');
    const list = mod.tools || mod.default || [];
    tools = Array.isArray(list) ? list : [];
  } catch { tools = []; }
}

function link(href, icon, label, active, extra) {
  return h('li', {}, h('a', { class: 'nav-item' + (active ? ' active' : ''), href, 'aria-current': active ? 'page' : null },
    h('span', { class: 'nav-icon', 'aria-hidden': 'true', text: icon }), h('span', { class: 'nav-label ellipsis', text: label }), extra || null));
}

export function renderSidebar(routeToolId) {
  const vols = $('nav-volumes');
  vols.replaceChildren(...state.volumes.map((v) => link(filesHash(v.id, ''), v.readOnly ? '\u{1F512}' : '\u{1F5C4}', v.label,
    state.mode === 'files' && state.vol === v.id && !state.query,
    h('span', { class: 'nav-extra' }, v.readOnly ? h('span', { class: 'badge', text: t('vol.readOnly') }) : null,
      v.freeBytes >= 0 ? h('span', { class: 'muted small', text: fmtSize(v.freeBytes) }) : null))));
  if (!state.volumes.length) vols.replaceChildren(h('li', { class: 'muted small pad', text: t('home.none') }));
  const running = state.jobs.filter((j) => j.state === 'queued' || j.state === 'running').length;
  $('nav-system').replaceChildren(
    link('#/trash', '\u{1F5D1}', t('nav.trash'), state.mode === 'trash'),
    link('#/jobs', '⚙', t('nav.jobs'), state.mode === 'jobs', running ? h('span', { class: 'badge count', text: String(running) }) : null));
  $('tools-heading').hidden = !tools.length;
  $('nav-tools').replaceChildren(...tools.map((tl) => link('#/tool/' + encodeURIComponent(tl.id), tl.icon || '\u{1F9F0}', toolLabel(tl), state.mode === 'tool' && routeToolId === tl.id)));
}

export function closeDrawer() { $('sidebar').classList.remove('open'); $('scrim').hidden = true; }

export function initSidebar() {
  $('menu-btn').addEventListener('click', () => {
    const open = $('sidebar').classList.toggle('open');
    $('scrim').hidden = !open;
  });
  $('scrim').addEventListener('click', closeDrawer);
  $('sidebar').addEventListener('click', (e) => { if (e.target.closest('a')) closeDrawer(); });
  on('jobs', () => renderSidebar(state.toolId));
  on('lang', () => renderSidebar(state.toolId));
}
