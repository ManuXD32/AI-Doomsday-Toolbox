package com.example.llamadroid.worlds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorldPageBuilderTest {
    private val fence = "```"

    private fun fixture(name: String): String {
        val url = checkNotNull(javaClass.classLoader?.getResource("worlds/$name")) { "missing fixture $name" }
        return File(url.toURI()).readText()
    }

    @Test
    fun plainHtmlWithoutThreeIsAPlainPageNotAWorld() {
        val md = "$fence" + "html\n<html><body><h1>Hi</h1><script>alert(1)</script></body></html>\n$fence"
        val plain = WorldPageBuilder.detect(md)
        assertNotNull(plain)
        assertFalse(plain!!.isThree)
        val page = WorldPageBuilder.build(plain)
        assertTrue(page.contains("window.__adtPlainHtml=true"))
        assertFalse(page.contains("__adtPageControls"))
        assertTrue(WorldPageBuilder.detect(fixture("elephant_module.md"))!!.isThree)
        assertFalse(WorldPageBuilder.build(WorldPageBuilder.detect(fixture("elephant_module.md"))!!).contains("__adtPlainHtml"))
        // fragments and non-DOM scripts are still not runnable pages
        assertNull(WorldPageBuilder.detect("$fence" + "html\n<p>hello</p>\n$fence"))
        assertNull(WorldPageBuilder.detect("$fence" + "js\nconst x = 1 + 2;\n$fence"))
        val domJs = WorldPageBuilder.detect("$fence" + "js\ndocument.body.append('x');\n$fence")
        assertFalse(domJs!!.isThree)
        assertTrue(WorldPageBuilder.build(domJs).contains("<script>document.body"))
        assertNull(WorldPageBuilder.detect("just prose about three apples"))
        assertNull(WorldPageBuilder.detect("$fence" + "kotlin\nval x = THREE.value\n$fence"))
    }

    @Test
    fun detectsModuleHtmlBlock() {
        val source = WorldPageBuilder.detect(fixture("elephant_module.md"))
        assertNotNull(source)
        assertTrue(source!!.html!!.contains("<title>Elephant Enclosure</title>"))
        assertFalse(source.truncated)
    }

    @Test
    fun loneJsBlockBecomesModulePage() {
        val md = "$fence" + "js\nimport * as THREE from 'three';\nconst r = new THREE.WebGLRenderer({canvas: document.getElementById('c')});\n$fence"
        val source = WorldPageBuilder.detect(md)!!
        assertNull(source.html)
        val page = WorldPageBuilder.build(source)
        assertTrue(page.contains("""<script type="module">"""))
        assertTrue(page.contains("""<canvas id="c">"""))
    }

    @Test
    fun combinesHtmlShellWithSiblingJsAndCss() {
        val md = listOf(
            "$fence" + "html\n<html><head></head><body><div id=\"hud\"></div></body></html>\n$fence",
            "$fence" + "css\n#hud{color:red}\n$fence",
            "$fence" + "js\nconst s = new THREE.Scene();\n$fence",
        ).joinToString("\n")
        val source = WorldPageBuilder.detect(md)!!
        assertEquals(1, source.scripts.size)
        assertEquals(1, source.styles.size)
        val page = WorldPageBuilder.build(source)
        assertTrue(page.contains("#hud{color:red}"))
        assertTrue(page.contains("import * as THREE from 'three';"))
        assertTrue(page.indexOf("const s = new THREE.Scene()") > page.indexOf("<div id=\"hud\">"))
    }

    @Test
    fun unclosedFenceIsTruncatedAndStillBuilds() {
        val source = WorldPageBuilder.detect(fixture("truncated.md"))!!
        assertTrue(source.truncated)
        val page = WorldPageBuilder.build(source)
        assertTrue(page.contains("window.__adtTruncated=true"))
    }

    @Test
    fun buildInjectsShimViewportAndImportMap() {
        val page = WorldPageBuilder.build(WorldPageBuilder.detect(fixture("elephant_module.md"))!!)
        val head = page.indexOf("<head>")
        assertTrue(page.startsWith("<!DOCTYPE html>"))
        assertEquals(head + "<head>".length, page.indexOf("""<script src="/adt-shim.js"></script>"""))
        assertTrue(page.contains("""name="viewport""""))
        assertTrue(page.contains(""""three":"https://worlds.adt.local/three/adt-three.js""""))
        assertTrue(page.contains(""""three/addons/":"/three/r170/jsm/""""))
        assertFalse(page.contains("window.__adtPageControls"))
    }

    @Test
    fun r160PagesGetR160WrapperAndAddons() {
        val md = "$fence" + "html\n<html><head><script type=\"importmap\">{\"imports\":{\"three\":\"https://cdnjs.cloudflare.com/ajax/libs/three.js/0.160.0/three.module.min.js\"}}</script></head>" +
            "<body><script type=\"module\">import * as THREE from 'three';</script></body></html>\n$fence"
        val page = WorldPageBuilder.build(WorldPageBuilder.detect(md)!!)
        assertTrue(page.contains(""""three":"https://worlds.adt.local/three/adt-three-r160.js""""))
        assertTrue(page.contains(""""three/addons/":"/three/r160/jsm/""""))
    }

    @Test
    fun mergesModelImportMapAndKeepsOurThree() {
        val md = "$fence" + "html\n<html><head><script type=\"importmap\">{\"imports\":{\"three\":\"https://unpkg.com/three@0.150.0/build/three.module.js\",\"lil-gui\":\"https://cdn.jsdelivr.net/npm/lil-gui@0.19/+esm\"}}</script></head>" +
            "<body><script type=\"module\">import * as THREE from 'three'; import {OrbitControls} from 'three/addons/controls/OrbitControls.js';</script></body></html>\n$fence"
        val page = WorldPageBuilder.build(WorldPageBuilder.detect(md)!!)
        assertEquals(1, Regex("""type="importmap"""").findAll(page).count())
        assertTrue(page.contains(""""three":"https://worlds.adt.local/three/adt-three.js""""))
        assertFalse(page.contains("unpkg.com/three@0.150.0"))
        assertTrue(page.contains(""""lil-gui":"https://cdn.jsdelivr.net/npm/lil-gui@0.19/+esm""""))
        assertTrue(page.contains("window.__adtPageControls=true"))
    }

    @Test
    fun stringsAreEmbeddedAsEscapedJsonNotFormatted() {
        val page = WorldPageBuilder.build(
            WorldPageBuilder.detect(fixture("elephant_module.md"))!!,
            mapOf("goTo" to "100%; \"x\" </script><b>%s %d"),
        )
        assertTrue(page.contains("""\"x\" \u003c/script>"""))
        assertFalse(page.contains("</script><b>"))
        assertTrue(page.contains("100%;"))
    }

    @Test
    fun legacyGlobalWithoutLoaderGetsBundledThree() {
        val md = "$fence" + "html\n<html><head></head><body><script>const s = new THREE.Scene();</script></body></html>\n$fence"
        val page = WorldPageBuilder.build(WorldPageBuilder.detect(md)!!)
        assertTrue(page.contains("""<script src="/three/r147/three.min.js"></script>"""))
    }

    @Test
    fun rewriteUrlTable() {
        val table = mapOf(
            "https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js" to "/three/r147/three.min.js",
            "https://unpkg.com/three@0.128.0/build/three.js" to "/three/r147/three.min.js",
            "https://unpkg.com/three@0.160.0/build/three.module.js" to "/three/adt-three-r160.js",
            "https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.min.js" to "/three/adt-three-r160.js",
            "https://cdn.jsdelivr.net/gh/mrdoob/three.js@r128/build/three.min.js" to "/three/r147/three.min.js",
            "https://threejs.org/build/three.module.js" to "/three/adt-three.js",
            "https://unpkg.com/three@0.160.0/examples/jsm/controls/OrbitControls.js" to "/three/r160/jsm/controls/OrbitControls.js",
            "https://cdn.jsdelivr.net/npm/three@0.150.1/examples/jsm/loaders/GLTFLoader.js?x=1" to "/three/r170/jsm/loaders/GLTFLoader.js",
            "https://unpkg.com/three@0.160.0/addons/controls/PointerLockControls.js" to "/three/r160/jsm/controls/PointerLockControls.js",
            "https://cdn.jsdelivr.net/npm/three@0.147.0/examples/js/controls/OrbitControls.js" to "/three/r147/js/controls/OrbitControls.js",
            "https://threejs.org/examples/fonts/helvetiker_regular.typeface.json" to "/three/fonts/helvetiker_regular.typeface.json",
            "https://esm.sh/three@0.160.0" to "/three/adt-three-r160.js",
            "https://esm.sh/three@0.160.0/es2022/three.mjs" to "/three/adt-three-r160.js",
            "https://esm.sh/three@0.160.0/examples/jsm/controls/OrbitControls.js" to "/three/r160/jsm/controls/OrbitControls.js",
            "https://cdnjs.cloudflare.com/ajax/libs/three.js/0.160.0/three.module.min.js" to "/three/adt-three-r160.js",
            "https://cdnjs.cloudflare.com/ajax/libs/three.js/0.170.0/three.module.min.js" to "/three/adt-three.js",
            "https://unpkg.com/three@0.160.0/examples/jsm/exporters/GLTFExporter.js" to "/three/r160/jsm/exporters/GLTFExporter.js",
            "https://unpkg.com/three@0.170.0/examples/jsm/exporters/GLTFExporter.js" to "/three/r170/jsm/exporters/GLTFExporter.js",
            "https://cdn.skypack.dev/three@0.128.0" to "/three/adt-three.js",
            "https://cdn.skypack.dev/three@0.128.0/examples/jsm/controls/OrbitControls.js" to "/three/r170/jsm/controls/OrbitControls.js",
        )
        table.forEach { (url, expected) -> assertEquals(url, expected, WorldPageBuilder.rewriteUrl(url)) }
    }

    @Test
    fun rewriteUrlRejectsOtherUrls() {
        listOf(
            "https://example.com/three.min.js",
            "https://unpkg.com/lodash@4/lodash.js",
            "https://cdn.jsdelivr.net/npm/some-lib/examples/jsm/x.js",
            "https://evil.test/three@0.160.0/build/three.module.js",
            "file:///etc/passwd",
            "not a url",
        ).forEach { assertNull(it, WorldPageBuilder.rewriteUrl(it)) }
    }

    @Test
    fun builderSourceNeverUsesFormat() {
        val src = File("src/main/java/com/example/llamadroid/worlds/WorldPageBuilder.kt")
        if (src.exists()) assertFalse(src.readText().contains(".format("))
    }
}
