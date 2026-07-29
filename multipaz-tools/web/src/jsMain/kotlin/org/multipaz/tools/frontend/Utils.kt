package org.multipaz.tools.frontend

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

