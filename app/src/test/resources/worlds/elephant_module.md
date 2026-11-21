Here is a voxel elephant enclosure.

```html
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>Elephant Enclosure</title>
<style>html,body{margin:0;overflow:hidden;background:#87ceeb}</style>
</head>
<body>
<script type="module">
import * as THREE from 'three';

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x9fd8f5);
scene.fog = new THREE.Fog(0x9fd8f5, 40, 110);
const camera = new THREE.PerspectiveCamera(60, innerWidth / innerHeight, 0.1, 300);
camera.position.set(0, 9, 26);
camera.lookAt(0, 3, 0);
const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setSize(innerWidth, innerHeight);
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
document.body.appendChild(renderer.domElement);

scene.add(new THREE.HemisphereLight(0xffffff, 0x4a6b3a, 0.9));
const sun = new THREE.DirectionalLight(0xfff2cc, 1.2);
sun.position.set(20, 30, 10);
scene.add(sun);

const box = new THREE.BoxGeometry(1, 1, 1);
const mats = {};
function mat(c) { return mats[c] || (mats[c] = new THREE.MeshLambertMaterial({ color: c })); }
function voxel(parent, x, y, z, c, sx = 1, sy = 1, sz = 1) {
  const m = new THREE.Mesh(box, mat(c));
  m.position.set(x, y, z);
  m.scale.set(sx, sy, sz);
  parent.add(m);
  return m;
}

// ground (checker grass) with a pond cut-out
const ground = new THREE.Group();
ground.name = 'Savanna Ground';
for (let x = -20; x <= 20; x++) {
  for (let z = -20; z <= 20; z++) {
    const inPond = (x + 9) ** 2 + (z - 6) ** 2 < 25;
    if (inPond) continue;
    voxel(ground, x, -0.5, z, (x + z) % 2 ? 0x5a8f3c : 0x66a044);
  }
}
scene.add(ground);

const pond = new THREE.Group();
pond.name = 'Water Pond';
for (let x = -14; x <= -4; x++) {
  for (let z = 1; z <= 11; z++) {
    if ((x + 9) ** 2 + (z - 6) ** 2 < 25) voxel(pond, x, -0.8, z, 0x3a86d4);
  }
}
scene.add(pond);

// elephant built from blocks
const elephant = new THREE.Group();
elephant.name = 'Elephant';
const grey = 0x8d8f96, dark = 0x6f7178;
voxel(elephant, 0, 3.2, 0, grey, 6, 4, 4);          // body
voxel(elephant, 3.8, 4.2, 0, grey, 3, 3, 3);        // head
for (const dz of [-1.4, 1.4]) {
  voxel(elephant, 3.9, 4.4, dz * 1.6, dark, 0.6, 3, 2.4); // ears
  voxel(elephant, 5.4, 2.8, dz * 0.6, 0xf5efe0, 1.6, 0.4, 0.4); // tusks
  for (const dx of [-2, 2]) voxel(elephant, dx, 0.9, dz, grey, 1.4, 2.4, 1.4); // legs
}
for (let i = 0; i < 5; i++) voxel(elephant, 5.2 + i * 0.15, 3.6 - i * 0.7, 0, dark, 0.9, 0.9, 0.9); // trunk
voxel(elephant, -3.2, 3.6, 0, dark, 0.6, 2.4, 0.6); // tail
voxel(elephant, 4.9, 5.0, 0.9, 0x111111, 0.3, 0.3, 0.3);
voxel(elephant, 4.9, 5.0, -0.9, 0x111111, 0.3, 0.3, 0.3);
elephant.position.set(2, 0, -1);
scene.add(elephant);

// fence loop
const fence = new THREE.Group();
fence.name = 'Enclosure Fence';
for (let i = -20; i <= 20; i += 2) {
  for (const [x, z] of [[i, -20], [i, 20], [-20, i], [20, i]]) {
    voxel(fence, x, 1, z, 0x8a5a2b, 0.5, 3, 0.5);
  }
}
for (let i = -20; i <= 20; i++) {
  voxel(fence, i, 1.6, -20, 0xa06b36, 1, 0.3, 0.3);
  voxel(fence, i, 1.6, 20, 0xa06b36, 1, 0.3, 0.3);
  voxel(fence, -20, 1.6, i, 0xa06b36, 0.3, 0.3, 1);
  voxel(fence, 20, 1.6, i, 0xa06b36, 0.3, 0.3, 1);
}
scene.add(fence);

// trees
function tree(name, x, z) {
  const t = new THREE.Group();
  t.name = name;
  for (let y = 0; y < 5; y++) voxel(t, 0, y + 0.5, 0, 0x6b4423);
  for (let dx = -2; dx <= 2; dx++) for (let dz = -2; dz <= 2; dz++) for (let dy = 0; dy < 2; dy++) {
    if (Math.abs(dx) + Math.abs(dz) + dy < 4) voxel(t, dx, 5.5 + dy, dz, 0x2f7d32);
  }
  t.position.set(x, 0, z);
  scene.add(t);
}
tree('Acacia Tree West', -15, -12);
tree('Acacia Tree East', 15, -14);

// feeding station
const feeder = new THREE.Group();
feeder.name = 'Feeding Station';
voxel(feeder, 0, 0.6, 0, 0x7a5230, 5, 1.2, 2);
for (let i = 0; i < 6; i++) voxel(feeder, -2 + i * 0.8, 1.5, 0, 0xd9a23c, 0.7, 0.7, 0.7);
feeder.position.set(12, 0, 8);
scene.add(feeder);

addEventListener('resize', () => {
  camera.aspect = innerWidth / innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(innerWidth, innerHeight);
});

function animate(t) {
  requestAnimationFrame(animate);
  elephant.position.y = Math.sin(t / 700) * 0.08;
  renderer.render(scene, camera);
}
requestAnimationFrame(animate);
</script>
</body>
</html>
```
