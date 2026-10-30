// Previews: image lightbox (prev/next), video/audio, PDF, text and Markdown (read-only).
import { kindOf, fmtSize, isEditable, parentPath } from './state.js';
import { rawUrl, request, editorUrl, errorMessage, downloadUrl } from './api.js';
import { h } from './dialogs.js';
import { t } from './i18n.js';

const TEXT_LIMIT = 256 * 1024;
let overlay = null;
let onKeyHandler = null;

export function closePreview() {
  if (!overlay) return;
  overlay.querySelectorAll('video,audio').forEach((m) => { m.pause(); m.removeAttribute('src'); m.load(); });
  overlay.remove();
  overlay = null;
  document.removeEventListener('keydown', onKeyHandler, true);
  onKeyHandler = null;
}

// Relative image links in a rendered document resolve against the document folder (the page itself lives at "/").
function fixRelativeImages(box, it) {
  box.querySelectorAll('img').forEach((img) => {
    const src = img.getAttribute('src') || '';
    if (!src || /^([a-z][a-z0-9+.-]*:|\/|#)/i.test(src)) return;
    let rel;
    try { rel = decodeURIComponent(src.split(/[?#]/)[0]); } catch { return; }
    const dir = parentPath(it.path);
    const segs = dir ? dir.split('/') : [];
    rel.split('/').forEach((p) => { if (p === '..') segs.pop(); else if (p && p !== '.') segs.push(p); });
    img.setAttribute('src', rawUrl(it.vol, segs.join('/')));
  });
}

async function textInto(box, it) {
  box.textContent = t('common.loading');
  try {
    const res = await request('GET', rawUrl(it.vol, it.path), { headers: { Range: 'bytes=0-' + (TEXT_LIMIT - 1) } });
    const text = await res.text();
    const cr = res.headers.get('Content-Range') || '';
    const total = Number(cr.split('/')[1]) || it.size || 0;
    const truncated = res.status === 206 && total > TEXT_LIMIT;
    box.replaceChildren();
    if (kindOf(it) === 'markdown') {
      // Hook for FS6b: js/plaintext.js may export renderMarkdown(container, text) using the vendored Toast UI viewer.
      let done = false;
      try {
        const mod = await import('./plaintext.js');
        if (mod && typeof mod.renderMarkdown === 'function') { box.classList.add('md-render'); await mod.renderMarkdown(box, text); done = true; fixRelativeImages(box, it); }
      } catch { /* helper not available: plain text */ }
      if (!done) box.append(h('pre', { class: 'text-pre', text }));
    } else {
      box.append(h('pre', { class: 'text-pre', text }));
    }
    if (truncated) box.append(h('p', { class: 'muted small', text: t('preview.truncated', { size: fmtSize(TEXT_LIMIT) }) }));
  } catch (e) {
    box.replaceChildren(h('p', { class: 'muted', text: errorMessage(e) }));
  }
}

export function openPreview(it, siblings = []) {
  closePreview();
  const kind = kindOf(it);
  const images = kind === 'image' ? siblings.filter((s) => kindOf(s) === 'image') : [];
  let idx = Math.max(0, images.findIndex((s) => s.path === it.path && s.vol === it.vol));
  let cur = it;

  const stage = h('div', { class: 'preview-stage' });
  const title = h('div', { class: 'preview-title ellipsis' });
  const actions = h('div', { class: 'preview-actions' });
  const prev = h('button', { class: 'nav-btn prev', type: 'button', 'aria-label': t('preview.prev'), text: '‹', onclick: () => step(-1) });
  const next = h('button', { class: 'nav-btn next', type: 'button', 'aria-label': t('preview.next'), text: '›', onclick: () => step(1) });
  overlay = h('div', { class: 'preview-overlay', role: 'dialog', 'aria-modal': 'true', 'aria-label': t('preview.title') },
    h('div', { class: 'preview-bar' }, title, actions,
      h('button', { class: 'icon-btn', type: 'button', 'aria-label': t('common.close'), text: '✕', onclick: closePreview })),
    stage, images.length > 1 ? prev : null, images.length > 1 ? next : null);
  overlay.addEventListener('mousedown', (e) => { if (e.target === stage && kindOf(cur) !== 'pdf') closePreview(); });

  function step(d) {
    if (images.length < 2) return;
    idx = (idx + d + images.length) % images.length;
    show(images[idx]);
  }

  function show(item) {
    cur = item;
    const k = kindOf(item);
    const url = rawUrl(item.vol, item.path);
    title.textContent = item.name + (images.length > 1 ? '  (' + (idx + 1) + '/' + images.length + ')' : '');
    title.title = item.name;
    actions.replaceChildren(
      h('button', { class: 'btn small', type: 'button', text: t('act.download'), onclick: () => downloadUrl(rawUrl(item.vol, item.path, true)) }),
      k === 'pdf' ? h('a', { class: 'btn small', href: url, target: '_blank', rel: 'noopener', text: t('preview.newTab') }) : null,
      isEditable(item) ? h('a', { class: 'btn small primary', href: editorUrl(item.vol, item.path), text: t('act.edit') }) : null);
    stage.replaceChildren();
    if (k === 'image') {
      const img = h('img', { class: 'preview-img', alt: item.name, src: url });
      img.addEventListener('error', () => stage.replaceChildren(h('p', { class: 'preview-msg', text: t('preview.failed') })));
      stage.append(img);
    } else if (k === 'video') {
      stage.append(h('video', { class: 'preview-media', src: url, controls: true, autoplay: true, playsinline: true }));
    } else if (k === 'audio') {
      stage.append(h('div', { class: 'preview-audio' }, h('div', { class: 'big-icon', 'aria-hidden': 'true', text: '\u{1F3B5}' }), h('audio', { src: url, controls: true, autoplay: true })));
    } else if (k === 'pdf') {
      stage.append(h('iframe', { class: 'preview-pdf', src: url, title: item.name }));
    } else if (k === 'markdown' || k === 'text') {
      const box = h('div', { class: 'preview-text' });
      stage.append(box);
      textInto(box, item);
    } else {
      stage.append(h('div', { class: 'preview-msg' }, h('p', { text: t('preview.none') }), h('p', { class: 'muted', text: fmtSize(item.size) })));
    }
    prev.hidden = next.hidden = images.length < 2;
  }

  onKeyHandler = (e) => {
    if (document.querySelector('.modal-backdrop')) return;
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); closePreview(); }
    else if (e.key === 'ArrowLeft') step(-1);
    else if (e.key === 'ArrowRight') step(1);
  };
  document.addEventListener('keydown', onKeyHandler, true);
  document.body.append(overlay);
  show(it);
}
