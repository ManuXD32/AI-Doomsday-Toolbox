// fetch wrapper: CSRF header on mutations, uniform error objects, URL builders.
import { emit, encPath } from './state.js';
import { t } from './i18n.js';

export class ApiError extends Error {
  constructor(status, code, message, body, retryAfter) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.body = body;
    this.retryAfter = retryAfter || 0;
  }
}

export function qs(params) {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params || {})) {
    if (v === undefined || v === null) continue;
    if (Array.isArray(v)) v.forEach((x) => u.append(k, String(x)));
    else u.set(k, String(v));
  }
  const s = u.toString();
  return s ? '?' + s : '';
}

export async function toApiError(res) {
  let code = 'HTTP';
  let msg = res.statusText;
  let data = null;
  try {
    data = await res.json();
    if (data && data.error) { code = data.error.code || code; msg = data.error.message || msg; }
  } catch { /* non-JSON error body */ }
  return new ApiError(res.status, code, msg, data, Number(res.headers.get('Retry-After')) || 0);
}

export async function request(method, url, { json, body, headers = {}, signal } = {}) {
  const h = { ...headers };
  if (method !== 'GET' && method !== 'HEAD') h['X-FileStation'] = '1';
  let payload = body;
  if (json !== undefined) { h['Content-Type'] = 'application/json'; payload = JSON.stringify(json); }
  let res;
  try {
    res = await fetch(url, { method, headers: h, body: payload, signal, credentials: 'same-origin' });
  } catch (e) {
    if (e && e.name === 'AbortError') throw e;
    throw new ApiError(0, 'NETWORK', t('err.NETWORK'));
  }
  if (!res.ok) {
    const err = await toApiError(res);
    if (res.status === 401 && !url.startsWith('/api/login')) emit('unauthorized');
    throw err;
  }
  return res;
}

export async function json(method, url, opts) {
  const r = await request(method, url, opts);
  if (r.status === 204) return {};
  return (r.headers.get('content-type') || '').includes('json') ? r.json() : {};
}

export const get = (url, params) => json('GET', url + qs(params));
export const post = (url, body) => json('POST', url, { json: body ?? {} });

export function errorMessage(e) {
  if (!e) return '';
  if (e instanceof ApiError) {
    const k = 'err.' + e.code;
    const m = t(k);
    return m !== k ? m : (e.message || t('err.HTTP'));
  }
  return e.message || String(e);
}

export const rawUrl = (vol, path, download) => '/raw/' + encodeURIComponent(vol) + '/' + encPath(path) + (download ? '?download=1' : '');
export const thumbUrl = (vol, path, size = 256) => '/thumb/' + encodeURIComponent(vol) + '/' + encPath(path) + '?size=' + size;
export const zipUrl = (vol, paths) => '/api/zip' + qs({ vol, path: paths });
export const editorUrl = (vol, path) => '/editor.html' + qs({ vol, path });

export function downloadUrl(url) {
  const a = document.createElement('a');
  a.href = url;
  a.download = '';
  a.rel = 'noopener';
  document.body.append(a);
  a.click();
  a.remove();
}
