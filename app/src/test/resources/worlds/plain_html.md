Here is a small counter page.

```html
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>Counter</title>
<style>body{font-family:sans-serif;background:#f4f1ea;text-align:center;padding-top:30vh}
button{font-size:24px;padding:12px 24px}</style></head>
<body>
<h1 id="n">0</h1>
<button id="inc" onclick="inc()">Add one</button>
<script>
let n = 0;
function inc() { n++; document.getElementById('n').textContent = n; }
</script>
</body>
</html>
```
