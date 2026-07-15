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
