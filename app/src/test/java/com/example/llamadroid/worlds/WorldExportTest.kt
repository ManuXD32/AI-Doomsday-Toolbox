package com.example.llamadroid.worlds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldExportTest {
    private fun resource(name: String) = javaClass.getResource("/worlds/$name")!!.readText()

    @Test
    fun htmlBlockIsExportedAsWritten() {
        val md = resource("elephant_module.md")
        val source = WorldPageBuilder.detect(md)!!
        val out = WorldExport.fromMarkdown(md)!!
        // The fixture imports bare 'three' without a map, so export adds a CDN import map and
        // otherwise keeps the model's page exactly as written.
        assertTrue(out.contains("\"three\":\"https://unpkg.com/three@0.170.0/build/three.module.js\""))
        assertEquals(source.html, out.replace(Regex("<script type=\"importmap\">[^<]*</script>\n?"), ""))
        assertFalse(out.contains("adt-shim"))
        assertFalse(out.contains("worlds.adt.local"))
    }

    @Test
    fun jsOnlySourceGetsImportMapAndScript() {
        val md = "```js\nimport * as THREE from 'three';\nconst s = new THREE.Scene();\n```"
        val out = WorldExport.fromMarkdown(md)!!
        assertTrue(out.contains("https://unpkg.com/three@0.170.0/build/three.module.js"))
        assertTrue(out.contains("\"three/addons/\":\"https://unpkg.com/three@0.170.0/examples/jsm/\""))
        assertTrue(out.contains("<script type=\"module\">"))
        assertTrue(out.contains("new THREE.Scene()"))
        assertTrue(out.startsWith("<!DOCTYPE html>"))
    }

    @Test
    fun legacyThreeJsGetsModuleImportPrefix() {
        val out = WorldExport.toHtml(WorldSource(null, listOf("const s = new THREE.Scene();"), emptyList()))
        assertTrue(out.contains("import * as THREE from 'three';"))
    }

    @Test
    fun scriptTerminatorsAreEscaped() {
        val out = WorldExport.toHtml(WorldSource(null, listOf("const a = '</script>'; THREE.x"), emptyList()))
        assertFalse(out.contains("'</script>'"))
    }

    @Test
    fun htmlWithBareImportButNoMapGetsMap() {
        val html = "<html><head></head><body><script type=\"module\">import * as THREE from 'three';</script></body></html>"
        val out = WorldExport.toHtml(WorldSource(html, emptyList(), emptyList()))
        assertTrue(out.contains("importmap"))
        val withMap = WorldExport.toHtml(WorldSource(out, emptyList(), emptyList()))
        assertEquals(out, withMap)
    }

    @Test
    fun noWorldReturnsNull() {
        assertNull(WorldExport.fromMarkdown("just text"))
    }

    @Test
    fun fileNameIsSanitized() {
        assertEquals("My world.html", WorldExport.suggestedFileName("My world"))
        assertEquals("a b c.html", WorldExport.suggestedFileName("a/b:c"))
        assertEquals("3d-world.html", WorldExport.suggestedFileName("  ..  "))
        assertEquals("3d-world.html", WorldExport.suggestedFileName(""))
        assertNotNull(WorldExport.suggestedFileName("x".repeat(500)))
        assertEquals(85, WorldExport.suggestedFileName("x".repeat(500)).length)
    }
}
