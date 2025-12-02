package org.multipaz.mdoc.zkp.longfellow

import kotlinx.io.bytestring.ByteString

class SystemTestCircuitLoader {
    companion object {
        fun loadCircuit(): Pair<String, ByteString>? {
            val filename = "6_1_4096_2945_137e5a75ce72735a37c8a72da1a8a0a5df8d13365c2ae3d2c2bd6a0e7197c7c6"
            val bytes = this::class.java.getResourceAsStream("/circuits/longfellow-libzk-v1/$filename")
                ?.use { it.readBytes() }
                ?: throw IllegalArgumentException("Resource not found")
            return Pair(filename, ByteString(bytes))
        }
    }
}

actual fun loadCircuit(): Pair<String, ByteString>? = SystemTestCircuitLoader.loadCircuit()
