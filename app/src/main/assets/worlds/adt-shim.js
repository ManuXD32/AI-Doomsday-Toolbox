/* ADT 3D world shim: scene capture, touch controls, spots, error panel.
 * Plain ES5, no modules. Loaded as the first script of every generated world page.
 * UI labels come from window.__adtStrings (injected by WorldPageBuilder from string resources). */
(function () {
  'use strict';
  var W = window, D = document;
  if (W.__adtShimLoaded) { return; }
  W.__adtShimLoaded = true;

  var adt = W.__adt = { scene: null, camera: null, renderer: null, renderCalls: 0, errors: [], T: null };
  var DEFAULTS = {
    joystick: 'Move', fly: 'Fly', walk: 'Walk', up: 'Up', down: 'Down', reset: 'Reset view',
    goTo: 'Places', hint: 'Drag anywhere to look around \u00b7 Use the stick to move', help: 'Controls help', noSpots: 'No named objects in this scene.',
    errorTitle: 'This world had a problem',
    truncatedHint: 'The code looks cut off. Ask the model to continue or regenerate it.',
    blankHint: 'Nothing was drawn. The code may be incomplete or never calls renderer.render(scene, camera).',
    closePanel: 'Close', touchControls: 'Touch controls', hideUi: 'Hide controls', showUi: 'Show controls', diagnostics: 'Diagnostics',
    threeMissing: 'three.js did not load. The page may need a library that is not bundled, or the code is incomplete.'
  };
  var DEFAULTS_ES = {
    joystick: 'Mover', fly: 'Volar', walk: 'Caminar', up: 'Subir', down: 'Bajar', reset: 'Restablecer vista', goTo: 'Lugares',
    hint: 'Arrastra para mirar alrededor \u00b7 Usa el joystick para moverte', help: 'Ayuda de controles',
    closePanel: 'Cerrar', touchControls: 'Controles t\u00e1ctiles', hideUi: 'Ocultar controles', showUi: 'Mostrar controles', diagnostics: 'Diagn\u00f3stico',
    threeMissing: 'three.js no se cargó. La página puede necesitar una librería que no está incluida, o el código está incompleto.'
  };
  function str(k) {
    try { var s = W.__adtStrings; if (s && typeof s[k] === 'string' && s[k]) { return s[k]; } } catch (e) {}
    try { if (DEFAULTS_ES[k] && /^es/i.test(W.navigator.language || '')) { return DEFAULTS_ES[k]; } } catch (e) {}
    return DEFAULTS[k];
  }
  function safe(fn) { return function () { try { return fn.apply(this, arguments); } catch (e) { logErr('shim: ' + e); } }; }
  function logErr(m) { try { if (W.console && W.console.debug) { W.console.debug('[adt] ' + m); } } catch (e) {} }

  // ---------------------------------------------------------------- controls state
  var ctl = {
    off: [0, 0, 0], qd: null, joyX: 0, joyY: 0, vert: 0, fly: false, glide: null,
    lastT: 0, speed: 0, visible: true, mainCam: null
  };
  var tmp = {};

  function initMath(T) {
    if (tmp.ready || !T.Vector3 || !T.Quaternion) { return; }
    ctl.qd = new T.Quaternion();
    tmp.v = new T.Vector3(); tmp.v2 = new T.Vector3(); tmp.q = new T.Quaternion(); tmp.q2 = new T.Quaternion();
    tmp.pos = new T.Vector3(); tmp.quat = new T.Quaternion(); tmp.box = new T.Box3();
    tmp.ready = true;
  }

  function sceneSpeed() {
    if (ctl.speed) { return ctl.speed; }
    var s = 6;
    try {
      if (adt.scene && adt.T) {
        var b = new adt.T.Box3().setFromObject(adt.scene);
        if (!b.isEmpty()) {
          var size = b.getSize(new adt.T.Vector3());
          s = Math.max(2, Math.min(200, Math.max(size.x, size.z) * 0.2));
        }
      }
    } catch (e) {}
    ctl.speed = s;
    return s;
  }

  function ease(t) { return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2; }

  // Applies the touch-control offset to the page camera right before the original render and
  // returns a function that restores the page's own pose right after it.
  function applyControls(camera) {
    var T = adt.T;
    if (!T || !tmp.ready || !camera || !camera.position || !camera.quaternion) { return null; }
    var now = (W.performance && performance.now) ? performance.now() : Date.now();
    var dt = ctl.lastT ? Math.min(0.1, (now - ctl.lastT) / 1000) : 0;
    ctl.lastT = now;

    if (ctl.glide) {
      var g = ctl.glide, k = Math.min(1, (now - g.t0) / g.dur), e = ease(k);
      for (var i = 0; i < 3; i++) { ctl.off[i] = g.from[i] + (g.to[i] - g.from[i]) * e; }
      if (k >= 1) { ctl.glide = null; }
    } else if (ctl.joyX || ctl.joyY || ctl.vert) {
      tmp.q.copy(camera.quaternion).premultiply(ctl.qd);
      tmp.v.set(0, 0, -1).applyQuaternion(tmp.q);
      tmp.v.y = 0;
      if (tmp.v.lengthSq() < 1e-4) { tmp.v.set(0, 1, 0).applyQuaternion(tmp.q); tmp.v.y = 0; }
      if (tmp.v.lengthSq() > 1e-6) {
        tmp.v.normalize();
        tmp.v2.set(-tmp.v.z, 0, tmp.v.x); // right vector in the XZ plane
        var sp = sceneSpeed() * dt;
        ctl.off[0] += (tmp.v.x * -ctl.joyY + tmp.v2.x * ctl.joyX) * sp;
        ctl.off[2] += (tmp.v.z * -ctl.joyY + tmp.v2.z * ctl.joyX) * sp;
      }
      if (ctl.fly && ctl.vert) { ctl.off[1] += ctl.vert * sceneSpeed() * 0.6 * dt; }
    }

    if (!ctl.off[0] && !ctl.off[1] && !ctl.off[2] && ctl.qd.x === 0 && ctl.qd.y === 0 && ctl.qd.z === 0) { return null; }
    tmp.pos.copy(camera.position);
    tmp.quat.copy(camera.quaternion);
    camera.position.set(tmp.pos.x + ctl.off[0], tmp.pos.y + ctl.off[1], tmp.pos.z + ctl.off[2]);
    camera.quaternion.premultiply(ctl.qd);
    return function () {
      camera.position.copy(tmp.pos);
      camera.quaternion.copy(tmp.quat);
    };
  }

  function look(dx, dy) {
    if (!tmp.ready || !adt.camera) { return; }
    ctl.glide = null;
    var kk = 0.005;
    tmp.q.setFromAxisAngle(tmp.v.set(0, 1, 0), -dx * kk);
    ctl.qd.premultiply(tmp.q);
    // pitch about the final local right axis, clamped away from the poles
    tmp.q2.copy(adt.camera.quaternion).premultiply(ctl.qd);
    tmp.v.set(1, 0, 0).applyQuaternion(tmp.q2);
    tmp.q.setFromAxisAngle(tmp.v, -dy * kk);
    tmp.v2.set(0, 0, -1).applyQuaternion(tmp.q2).applyQuaternion(tmp.q);
    if (Math.abs(tmp.v2.y) < 0.985) { ctl.qd.premultiply(tmp.q); }
    ctl.qd.normalize();
  }

  function resetControls() {
    ctl.off[0] = ctl.off[1] = ctl.off[2] = 0;
    ctl.glide = null;
    if (ctl.qd) { ctl.qd.set(0, 0, 0, 1); }
  }

  // ---------------------------------------------------------------- spots
  function collectSpots() {
    var out = [], seen = {};
    try {
      if (adt.scene && adt.scene.traverse) {
        adt.scene.traverse(function (o) {
          if (out.length >= 30 || !o || o.isLight || o.isCamera) { return; }
          var n = (typeof o.name === 'string') ? o.name.trim() : '';
          if (!n || seen[n]) { return; }
          seen[n] = true;
          out.push({ name: n, obj: o });
        });
      }
    } catch (e) { logErr(e); }
    return out;
  }

  function goTo(obj) {
    var T = adt.T, cam = adt.camera;
    if (!T || !tmp.ready || !cam || !obj) { return; }
    try {
      var box = new T.Box3().setFromObject(obj);
      if (box.isEmpty()) { return; }
      var c = box.getCenter(new T.Vector3()), r = Math.max(0.5, box.getSize(new T.Vector3()).length() / 2);
      var half = 0.45;
      if (cam.isPerspectiveCamera) {
        var vh = (cam.fov || 50) * Math.PI / 360, hh = Math.atan(Math.tan(vh) * (cam.aspect || 1));
        half = Math.max(0.1, Math.min(vh, hh));
      }
      var dist = Math.max(1, r / Math.sin(half) * 1.15);
      tmp.q.copy(cam.quaternion).premultiply(ctl.qd);
      var fwd = new T.Vector3(0, 0, -1).applyQuaternion(tmp.q);
      var tgt = c.clone().addScaledVector(fwd, -dist);
      ctl.glide = {
        t0: (W.performance && performance.now) ? performance.now() : Date.now(), dur: 650,
        from: [ctl.off[0], ctl.off[1], ctl.off[2]],
        to: [tgt.x - cam.position.x, tgt.y - cam.position.y, tgt.z - cam.position.z]
      };
    } catch (e) { logErr(e); }
  }

  // ---------------------------------------------------------------- patching three
  // Keeps the drawing size in sync with the window. Pages that start while the view is 0x0 (or never
  // handle resize) otherwise stay blank. Only acts for a canvas attached directly under body, or when
  // the camera aspect is NaN / 0, so pages that size to a container element are left alone.
  function fixSize() {
    try {
      var r = adt.renderer, cam = adt.camera;
      if (!r || !r.domElement) { return; }
      var w = W.innerWidth, h = W.innerHeight;
      if (!(w > 0 && h > 0)) { return; }
      var cv = r.domElement;
      var direct = cv.parentNode === D.body;
      if (direct && typeof r.getSize === 'function' && adt.T && adt.T.Vector2) {
        var sz = tmp.sz || (tmp.sz = new adt.T.Vector2());
        r.getSize(sz);
        if (Math.abs(sz.x - w) > 1 || Math.abs(sz.y - h) > 1) { r.setSize(w, h, true); }
      }
      if (cam && cam.isPerspectiveCamera) {
        var asp = cam.aspect;
        var bad = !isFinite(asp) || !(asp > 0);
        var want = w / h;
        if (!direct && bad) {
          var cw = cv.clientWidth, ch = cv.clientHeight;
          if (cw > 0 && ch > 0) { want = cw / ch; }
        }
        if (bad || (direct && Math.abs(asp - want) > 0.01)) { cam.aspect = want; cam.updateProjectionMatrix(); }
      }
    } catch (e) { logErr(e); }
  }
  W.addEventListener('resize', function () { try { W.requestAnimationFrame(fixSize); } catch (e) {} }, true);
  W.addEventListener('orientationchange', function () { try { W.requestAnimationFrame(fixSize); } catch (e) {} }, true);

  // WebGLRenderer#render is an instance property (set in the constructor), so the renderer class is
  // wrapped with a subclass whose constructor hooks the instance's render.
  function hookRender(inst) {
    var orig = inst.render;
    if (typeof orig !== 'function' || orig.__adtPatched) { return; }
    var patched = function (scene, camera) {
      var restore = null;
      try {
        if (D.hidden) { return; }
        if (!ctl.mainCam && camera && camera.isCamera) { ctl.mainCam = camera; }
        if (camera === ctl.mainCam) {
          adt.scene = scene; adt.camera = camera; adt.renderer = this; adt.renderCalls++;
          if (adt.renderCalls === 1) { ensureUI(); }
          fixSize();
          restore = applyControls(camera);
        }
      } catch (e) { logErr(e); restore = null; }
      try { return orig.apply(this, arguments); }
      finally { if (restore) { try { restore(); } catch (e2) {} } }
    };
    patched.__adtPatched = true;
    inst.render = patched;
  }

  function wrapRenderer(Cls) {
    adt.threeSeen = true;
    if (typeof Cls !== 'function' || Cls.__adtWrapped) { return Cls; }
    var Wrapped = class extends Cls {
      constructor() { super(...arguments); try { hookRender(this); } catch (e) { logErr(e); } }
    };
    Wrapped.__adtWrapped = true;
    return Wrapped;
  }

  // Registers the three namespace (math classes for the controls). Returns true when usable.
  function patchThree(T) {
    try {
      adt.threeSeen = true;
      if (!T || !T.Vector3 || !T.Quaternion) { return false; }
      adt.T = T; initMath(T);
      return true;
    } catch (e) { logErr(e); return false; }
  }
  W.__adtPatchThree = patchThree;
  W.__adtWrapRenderer = wrapRenderer;

  // legacy pages: THREE is assigned as a global by three.min.js (the UMD fills it afterwards)
  (function () {
    var value;
    function swap(ns) {
      try {
        if (ns && ns.WebGLRenderer && !ns.WebGLRenderer.__adtWrapped) { ns.WebGLRenderer = wrapRenderer(ns.WebGLRenderer); }
        patchThree(ns);
      } catch (e) { logErr(e); }
    }
    function trap(ns) {
      try {
        if (!ns || typeof ns !== 'object') { return; }
        if (ns.WebGLRenderer) { swap(ns); return; }
        var cls;
        Object.defineProperty(ns, 'WebGLRenderer', {
          configurable: true, enumerable: true,
          get: function () { return cls; },
          set: function (c) {
            cls = wrapRenderer(c);
            try { Object.defineProperty(ns, 'WebGLRenderer', { value: cls, writable: true, configurable: true, enumerable: true }); } catch (e) {}
            patchThree(ns);
          }
        });
      } catch (e) { logErr(e); }
    }
    try {
      var existing = W.THREE;
      Object.defineProperty(W, 'THREE', {
        configurable: true, enumerable: true,
        get: function () { return value; },
        set: function (v) { value = v; trap(v); }
      });
      if (existing) { value = existing; trap(existing); }
    } catch (e) { logErr(e); }
    // safety net when the UMD defines properties some other way
    var tries = 0, iv = setInterval(function () {
      tries++;
      try { if (value) { swap(value); } if ((value && value.WebGLRenderer && value.WebGLRenderer.__adtWrapped) || tries > 100) { clearInterval(iv); } }
      catch (e) { clearInterval(iv); }
    }, 100);
  })();

  // ---------------------------------------------------------------- UI
  var ui = {};
  function el(tag, cls, text) {
    var n = D.createElement(tag);
    if (cls) { n.className = cls; }
    if (text != null) { n.textContent = text; }
    return n;
  }
  function when(fn) { if (D.body) { fn(); } else { D.addEventListener('DOMContentLoaded', safe(fn)); } }

  function injectStyle() {
    if (ui.style) { return; }
    var s = el('style');
    s.id = 'adt-style';
    s.textContent =
      'html,body{overscroll-behavior:none}canvas{touch-action:none}' +
      '.adt-hud{position:fixed;z-index:2147483000;font:13px/1.25 system-ui,-apple-system,sans-serif;color:#fff;' +
      '-webkit-user-select:none;user-select:none;touch-action:none;box-sizing:border-box}' +
      '.adt-hud *{box-sizing:border-box}' +
      '.adt-joy{left:16px;bottom:calc(28px + env(safe-area-inset-bottom,0px));width:120px;height:120px;border-radius:60px;' +
      'background:rgba(0,0,0,.35);border:2px solid rgba(255,255,255,.55)}' +
      '.adt-knob{position:absolute;left:36px;top:36px;width:44px;height:44px;border-radius:22px;background:rgba(255,255,255,.65)}' +
      '.adt-col{display:flex;flex-direction:column;gap:8px;align-items:flex-end}' +
      '.adt-top{right:12px;top:12px;gap:6px;padding:6px;border-radius:22px;background:rgba(0,0,0,.4);align-items:stretch}' +
      '.adt-top .adt-btn{max-width:56vw}' +
      '.adt-seg{display:flex;border-radius:22px;overflow:hidden;border:1px solid rgba(255,255,255,.55)}' +
      '.adt-seg .adt-btn{flex:1 1 0;border:0;border-radius:0;min-width:64px}' +
      '.adt-joylabel{position:absolute;left:0;right:0;top:-20px;text-align:center;font-size:12px;opacity:.9;pointer-events:none;' +
      'text-shadow:0 1px 2px #000}' +
      '.adt-vert{right:16px;bottom:calc(28px + env(safe-area-inset-bottom,0px))}.adt-vert .adt-btn{min-width:96px}' +
      '.adt-hint{left:12px;right:12px;bottom:calc(168px + env(safe-area-inset-bottom,0px));text-align:center;padding:10px 14px;' +
      'border-radius:14px;background:rgba(0,0,0,.65);border:1px solid rgba(255,255,255,.35);pointer-events:none;' +
      'transition:opacity .4s}.adt-hint.adt-gone{opacity:0}' +
      '.adt-btn{min-height:44px;min-width:44px;padding:0 14px;border-radius:22px;background:rgba(0,0,0,.6);' +
      'border:1px solid rgba(255,255,255,.55);color:#fff;font:inherit;max-width:60vw;overflow:hidden;' +
      'text-overflow:ellipsis;white-space:nowrap}.adt-btn.adt-on{background:rgba(40,110,200,.85)}' +
      '.adt-panel{left:12px;right:12px;top:56px;max-height:68vh;overflow-y:auto;-webkit-overflow-scrolling:touch;' +
      'background:rgba(18,18,24,.95);border:1px solid rgba(255,255,255,.35);border-radius:12px;padding:12px;touch-action:pan-y}' +
      '.adt-panel.adt-err{border-color:#ff6b6b}.adt-panel h3{margin:0 0 8px;font-size:15px}' +
      '.adt-panel p{margin:0 0 8px;color:#ddd}.adt-msg{font:12px/1.35 monospace;white-space:pre-wrap;word-break:break-word;' +
      'margin:0 0 6px;color:#ffb4b4}' +
      '.adt-eye{position:fixed;z-index:2147483000;left:12px;top:12px;width:44px;height:44px;min-width:44px;padding:0;font-size:20px;line-height:1;' +
      'text-align:center;border-radius:22px;background:rgba(0,0,0,.45);border:1px solid rgba(255,255,255,.55);color:#fff}' +
      '.adt-pill{position:fixed;z-index:2147483000;left:50%;bottom:calc(16px + env(safe-area-inset-bottom,0px));transform:translateX(-50%);' +
      'display:none;min-height:44px;padding:0 18px;border-radius:22px;background:rgba(0,0,0,.4);border:1px solid rgba(255,255,255,.4);' +
      'color:#fff;font:13px system-ui,sans-serif;white-space:nowrap}' +
      '.adt-hidden .adt-hud,.adt-hidden .adt-eye{display:none!important}.adt-hidden .adt-pill{display:block}' +
      '.adt-item{display:block;width:100%;max-width:none;text-align:left;margin:0 0 6px;border-radius:8px}';
    (D.head || D.documentElement).appendChild(s);
    ui.style = s;
  }

  function button(cls, text, onTap) {
    var b = el('button', 'adt-btn ' + (cls || ''), text);
    b.type = 'button';
    b.addEventListener('click', safe(function (ev) { ev.stopPropagation(); onTap(b); }));
    return b;
  }

  // ADTHost is the Android bridge (absent in a plain browser): saveFile, openDiagnostics, setChromeHidden.
  function host() { try { return W.ADTHost || null; } catch (e) { return null; } }
  function setChromeHidden(hide) {
    try { D.documentElement.classList.toggle('adt-hidden', !!hide); } catch (e) {}
    try { var h = host(); if (h && h.setChromeHidden) { h.setChromeHidden(!!hide); } } catch (e) {}
  }

  function closePanels() {
    if (ui.spots && ui.spots.parentNode) { ui.spots.parentNode.removeChild(ui.spots); }
    ui.spots = null;
  }

  function hideHint() {
    var h = ui.hint;
    if (!h) { return; }
    h.classList.add('adt-gone');
    setTimeout(function () { if (h.parentNode) { h.parentNode.removeChild(h); } }, 450);
    ui.hint = null;
  }

  function showHelp() {
    closePanels();
    hideHint();
    var p = el('div', 'adt-hud adt-panel');
    p.id = 'adt-help';
    p.appendChild(el('h3', null, str('help')));
    if (!W.__adtPlainHtml) { p.appendChild(el('p', null, str('hint'))); }
    var h = host();
    if (h && h.openDiagnostics) { p.appendChild(button('adt-item', str('diagnostics'), function () { closePanels(); h.openDiagnostics(); })); }
    p.appendChild(button('adt-item', str('closePanel'), closePanels));
    D.body.appendChild(p);
    ui.spots = p;
  }

  function showSpots() {
    closePanels();
    var p = el('div', 'adt-hud adt-panel');
    var spots = collectSpots();
    if (!spots.length) { p.appendChild(el('p', null, str('noSpots'))); }
    spots.forEach(function (s) {
      p.appendChild(button('adt-item', s.name, function () { goTo(s.obj); closePanels(); }));
    });
    p.appendChild(button('adt-item', str('closePanel'), closePanels));
    D.body.appendChild(p);
    ui.spots = p;
  }

  function buildJoystick() {
    var base = el('div', 'adt-hud adt-joy');
    base.setAttribute('aria-label', str('joystick'));
    var knob = el('div', 'adt-knob');
    base.appendChild(knob);
    base.appendChild(el('div', 'adt-joylabel', str('joystick')));
    var active = null, R = 38;
    function upd(ev) {
      var r = base.getBoundingClientRect();
      var x = ev.clientX - (r.left + r.width / 2), y = ev.clientY - (r.top + r.height / 2);
      var m = Math.sqrt(x * x + y * y);
      if (m > R) { x = x * R / m; y = y * R / m; }
      knob.style.transform = 'translate(' + x + 'px,' + y + 'px)';
      ctl.joyX = x / R; ctl.joyY = y / R; ctl.glide = null;
    }
    function end() { active = null; ctl.joyX = ctl.joyY = 0; knob.style.transform = ''; }
    base.addEventListener('pointerdown', safe(function (ev) {
      active = ev.pointerId; try { base.setPointerCapture(ev.pointerId); } catch (e) {}
      upd(ev); ev.preventDefault(); ev.stopPropagation();
    }));
    base.addEventListener('pointermove', safe(function (ev) { if (active === ev.pointerId) { upd(ev); ev.preventDefault(); } }));
    base.addEventListener('pointerup', safe(function (ev) { if (active === ev.pointerId) { end(); } }));
    base.addEventListener('pointercancel', safe(end));
    return base;
  }

  function holdButton(text, v) {
    var b = button('', text, function () {});
    var down = function (ev) { ctl.vert = v; ctl.glide = null; try { b.setPointerCapture(ev.pointerId); } catch (e) {} ev.preventDefault(); ev.stopPropagation(); };
    var up = function () { if (ctl.vert === v) { ctl.vert = 0; } };
    b.addEventListener('pointerdown', safe(down));
    b.addEventListener('pointerup', safe(up));
    b.addEventListener('pointercancel', safe(up));
    return b;
  }

  // Plain HTML pages (no 3D): only the eye, a "?" for diagnostics and the show-UI pill.
  function buildPlainUI(root) {
    var tg = el('div', 'adt-hud adt-col');
    tg.style.left = '60px'; tg.style.top = '12px';
    var helpBtn = button('', '?', function () { showHelp(); });
    helpBtn.setAttribute('aria-label', str('help'));
    tg.appendChild(helpBtn);
    root.appendChild(tg);
    var eye = button('adt-eye', '\uD83D\uDC41', function () { closePanels(); setChromeHidden(true); });
    eye.className = 'adt-eye'; eye.id = 'adt-eye';
    eye.setAttribute('aria-label', str('hideUi'));
    var pill = button('adt-pill', str('showUi'), function () { setChromeHidden(false); });
    pill.className = 'adt-pill'; pill.id = 'adt-pill';
    root.appendChild(eye); root.appendChild(pill);
    D.body.appendChild(root);
  }

  function ensureUI() {
    when(function () {
      if (ui.root || !D.body) { return; }
      injectStyle();
      var root = el('div'); root.id = 'adt-ui';
      ui.root = root;
      if (W.__adtPlainHtml) { buildPlainUI(root); return; }
      var overlay = el('div'); // joystick + controls, hidden behind the toggle for pages with own controls
      overlay.id = 'adt-overlay';
      ui.overlay = overlay;
      overlay.appendChild(buildJoystick());

      var top = el('div', 'adt-hud adt-col adt-top');
      var vert = el('div', 'adt-hud adt-col adt-vert');
      vert.style.display = 'none';
      var seg = el('div', 'adt-seg');
      seg.setAttribute('role', 'group');
      var walkBtn, flyBtn;
      function setMode(fly) {
        ctl.fly = fly;
        if (!fly) { ctl.vert = 0; }
        walkBtn.classList.toggle('adt-on', !fly); flyBtn.classList.toggle('adt-on', fly);
        walkBtn.setAttribute('aria-pressed', String(!fly)); flyBtn.setAttribute('aria-pressed', String(fly));
        vert.style.display = fly ? 'flex' : 'none';
      }
      walkBtn = button('adt-on', str('walk'), function () { setMode(false); });
      flyBtn = button('', str('fly'), function () { setMode(true); });
      walkBtn.setAttribute('aria-pressed', 'true'); flyBtn.setAttribute('aria-pressed', 'false');
      seg.appendChild(walkBtn); seg.appendChild(flyBtn);
      var spotsBtn = button('', str('goTo'), function () { if (ui.spots) { closePanels(); } else { showSpots(); } });
      spotsBtn.style.display = 'none'; // shown once the scene has named objects
      var resetBtn = button('', str('reset'), function () { resetControls(); });
      var helpBtn = button('', '?', function () { showHelp(); });
      helpBtn.setAttribute('aria-label', str('help'));
      vert.appendChild(holdButton('\u25B2 ' + str('up'), 1));
      vert.appendChild(holdButton('\u25BC ' + str('down'), -1));
      top.appendChild(seg); top.appendChild(spotsBtn); top.appendChild(resetBtn); top.appendChild(helpBtn);
      overlay.appendChild(top); overlay.appendChild(vert);
      ui.spotsBtn = spotsBtn;
      var hint = el('div', 'adt-hud adt-hint', str('hint'));
      hint.id = 'adt-hint';
      overlay.appendChild(hint);
      ui.hint = hint;
      var hintTimer = setTimeout(hideHint, 5000);
      W.addEventListener('pointerdown', function onFirst() { hideHint(); W.removeEventListener('pointerdown', onFirst, true); clearTimeout(hintTimer); }, true);
      var tick = setInterval(function () {
        try { var has = collectSpots().length > 0; spotsBtn.style.display = has ? '' : 'none'; if (has) { clearInterval(tick); } }
        catch (e) { clearInterval(tick); }
      }, 1500);
      root.appendChild(overlay);

      if (W.__adtPageControls) {
        ctl.visible = false;
        overlay.style.display = 'none';
        var tg = el('div', 'adt-hud adt-col');
        tg.style.left = '60px'; tg.style.top = '12px';
        tg.appendChild(button('', str('touchControls'), function (b) {
          ctl.visible = !ctl.visible;
          overlay.style.display = ctl.visible ? '' : 'none';
          b.classList.toggle('adt-on', ctl.visible);
          if (!ctl.visible) { ctl.joyX = ctl.joyY = ctl.vert = 0; closePanels(); }
        }));
        var tgHelp = button('', '?', function () { showHelp(); });
        tgHelp.setAttribute('aria-label', str('help'));
        tg.appendChild(tgHelp);
        root.appendChild(tg);
      }
      var eye = button('adt-eye', '\uD83D\uDC41', function () { closePanels(); setChromeHidden(true); });
      eye.className = 'adt-eye'; eye.id = 'adt-eye';
      eye.setAttribute('aria-label', str('hideUi'));
      var pill = button('adt-pill', str('showUi'), function () { setChromeHidden(false); });
      pill.className = 'adt-pill'; pill.id = 'adt-pill';
      root.appendChild(eye); root.appendChild(pill);
      D.body.appendChild(root);
    });
  }

  // one-finger drag on the canvas (or bare page) looks around; HUD and page widgets are untouched
  (function () {
    var id = null, lx = 0, ly = 0;
    function isSurface(t) {
      if (!t || !t.tagName) { return false; }
      var n = t.tagName;
      return n === 'CANVAS' || n === 'BODY' || n === 'HTML';
    }
    W.addEventListener('pointerdown', safe(function (ev) {
      if (!ctl.visible || !adt.renderCalls || id !== null || !isSurface(ev.target)) { return; }
      if (ev.pointerType === 'mouse' && ev.button !== 0) { return; }
      id = ev.pointerId; lx = ev.clientX; ly = ev.clientY;
    }), true);
    W.addEventListener('pointermove', safe(function (ev) {
      if (id !== ev.pointerId) { return; }
      look(ev.clientX - lx, ev.clientY - ly);
      lx = ev.clientX; ly = ev.clientY;
    }), true);
    var end = safe(function (ev) { if (id === ev.pointerId) { id = null; } });
    W.addEventListener('pointerup', end, true);
    W.addEventListener('pointercancel', end, true);
  })();

  // ---------------------------------------------------------------- error panel
  function showErrors() {
    when(function () {
      injectStyle();
      var old = D.getElementById('adt-errors');
      if (old && old.parentNode) { old.parentNode.removeChild(old); }
      var p = el('div', 'adt-hud adt-panel adt-err');
      p.id = 'adt-errors';
      p.appendChild(el('h3', null, str('errorTitle')));
      if (W.__adtTruncated) { p.appendChild(el('p', null, str('truncatedHint'))); }
      if (!adt.renderCalls && !W.__adtPlainHtml) { p.appendChild(el('p', null, str('blankHint'))); }
      adt.errors.forEach(function (m) { p.appendChild(el('div', 'adt-msg', m)); });
      p.appendChild(button('adt-item', str('closePanel'), function () { p.parentNode && p.parentNode.removeChild(p); ui.errorsClosed = true; }));
      D.body.appendChild(p);
    });
  }

  function addError(msg) {
    try {
      msg = String(msg || '').slice(0, 600);
      if (!msg || adt.errors.indexOf(msg) >= 0 || adt.errors.length >= 20) { return; }
      adt.errors.push(msg);
      if (!ui.errorsClosed) { showErrors(); }
    } catch (e) {}
  }

  W.addEventListener('error', function (ev) {
    try {
      var t = ev.target;
      if (t && t !== W && t.tagName) {
        addError('Failed to load ' + t.tagName.toLowerCase() + ': ' + (t.src || t.href || ''));
      } else {
        addError((ev.message || 'Script error') + (ev.lineno ? ' (line ' + ev.lineno + ')' : ''));
      }
    } catch (e) {}
  }, true);
  W.addEventListener('unhandledrejection', function (ev) {
    try { var r = ev.reason; addError(r && r.message ? r.message : String(r)); } catch (e) {}
  });
  try {
    var ce = W.console && W.console.error;
    if (ce) {
      W.console.error = function () {
        try { addError(Array.prototype.slice.call(arguments).join(' ')); } catch (e) {}
        return ce.apply(this, arguments);
      };
    }
  } catch (e) {}

  // the shim overlay must exist even when three never loads, so the user always sees controls + errors
  ensureUI();
  setTimeout(function () {
    if (W.__adtPlainHtml) { return; }
    if (!adt.threeSeen && !adt.renderCalls) { addError(str('threeMissing')); }
    if (!adt.renderCalls && !D.hidden && !ui.errorsClosed) { showErrors(); }
  }, 4000);

  // ---------------------------------------------------------------- file downloads
  // WebView ignores <a download> for blob:/data: URLs, so hand the bytes to the app (ADTHost.saveFile).
  var blobs = {};
  try {
    var cou = W.URL && W.URL.createObjectURL;
    if (cou) { W.URL.createObjectURL = function (o) { var u = cou.apply(this, arguments); try { if (o && o.size != null) { blobs[u] = o; } } catch (e) {} return u; }; }
  } catch (e) {}
  function toB64(buf) {
    var u = new Uint8Array(buf), out = '', CH = 24576; // multiple of 3 so chunks concatenate cleanly
    for (var i = 0; i < u.length; i += CH) { out += W.btoa(String.fromCharCode.apply(null, u.subarray(i, i + CH))); }
    return out;
  }
  function wantsHostSave(a) {
    var h = host();
    return !!(h && h.saveFile && a && a.hasAttribute && a.hasAttribute('download') && /^(blob|data):/i.test(a.href || ''));
  }
  function hostSave(a) {
    var href = a.href, name = a.getAttribute('download') || 'download', h = host();
    var get = blobs[href] ? Promise.resolve(blobs[href]) : W.fetch(href).then(function (r) { return r.blob(); });
    get.then(function (b) {
      return b.arrayBuffer().then(function (buf) { h.saveFile(name, b.type || '', toB64(buf)); });
    }).catch(function (e) { logErr('download: ' + e); });
  }
  try {
    var origClick = W.HTMLAnchorElement.prototype.click;
    W.HTMLAnchorElement.prototype.click = function () {
      if (wantsHostSave(this)) { hostSave(this); return; }
      return origClick.apply(this, arguments);
    };
  } catch (e) {}
  D.addEventListener('click', function (ev) {
    try {
      var a = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
      if (wantsHostSave(a)) { ev.preventDefault(); ev.stopPropagation(); hostSave(a); }
    } catch (e) {}
  }, true);

  // test / debug handle
  adt.api = { goToName: function (n) { var s = collectSpots(); for (var i = 0; i < s.length; i++) { if (s[i].name === n) { goTo(s[i].obj); return true; } } return false; },
              reset: resetControls, spots: function () { return collectSpots().map(function (s) { return s.name; }); },
              offset: function () { return ctl.off.slice(); } };
})();
