```html
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>Spinning cubes</title>
<style>body{margin:0;overflow:hidden}</style>
<script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/three@0.147.0/examples/js/controls/OrbitControls.js"></script>
</head>
<body>
<script>
const scene = new THREE.Scene();
scene.background = new THREE.Color(0x203040);
const camera = new THREE.PerspectiveCamera(60, innerWidth / innerHeight, 0.1, 100);
camera.position.set(6, 5, 8);
const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setSize(innerWidth, innerHeight);
document.body.appendChild(renderer.domElement);
const controls = new THREE.OrbitControls(camera, renderer.domElement);
scene.add(new THREE.AmbientLight(0xffffff, 0.6));
const light = new THREE.DirectionalLight(0xffffff, 0.8);
light.position.set(3, 6, 4);
scene.add(light);
const cubes = [];
for (let i = 0; i < 5; i++) {
  const m = new THREE.Mesh(new THREE.BoxGeometry(1, 1, 1), new THREE.MeshLambertMaterial({ color: new THREE.Color().setHSL(i / 5, 0.7, 0.55) }));
  m.name = 'Cube ' + (i + 1);
  m.position.set(i * 1.6 - 3.2, 0, 0);
  scene.add(m);
  cubes.push(m);
}
function animate() {
  requestAnimationFrame(animate);
  cubes.forEach((c, i) => { c.rotation.y += 0.01 * (i + 1); });
  controls.update();
  renderer.render(scene, camera);
}
animate();
</script>
</body>
</html>
```
