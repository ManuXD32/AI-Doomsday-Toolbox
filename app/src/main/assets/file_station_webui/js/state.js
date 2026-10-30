// Shared state, event bus and small formatting helpers.
export const state = {
  session: null,
  volumes: [],
  mode: 'files', // files | trash | jobs | tool
  vol: null,
  path: '',
  readOnly: false,
  items: [], // items currently displayed in the file view ({vol, path, name, isDir, size, mtime, mime})
  query: '', // non-empty while showing search results
  view: 'list',
  sort: 'name',
  order: 'asc',
  jobs: [],
  caps: null,
  toolId: null,
};

const bus = new EventTarget();
export function on(name, fn) {
  const handler = (e) => fn(e.detail);
  bus.addEventListener(name, handler);
  return () => bus.removeEventListener(name, handler);
}
export function emit(name, detail) {
  bus.dispatchEvent(new CustomEvent(name, { detail }));
}

export const store = {
  get(k, d) {
    try { return localStorage.getItem(k) ?? d; } catch { return d; }
  },
  set(k, v) {
    try { localStorage.setItem(k, v); } catch { /* storage unavailable */ }
  },
};

export const encPath = (p) => (p ? p.split('/').map(encodeURIComponent).join('/') : '');
export const joinPath = (a, b) => (a ? a + '/' + b : b);
export const parentPath = (p) => { const i = p.lastIndexOf('/'); return i < 0 ? '' : p.slice(0, i); };
export const baseName = (p) => p.slice(p.lastIndexOf('/') + 1);
export const itemKey = (it) => it.vol + ':' + it.path;
export const ref = (it) => ({ vol: it.vol, path: it.path });

export function filesHash(vol, path) {
  if (!vol) return '#/files';
  return '#/files/' + encodeURIComponent(vol) + (path ? '/' + encPath(path) : '');
}

export function fmtSize(n) {
  if (n == null || n < 0) return '';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  let v = n;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return (i === 0 || v >= 100 ? Math.round(v) : v.toFixed(1)) + ' ' + u[i];
}

export function fmtDate(ms) {
  if (!ms) return '';
  try {
    return new Intl.DateTimeFormat(document.documentElement.lang || undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(ms));
  } catch { return new Date(ms).toLocaleString(); }
}

export function extOf(name) {
  const i = name.lastIndexOf('.');
  return i <= 0 ? '' : name.slice(i + 1).toLowerCase();
}

const EXT = {
  image: ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'avif', 'heic', 'ico'],
  video: ['mp4', 'webm', 'mkv', 'mov', 'm4v', 'ogv', '3gp', 'avi'],
  audio: ['mp3', 'wav', 'ogg', 'oga', 'opus', 'flac', 'm4a', 'aac'],
  pdf: ['pdf'],
  archive: ['zip', 'tar', 'tgz', 'gz', 'xz', 'txz', '7z', 'bz2'],
  markdown: ['md', 'markdown'],
  text: ['txt', 'log', 'json', 'csv', 'tsv', 'xml', 'html', 'htm', 'svg', 'js', 'css', 'yml', 'yaml', 'ini',
    'conf', 'py', 'kt', 'java', 'sh', 'c', 'h', 'cpp', 'toml', 'srt', 'rst', 'tex', 'sql', 'gradle', 'properties'],
};

export function kindOf(it) {
  if (it.isDir) return 'dir';
  const e = extOf(it.name);
  for (const [k, list] of Object.entries(EXT)) if (list.includes(e)) return k;
  const m = it.mime || '';
  if (m.startsWith('image/')) return 'image';
  if (m.startsWith('video/')) return 'video';
  if (m.startsWith('audio/')) return 'audio';
  if (m.startsWith('text/')) return 'text';
  return 'file';
}
export const isEditable = (it) => { const k = kindOf(it); return k === 'markdown' || k === 'text'; };

export const ICONS = {
  dir: '\u{1F4C1}', image: '\u{1F5BC}', video: '\u{1F39E}', audio: '\u{1F3B5}', pdf: '\u{1F4D5}',
  archive: '\u{1F5DC}', markdown: '\u{1F4DD}', text: '\u{1F4C4}', file: '\u{1F4C4}',
};
