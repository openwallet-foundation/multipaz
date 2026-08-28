package org.multipaz.server.common

import kotlinx.io.bytestring.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the `custom_head_html` injection performed by `serveResources()`. */
class CustomHeadHtmlTest {
    private fun inject(content: String, headHtml: String?): String =
        injectCustomHeadHtml(
            ByteString(content.encodeToByteArray()),
            headHtml
        ).toByteArray().decodeToString()

    @Test
    fun testInsertedBeforeClosingHeadTag() {
        assertEquals(
            "<html><head><title>t</title><link rel=\"x\"></head><body></body></html>",
            inject(
                "<html><head><title>t</title></head><body></body></html>",
                "<link rel=\"x\">"
            )
        )
    }

    @Test
    fun testTagMatchIsCaseInsensitive() {
        assertEquals(
            "<HEAD>X</HEAD>",
            inject("<HEAD></HEAD>", "X")
        )
    }

    @Test
    fun testOnlyFirstClosingHeadTagIsUsed() {
        assertEquals(
            "<head>X</head><body></head></body>",
            inject("<head></head><body></head></body>", "X")
        )
    }

    @Test
    fun testUnchangedWhenNotConfigured() {
        val page = "<html><head></head></html>"
        assertEquals(page, inject(page, null))
    }

    @Test
    fun testUnchangedWhenConfiguredValueIsBlank() {
        val page = "<html><head></head></html>"
        assertEquals(page, inject(page, "   "))
    }

    @Test
    fun testUnchangedWhenThereIsNoHeadTag() {
        val page = "<html><body>no head here</body></html>"
        assertEquals(page, inject(page, "<link rel=\"x\">"))
    }

    @Test
    fun testUnchangedWhenContentIsShorterThanTheTag() {
        assertEquals("<p>", inject("<p>", "X"))
    }

    @Test
    fun testNonUtf8BytesArePreserved() {
        // A page in a non-Unicode encoding must survive injection byte-for-byte: the scan works
        // on raw bytes precisely so that content is never decoded and re-encoded.
        val latin1 = byteArrayOf(0x3C, 0x68, 0x65, 0x61, 0x64, 0x3E)   // "<head>"
            .plus(0xE9.toByte())                                        // 'é' in ISO-8859-1
            .plus("</head>".encodeToByteArray())
        val result = injectCustomHeadHtml(ByteString(latin1), "X").toByteArray()
        val expected = byteArrayOf(0x3C, 0x68, 0x65, 0x61, 0x64, 0x3E)
            .plus(0xE9.toByte())
            .plus("X".encodeToByteArray())
            .plus("</head>".encodeToByteArray())
        assertArrayEquals(expected, result)
    }

    @Test
    fun testInjectedHtmlIsEncodedAsUtf8() {
        val result = injectCustomHeadHtml(
            ByteString("<head></head>".encodeToByteArray()),
            "é"
        ).toByteArray()
        val expected = "<head>".encodeToByteArray()
            .plus(byteArrayOf(0xC3.toByte(), 0xA9.toByte()))
            .plus("</head>".encodeToByteArray())
        assertArrayEquals(expected, result)
    }
}
