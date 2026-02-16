package org.multipaz.compose.text

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MarkdownAnnotatedStringTest {

    @Test
    fun testPlainText() {
        val result = AnnotatedString.fromMarkdown("Hello world")
        assertEquals("Hello world", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun testBoldText() {
        val result = AnnotatedString.fromMarkdown("Hello **bold** world")
        assertEquals("Hello bold world", result.text)
        assertEquals(1, result.spanStyles.size)
        assertEquals(FontWeight.Bold, result.spanStyles[0].item.fontWeight)
        // 'bold' starts at index 6 and ends at 10
        assertEquals(6, result.spanStyles[0].start)
        assertEquals(10, result.spanStyles[0].end)
    }

    @Test
    fun testItalicText() {
        val result = AnnotatedString.fromMarkdown("Hello *italic* world")
        assertEquals("Hello italic world", result.text)
        assertEquals(1, result.spanStyles.size)
        assertEquals(FontStyle.Italic, result.spanStyles[0].item.fontStyle)
    }

    @Test
    fun testStrikethroughText() {
        val result = AnnotatedString.fromMarkdown("Hello ~~strike~~ world")
        assertEquals("Hello strike world", result.text)
        assertEquals(1, result.spanStyles.size)
        assertEquals(TextDecoration.LineThrough, result.spanStyles[0].item.textDecoration)
    }

    @Test
    fun testInlineCodeText() {
        val result = AnnotatedString.fromMarkdown("Use `code` here")
        assertEquals("Use code here", result.text)
        assertEquals(1, result.spanStyles.size)
        // Monospace validation
        assertEquals(androidx.compose.ui.text.font.FontFamily.Monospace, result.spanStyles[0].item.fontFamily)
    }

    @Test
    fun testLinkText() {
        val result = AnnotatedString.fromMarkdown("Click [here](https://example.com) now")
        assertEquals("Click here now", result.text)

        val links = result.getLinkAnnotations(0, result.length)
        assertEquals(1, links.size)

        val linkAnnotation = links[0].item as androidx.compose.ui.text.LinkAnnotation.Url
        assertEquals("https://example.com", linkAnnotation.url)
        assertEquals(6, links[0].start)
        assertEquals(10, links[0].end)
    }

    @Test
    fun testMultipleFormats() {
        val result = AnnotatedString.fromMarkdown("**Bold**, *italic*, and a [link](https://url.com)!")
        assertEquals("Bold, italic, and a link!", result.text)

        // 1 Bold + 1 Italic
        assertEquals(2, result.spanStyles.size)
        assertEquals(FontWeight.Bold, result.spanStyles[0].item.fontWeight)
        assertEquals(FontStyle.Italic, result.spanStyles[1].item.fontStyle)

        // 1 Link
        assertEquals(1, result.getLinkAnnotations(0, result.length).size)
    }
}