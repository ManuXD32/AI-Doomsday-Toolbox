package com.example.llamadroid.ui.walkthrough

import com.example.llamadroid.screenshots.catalog.GuideShotCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every preview key the guides can show must have a screenshot spec in [GuideShotCatalog] (the opt-in renderer
 * `GuideShotTest` produces the drawables), and every catalog key must have its drawable. Cheap: reads the
 * source of the key maps, no Robolectric.
 */
class GuideShotCoverageTest {
    private fun source(name: String): String {
        val candidates = listOf(
            File("src/main/java/com/example/llamadroid/ui/walkthrough/$name"),
            File("app/src/main/java/com/example/llamadroid/ui/walkthrough/$name")
        )
        return checkNotNull(candidates.firstOrNull { it.isFile }) { "Cannot find $name from ${File(".").absolutePath}" }.readText()
    }

    private val keyPattern = Regex("""^\s*"([a-z0-9_]+)"\s*->""", RegexOption.MULTILINE)

    /** Preview keys whose drawable is a values alias of another catalog key's drawable (no file of their own). */
    private val aliasedKeys = mapOf("native_llama" to "conversations_native_management")

    @Test fun everyLessonPreviewKeyHasAShotSpec() {
        val keys = keyPattern.findAll(source("WalkthroughPreviews.kt")).map { it.groupValues[1] }.toSet()
        assertTrue("expected at least 55 mapped preview keys, found ${keys.size}", keys.size >= 55)
        val missing = keys - GuideShotCatalog.keys.toSet() - aliasedKeys.keys
        assertTrue("Preview keys without a GuideShotCatalog spec: $missing", missing.isEmpty())
    }

    @Test fun aliasedKeysPointAtCatalogDrawables() {
        val values = File("src/main/res/values/walkthrough.xml").takeIf { it.isFile } ?: File("app/src/main/res/values/walkthrough.xml")
        val xml = values.readText()
        aliasedKeys.forEach { (key, target) ->
            assertTrue("$target must be a catalog key", target in GuideShotCatalog.keys)
            val alias = """name="tour_lesson_$key" type="drawable">@drawable/${GuideShotCatalog.drawableName(target)}<"""
            assertTrue("walkthrough.xml must alias tour_lesson_$key to ${GuideShotCatalog.drawableName(target)}", alias in xml)
        }
    }

    @Test fun fallbackPreviewsHaveShotSpecs() {
        val body = source("WalkthroughUi.kt").substringAfter("internal fun previewResource").substringBefore("\n}\n")
        val keys = keyPattern.findAll(body).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("tools", "library", "create", "tama", "farm"), keys)
        val catalog = GuideShotCatalog.keys.toSet()
        // "library" is also a lesson key; the rest use the preview_ prefix, and "home" is the else branch.
        assertTrue("library" in catalog)
        listOf("home", "tools", "create", "tama", "farm").forEach { assertTrue("preview_$it" in catalog) }
    }

    @Test fun everyCatalogKeyHasItsDrawable() {
        val drawables = Class.forName("com.example.llamadroid.R\$drawable")
        GuideShotCatalog.keys.forEach { key ->
            val name = GuideShotCatalog.drawableName(key)
            assertNotNull("Missing drawable $name for guide shot '$key'", runCatching { drawables.getField(name) }.getOrNull())
        }
    }

    @Test fun recipePreviewOverridesPointAtCatalogKeys() {
        assertTrue(recipePreviewOverrides.isNotEmpty())
        recipePreviewOverrides.forEach { (recipe, key) ->
            assertTrue("Override $recipe -> $key is not a catalog key", key in GuideShotCatalog.keys)
            assertNotNull("Override $recipe -> $key has no drawable", lessonPreviewResource(key))
        }
    }

    @Test fun lessonKeysResolveToDrawables() {
        (GuideShotCatalog.keys + aliasedKeys.keys).filterNot { it.startsWith("preview_") }.forEach { key ->
            assertNotNull("lessonPreviewResource($key) is null", lessonPreviewResource(key))
        }
    }
}
