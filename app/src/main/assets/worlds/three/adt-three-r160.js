// ADT wrapper around the bundled three.js r160 module build. Every `import 'three'`
// (import map) and every rewritten CDN module URL resolves here so all pages and addons
// share one set of classes. WebGLRenderer is re-exported as a subclass that lets adt-shim.js
// capture the scene/camera and apply touch controls (render is an instance property, so it
// cannot be patched on the prototype).
import * as T from 'https://worlds.adt.local/three/r160/three.module.min.js';
export * from 'https://worlds.adt.local/three/r160/three.module.min.js';
try { if (window.__adtPatchThree) window.__adtPatchThree(T); } catch (e) {}
const Wrapped = (window.__adtWrapRenderer ? window.__adtWrapRenderer(T.WebGLRenderer) : T.WebGLRenderer);
export { Wrapped as WebGLRenderer };
