package org.multipaz.tools.frontend

import emotion.react.css
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.Color
import web.cssom.Display
import web.cssom.FontWeight
import web.cssom.JustifyContent
import web.cssom.px
import org.multipaz.cbor.Cdn
import org.multipaz.cbor.Cbor
import org.multipaz.util.fromBase64
import org.multipaz.util.fromBase64Url
import org.multipaz.util.fromHex

fun decodeInputToBytes(input: String): ByteArray {
    val clean = input.replace(Regex("[\\s\\r\\n\\t]"), "")
    if (clean.isEmpty()) return byteArrayOf()
    
    var hexCand = clean
    if (hexCand.startsWith("h'") && hexCand.endsWith("'")) {
        hexCand = hexCand.substring(2, hexCand.length - 1)
    }
    
    try {
        if (hexCand.all { it in "0123456789abcdefABCDEF" } && hexCand.length % 2 == 0) {
            return hexCand.fromHex()
        }
    } catch (e: Exception) {}
    
    try {
        return clean.fromBase64Url()
    } catch (e: Exception) {}
    
    try {
        return clean.fromBase64()
    } catch (e: Exception) {}
    
    throw IllegalArgumentException("Could not decode input as Hex, Base64Url or Base64")
}

data class DetectedInputInfo(
    val formatName: String,
    val byteCount: Int
)

fun detectInputInfo(input: String, isCdnOnly: Boolean = false): DetectedInputInfo? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    if (isCdnOnly) {
        try {
            val item = Cdn.parse(trimmed)
            val bytes = Cbor.encode(item)
            return DetectedInputInfo("CDN Text", bytes.size)
        } catch (e: Exception) {}
        val utf8Bytes = trimmed.encodeToByteArray()
        return DetectedInputInfo("UTF-8 Text", utf8Bytes.size)
    }

    // 1. PEM Certificate / Key
    if (trimmed.startsWith("-----BEGIN") && trimmed.contains("-----END")) {
        val lines = trimmed.lines().filter { !it.startsWith("-----") }
        val b64 = lines.joinToString("").replace(Regex("[\\s\\r\\n\\t]"), "")
        val bytes = try { b64.fromBase64() } catch (e: Exception) { null }
        if (bytes != null && bytes.isNotEmpty()) {
            val certType = when {
                trimmed.contains("CERTIFICATE") -> "PEM Certificate"
                trimmed.contains("PRIVATE KEY") -> "PEM Private Key"
                trimmed.contains("PUBLIC KEY") -> "PEM Public Key"
                else -> "PEM Block"
            }
            return DetectedInputInfo(certType, bytes.size)
        }
    }

    val clean = trimmed.replace(Regex("[\\s\\r\\n\\t]"), "")
    if (clean.isEmpty()) return null

    // 2. Hex
    var hexCand = clean
    var isHexShorthand = false
    if (hexCand.startsWith("h'") && hexCand.endsWith("'")) {
        hexCand = hexCand.substring(2, hexCand.length - 1)
        isHexShorthand = true
    }

    if (hexCand.all { it in "0123456789abcdefABCDEF" } && hexCand.length % 2 == 0) {
        val bytes = try { hexCand.fromHex() } catch (e: Exception) { null }
        if (bytes != null) {
            val formatLabel = if (isHexShorthand) "Hex (h'...')" else "Hex"
            return DetectedInputInfo(formatLabel, bytes.size)
        }
    }

    // 3. Base64Url
    try {
        val bytes = clean.fromBase64Url()
        if (bytes.isNotEmpty()) {
            if (clean.contains('-') || clean.contains('_')) {
                return DetectedInputInfo("Base64Url", bytes.size)
            }
        }
    } catch (e: Exception) {}

    // 4. Base64
    try {
        val bytes = clean.fromBase64()
        if (bytes.isNotEmpty()) {
            val isLikelyBase64 = clean.endsWith("=") || clean.contains('+') || clean.contains('/') || (clean.length % 4 == 0 && clean.length > 8)
            if (isLikelyBase64) {
                return DetectedInputInfo("Base64", bytes.size)
            }
        }
    } catch (e: Exception) {}

    // 5. CDN Text literal
    try {
        val item = Cdn.parse(trimmed)
        val bytes = Cbor.encode(item)
        return DetectedInputInfo("CDN Text", bytes.size)
    } catch (e: Exception) {}

    // 6. Fallback UTF-8
    val utf8Bytes = trimmed.encodeToByteArray()
    return DetectedInputInfo("UTF-8 Text", utf8Bytes.size)
}

external interface DetectedInputBadgeProps : Props {
    var input: String
    var isCdnOnly: Boolean?
}

val DetectedInputBadge = FC<DetectedInputBadgeProps> { props ->
    val info = detectInputInfo(props.input, isCdnOnly = props.isCdnOnly == true)
    if (info != null) {
        div {
            css {
                display = Display.flex
                justifyContent = JustifyContent.flexEnd
                marginTop = 4.px
                marginBottom = 8.px
            }
            span {
                css {
                    fontSize = 12.px
                    color = Color("#94a3b8")
                    fontWeight = FontWeight.normal
                }
                val formattedSize = formatNumberWithCommas(info.byteCount)
                val unit = if (info.byteCount == 1) "byte" else "bytes"
                val extraInfo = if (info.formatName.contains("CDN")) " encoded as CBOR" else ""
                +"Detected input: ${info.formatName} ($formattedSize $unit$extraInfo)"
            }
        }
    }
}

fun getUrlHashPayload(): String {
    val hash = kotlinx.browser.window.location.hash
    if (hash.startsWith("#") && hash.length > 1) {
        val raw = hash.substring(1)
        return try {
            js("decodeURIComponent(raw)").unsafeCast<String>()
        } catch (e: Throwable) {
            raw
        }
    }
    return ""
}

val onHashChangeListeners = mutableSetOf<() -> Unit>()

fun updateUrlHashPayload(payload: String) {
    val currentPath = tabToPath(pathToTab(kotlinx.browser.window.location.pathname))
    val clean = payload.trim()
    if (clean.isNotEmpty()) {
        val encoded = try {
            js("encodeURIComponent(clean)").unsafeCast<String>()
        } catch (e: Throwable) {
            clean
        }
        val targetUrl = "$currentPath#$encoded"
        if (kotlinx.browser.window.location.pathname + kotlinx.browser.window.location.hash != targetUrl) {
            kotlinx.browser.window.history.replaceState(null, "", targetUrl)
        }
    } else {
        if (kotlinx.browser.window.location.hash.isNotEmpty()) {
            kotlinx.browser.window.history.replaceState(null, "", currentPath)
        }
    }
}
