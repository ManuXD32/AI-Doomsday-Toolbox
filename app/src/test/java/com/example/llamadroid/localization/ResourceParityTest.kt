package com.example.llamadroid.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import kotlin.io.path.createTempDirectory
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Keeps the default and Spanish value resource catalogs in sync.
 *
 * This deliberately reads source XML instead of compiled R values so it also catches a
 * translation that was accidentally placed in a file which is not part of the expected locale
 * directory. The scanner is limited to resource kinds whose values are translatable strings and
 * still parses every XML file in each values directory, including styles and colors.
 */
class ResourceParityTest {
    @Test
    fun `English and Spanish translatable resources have matching keys and formats`() {
        val english = ResourceCatalog.read(resourceDirectory("values"))
        val spanish = ResourceCatalog.read(resourceDirectory("values-es"))

        val missing = (english.keys - spanish.keys).sorted()
        val extra = (spanish.keys - english.keys).sorted()
        val kindMismatches = (english.keys intersect spanish.keys).asSequence()
            .filter { english.getValue(it).kind != spanish.getValue(it).kind }
            .sorted()
            .map { name ->
                "$name: ${english.getValue(name).kind} != ${spanish.getValue(name).kind}"
            }
            .toList()
        val formatMismatches = (english.keys intersect spanish.keys).asSequence()
            .filter {
                canonicalPlaceholders(english.getValue(it).placeholders) !=
                    canonicalPlaceholders(spanish.getValue(it).placeholders)
            }
            .sorted()
            .map { name ->
                "$name: ${canonicalPlaceholders(english.getValue(name).placeholders)} != " +
                    canonicalPlaceholders(spanish.getValue(name).placeholders)
            }
            .toList()

        val differences = buildString {
            if (missing.isNotEmpty()) append("missing=[${missing.joinToString()}]; ")
            if (extra.isNotEmpty()) append("extra=[${extra.joinToString()}]; ")
            if (kindMismatches.isNotEmpty()) {
                append("kindMismatches=[${kindMismatches.joinToString() { it }}]; ")
            }
            if (formatMismatches.isNotEmpty()) {
                append("formatMismatches=[${formatMismatches.joinToString() { it }}]; ")
            }
        }.trimEnd(';', ' ')

        assertTrue(
            "English/Spanish resource parity failed${if (differences.isEmpty()) "" else ": $differences"}",
            differences.isEmpty()
        )
    }

    @Test
    fun `placeholder parser ignores escaped percent sequences`() {
        assertTrue(
            ResourceCatalog.placeholders("Progress: 100%% · %1\$d files · %2\$.1f%%") ==
                listOf(
                    Placeholder(index = 1, type = 'd'),
                    Placeholder(index = 2, type = 'f')
                )
        )
    }

    @Test
    fun `placeholder comparison permits explicitly indexed locale reordering`() {
        assertEquals(
            canonicalPlaceholders(ResourceCatalog.placeholders("%1\$s has %2\$d items")),
            canonicalPlaceholders(ResourceCatalog.placeholders("%2\$d elementos de %1\$s"))
        )
    }

    @Test
    fun `resource parser ignores nontranslatable entries while scanning every XML file`() {
        val tempDirectory = createTempDirectory(prefix = "resource-parity").toFile()
        try {
            File(tempDirectory, "strings.xml").writeText(
                """
                <resources>
                    <string name="visible">Visible %1${'$'}s</string>
                    <string name="machine_value" translatable="false">machine-only</string>
                    <string name="literal_percent" formatted="false">Progress is 100% complete</string>
                </resources>
                """.trimIndent()
            )
            File(tempDirectory, "other_values.xml").writeText(
                """
                <resources>
                    <plurals name="visible_count">
                        <item quantity="one">%1${'$'}d item</item>
                        <item quantity="other">%1${'$'}d items</item>
                    </plurals>
                    <color name="ignored_color">#ffffff</color>
                </resources>
                """.trimIndent()
            )

            val resources = ResourceCatalog.read(tempDirectory)

            assertTrue(resources.keys == setOf("literal_percent", "visible", "visible_count"))
            assertTrue(resources.getValue("literal_percent").placeholders.isEmpty())
            assertTrue(resources.getValue("visible").placeholders == listOf(Placeholder(1, 's')))
            assertTrue(
                resources.getValue("visible_count").placeholders ==
                    listOf(Placeholder(1, 'd'), Placeholder(1, 'd'))
            )
        } finally {
            tempDirectory.deleteRecursively()
        }
    }

    private fun resourceDirectory(qualifier: String): File {
        val workingDirectory = System.getProperty("user.dir")
            ?: error("The JVM user.dir property is unavailable")
        var directory = File(workingDirectory).absoluteFile
        while (true) {
            listOf(
                File(directory, "src/main/res/$qualifier"),
                File(directory, "app/src/main/res/$qualifier")
            ).firstOrNull { it.isDirectory }?.let { return it }
            directory = directory.parentFile ?: break
        }
        error("Could not find app/src/main/res/$qualifier from $workingDirectory")
    }
}

private fun canonicalPlaceholders(placeholders: List<Placeholder>): List<Placeholder> =
    placeholders.withIndex()
        .sortedWith(
            compareBy<IndexedValue<Placeholder>> { it.value.index ?: Int.MAX_VALUE }
                .thenBy { it.index }
        )
        .map { it.value }

private data class Placeholder(
    val index: Int?,
    val type: Char
)

private data class ResourceEntry(
    val kind: String,
    val placeholders: List<Placeholder>,
    val source: String
)

private object ResourceCatalog {
    private val translatableKinds = setOf("string", "plurals", "string-array", "array")
    private val conversionTypes = setOf(
        'b', 'B', 'c', 'C', 'd', 'o', 'x', 'X', 'e', 'E', 'f', 'g', 'G', 'a', 'A',
        's', 'S', 'h', 'H', 't', 'T'
    )
    private val formatFlags = setOf('-', '+', '#', ' ', '0', ',', '(', '<', '\'')

    fun read(directory: File): Map<String, ResourceEntry> {
        require(directory.isDirectory) { "Resource directory does not exist: $directory" }

        val parser = secureDocumentBuilderFactory().newDocumentBuilder()
        val entries = sortedMapOf<String, ResourceEntry>()
        val xmlFiles = directory.walkTopDown()
            .filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
            .sortedBy { it.relativeTo(directory).path }
            .toList()

        for (file in xmlFiles) {
            val document = parser.parse(file)
            val root = document.documentElement ?: error("Missing root element in $file")
            for (index in 0 until root.childNodes.length) {
                val element = root.childNodes.item(index) as? Element ?: continue
                val kind = element.localName ?: element.tagName
                if (kind !in translatableKinds || !element.isTranslatable()) continue

                val name = element.getAttribute("name").trim()
                if (name.isEmpty()) continue

                val previous = entries.put(
                    name,
                    ResourceEntry(
                        kind = kind,
                        placeholders = if (element.isFormatted()) {
                            placeholders(element.textContent ?: "")
                        } else {
                            emptyList()
                        },
                        source = file.relativeTo(directory).path
                    )
                )
                check(previous == null) {
                    "Duplicate translatable resource '$name' in " +
                        "${previous?.source} and ${file.relativeTo(directory).path}"
                }
            }
        }
        return entries
    }

    fun placeholders(value: String): List<Placeholder> {
        val result = mutableListOf<Placeholder>()
        var cursor = 0
        while (cursor < value.length) {
            if (value[cursor] != '%') {
                cursor++
                continue
            }
            if (cursor + 1 >= value.length) {
                cursor++
                continue
            }
            if (value[cursor + 1] == '%') {
                cursor += 2
                continue
            }

            val start = cursor
            cursor++
            val indexStart = cursor
            while (cursor < value.length && value[cursor].isDigit()) cursor++
            val explicitIndex = if (
                cursor > indexStart && cursor < value.length && value[cursor] == '$'
            ) {
                value.substring(indexStart, cursor).toIntOrNull().also { cursor++ }
            } else {
                cursor = indexStart
                null
            }

            val flagsStart = cursor
            while (cursor < value.length && value[cursor] in formatFlags) cursor++
            val flags = value.substring(flagsStart, cursor)
            // A whitespace/parenthesis flag without an explicit index is overwhelmingly likely
            // to be ordinary prose such as "100% available". Android's translatable strings in
            // this project use flags only with an explicitly indexed conversion.
            if (explicitIndex == null && flags.any { it == ' ' || it == '(' }) {
                cursor = start + 1
                continue
            }

            while (cursor < value.length && (value[cursor].isDigit() || value[cursor] == '*')) {
                cursor++
            }
            if (cursor < value.length && value[cursor] == '.') {
                cursor++
                while (cursor < value.length && (value[cursor].isDigit() || value[cursor] == '*')) {
                    cursor++
                }
            }
            if (cursor < value.length && value[cursor] in setOf('h', 'l', 'L')) cursor++
            if (cursor >= value.length || value[cursor] !in conversionTypes) {
                cursor = start + 1
                continue
            }

            result += Placeholder(index = explicitIndex, type = value[cursor])
            cursor++
        }
        return result
    }

    private fun Element.isTranslatable(): Boolean =
        getAttributeNode("translatable")?.value?.trim()?.equals("false", ignoreCase = true) != true

    private fun Element.isFormatted(): Boolean =
        getAttributeNode("formatted")?.value?.trim()?.equals("false", ignoreCase = true) != true

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
}
