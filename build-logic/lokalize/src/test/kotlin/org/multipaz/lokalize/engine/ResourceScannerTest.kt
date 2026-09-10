package org.multipaz.lokalize.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ResourceScannerTest {

    @Test
    fun `scan should extract simple strings`(@TempDir tempDir: File) {
        // Given
        // The scanner derives the locale from the parent directory name, so the file has to
        // sit in a `values` directory for it to report the base locale.
        val valuesDir = File(tempDir, "values").apply { mkdirs() }
        val xmlFile = File(valuesDir, "strings.xml")
        xmlFile.writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="hello">Hello</string>
                <string name="world">World</string>
            </resources>
        """.trimIndent())

        // When
        val bundle = ResourceScanner().scan(xmlFile)

        // Then
        assertEquals("default", bundle.locale)
        assertEquals(2, bundle.strings.size)
        assertEquals("Hello", bundle.strings["hello"]?.value)
        assertEquals("World", bundle.strings["world"]?.value)
    }

    @Test
    fun `scan should extract plurals with quantities`(@TempDir tempDir: File) {
        // Given
        val xmlFile = File(tempDir, "strings.xml")
        xmlFile.writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <plurals name="items">
                    <item quantity="one">1 item</item>
                    <item quantity="other">%d items</item>
                </plurals>
            </resources>
        """.trimIndent())

        // When
        val bundle = ResourceScanner().scan(xmlFile)

        // Then
        assertEquals(1, bundle.plurals.size)
        val plural = bundle.plurals["items"]
        assertNotNull(plural)
        assertEquals("1 item", plural?.items?.get("one"))
        assertEquals("%d items", plural?.items?.get("other"))
    }

    @Test
    fun `scan should extract string arrays`(@TempDir tempDir: File) {
        // Given
        val xmlFile = File(tempDir, "strings.xml")
        xmlFile.writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string-array name="colors">
                    <item>Red</item>
                    <item>Green</item>
                    <item>Blue</item>
                </string-array>
            </resources>
        """.trimIndent())

        // When
        val bundle = ResourceScanner().scan(xmlFile)

        // Then
        assertEquals(1, bundle.arrays.size)
        val array = bundle.arrays["colors"]
        assertNotNull(array)
        assertEquals(listOf("Red", "Green", "Blue"), array?.items)
    }

    @Test
    fun `scan should detect locale from values-es directory`(@TempDir tempDir: File) {
        // Given
        val valuesEsDir = File(tempDir, "values-es")
        valuesEsDir.mkdirs()
        val xmlFile = File(valuesEsDir, "strings.xml")
        xmlFile.writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="hello">Hola</string>
            </resources>
        """.trimIndent())

        // When
        val bundle = ResourceScanner().scan(xmlFile)

        // Then
        assertEquals("es", bundle.locale)
    }

    @Test
    fun `scan should return empty bundle for missing file`(@TempDir tempDir: File) {
        // Given
        val nonExistentFile = File(tempDir, "non_existent.xml")

        // When
        val bundle = ResourceScanner().scan(nonExistentFile)

        // Then
        assertEquals("unknown", bundle.locale)
        assertTrue(bundle.strings.isEmpty())
        assertTrue(bundle.plurals.isEmpty())
        assertTrue(bundle.arrays.isEmpty())
    }

    @Test
    fun `scan should handle empty resources file`(@TempDir tempDir: File) {
        // Given
        val xmlFile = File(tempDir, "strings.xml")
        xmlFile.writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
            </resources>
        """.trimIndent())

        // When
        val bundle = ResourceScanner().scan(xmlFile)

        // Then
        assertTrue(bundle.strings.isEmpty())
        assertTrue(bundle.plurals.isEmpty())
        assertTrue(bundle.arrays.isEmpty())
    }

    @Test
    fun `scan should handle XML with comments`(@TempDir tempDir: File) {
        // Given
        val xmlFile = File(tempDir, "strings.xml")
        xmlFile.writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <!-- This is a comment -->
                <string name="key">Value</string>
            </resources>
        """.trimIndent())

        // When
        val bundle = ResourceScanner().scan(xmlFile)

        // Then
        assertEquals(1, bundle.strings.size)
        assertEquals("Value", bundle.strings["key"]?.value)
    }

    @Test
    fun `scan should preserve XML entities in strings`(@TempDir tempDir: File) {
        // Given
        val xmlFile = File(tempDir, "strings.xml")
        xmlFile.writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="html">&lt;b&gt;Bold&lt;/b&gt;</string>
                <string name="amp">A &amp; B</string>
            </resources>
        """.trimIndent())

        // When
        val bundle = ResourceScanner().scan(xmlFile)

        // Then
        assertEquals("<b>Bold</b>", bundle.strings["html"]?.value)
        assertEquals("A & B", bundle.strings["amp"]?.value)
    }

    @Test
    fun `scan should handle complex nested plurals`(@TempDir tempDir: File) {
        // Given: Russian-style plural with many quantities
        val xmlFile = File(tempDir, "strings.xml")
        xmlFile.writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <plurals name="minutes">
                    <item quantity="one">%d минута</item>
                    <item quantity="few">%d минуты</item>
                    <item quantity="many">%d минут</item>
                    <item quantity="other">%d минут</item>
                </plurals>
            </resources>
        """.trimIndent())

        // When
        val bundle = ResourceScanner().scan(xmlFile)

        // Then
        val plural = bundle.plurals["minutes"]
        assertNotNull(plural)
        assertEquals(4, plural?.items?.size)
        assertTrue(plural?.items?.containsKey("one") == true)
        assertTrue(plural?.items?.containsKey("few") == true)
        assertTrue(plural?.items?.containsKey("many") == true)
        assertTrue(plural?.items?.containsKey("other") == true)
    }
}
