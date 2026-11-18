package org.multipaz.processor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConstructorDefaultParserTest {

    @Test
    fun singleLineMultipleParameters() {
        val source = """
            @CborSerializable
            data class Foo(val a: String = "x", val b: Int? = null, val c: Int? = 4, val d: Long)
        """.trimIndent()
        val defaults = CborSymbolProcessor.extractConstructorDefaultExpressions(source, "Foo")
        assertEquals(listOf("\"x\"", "null", "4", null), defaults)
    }

    @Test
    fun commentsAndMultilineDefaults() {
        val source = """
            @CborSerializable(schemaHash = "abc=def")
            internal data class DocData(
                val id: String, // id = primary
                val optionalA: String? = null, // trailing comment = something
                val optionalB: ByteString? = /* inline = 1 */ null  // serialized metadata
            )
        """.trimIndent()
        val defaults = CborSymbolProcessor.extractConstructorDefaultExpressions(source, "DocData")
        assertEquals(listOf(null, "null", "null"), defaults)
    }

    @Test
    fun annotationsWithEqualsAndConstructorKeyword() {
        val source = """
            @CborSerializable
            class Annotated @OptIn(ExperimentalTime::class) internal constructor(
                @Suppress("UNUSED_PARAMETER") val a: Int? = null,
                @Deprecated(message = "old = true", replaceWith = ReplaceWith("b2"))
                val b: String? = "x = null",
                val c: Map<String, List<Int>> = mapOf("k" to listOf(1, 2)),
                val cmp: Boolean = CONST_A < CONST_B,
                val d: Int? = null,
            ) : Base()
        """.trimIndent()
        val defaults = CborSymbolProcessor.extractConstructorDefaultExpressions(source, "Annotated")
        assertEquals(
            listOf("null", "\"x = null\"", "mapOf(\"k\" to listOf(1, 2))", "CONST_A < CONST_B", "null"),
            defaults
        )
    }

    @Test
    fun stringInterpolationAndBackticks() {
        val source = """
            @CborSerializable
            data class Tricky(
                val `a = b`: String = "hello ${'$'}{bar("x, y = null")}",
                val next: String? = null,
            )
        """.trimIndent()
        val defaults = CborSymbolProcessor.extractConstructorDefaultExpressions(source, "Tricky")
        assertEquals(
            listOf("\"hello \${bar(\"x, y = null\")}\"", "null"),
            defaults
        )
    }

    @Test
    fun classWithoutPrimaryConstructorParentheses() {
        val source = """
            @CborSerializable
            class Empty : Base {
                companion object
            }
        """.trimIndent()
        val defaults = CborSymbolProcessor.extractConstructorDefaultExpressions(source, "Empty")
        assertEquals(emptyList<String?>(), defaults)
        assertNull(CborSymbolProcessor.extractConstructorDefaultExpressions(source, "Missing"))
    }
}
