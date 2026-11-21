```html
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>html,body{margin:0;overflow:hidden;background:#222}#export-glb{position:fixed;right:12px;bottom:12px;z-index:5;padding:10px 14px}</style>
<script type="importmap">
{ "imports": {
  "three": "https://cdnjs.cloudflare.com/ajax/libs/three.js/0.160.0/three.module.min.js",
  "three/addons/": "https://unpkg.com/three@0.160.0/examples/jsm/" } }
</script>
</head>
<body>
<button id="export-glb">Export GLB</button>
<script type="module">
import * as THREE from 'three';
import { GLTFExporter } from 'three/addons/exporters/GLTFExporter.js';
window.__adtRevision = THREE.REVISION;
const scene = new THREE.Scene(); scene.background = new THREE.Color(0x303a46);
const camera = new THREE.PerspectiveCamera(50, innerWidth / innerHeight, 0.1, 100); camera.position.set(2.5, 2, 4); camera.lookAt(0, 0.5, 0);
const renderer = new THREE.WebGLRenderer({ antialias: true }); renderer.setSize(innerWidth, innerHeight); document.body.appendChild(renderer.domElement);
scene.add(new THREE.AmbientLight(0xffffff, 0.8));
const sun = new THREE.DirectionalLight(0xffffff, 1.2); sun.position.set(3, 5, 4); scene.add(sun);
const asset = new THREE.Group(); asset.name = 'Crate';
const box = new THREE.Mesh(new THREE.BoxGeometry(1, 1, 1), new THREE.MeshStandardMaterial({ color: 0xc0803a })); box.position.y = 0.5; asset.add(box);
const lid = new THREE.Mesh(new THREE.BoxGeometry(1.1, 0.1, 1.1), new THREE.MeshStandardMaterial({ color: 0x8a5a2a })); lid.position.y = 1.05; asset.add(lid);
scene.add(asset);
function loop() { requestAnimationFrame(loop); asset.rotation.y += 0.01; renderer.render(scene, camera); }
loop();
document.getElementById('export-glb').addEventListener('click', () => {
  new GLTFExporter().parse(scene, (glb) => {
    const blob = new Blob([glb], { type: 'model/gltf-binary' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob); a.download = 'crate.glb';
    a.click();
    URL.revokeObjectURL(a.href);
  }, (e) => console.error(e), { binary: true });
});
</script>
</body>
</html>
```
