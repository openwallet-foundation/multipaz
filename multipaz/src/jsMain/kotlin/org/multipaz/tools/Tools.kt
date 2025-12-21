@file:OptIn(ExperimentalJsExport::class)

package org.multipaz.tools

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.util.fromHex
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

@JsExport
fun cborToDiagnostic(text: String): String {
    try {
        val bytes = text.fromHex()
        val dataItem = Cbor.decode(bytes)
        return Cbor.toDiagnostics(dataItem,
        setOf(DiagnosticOption.EMBEDDED_CBOR, DiagnosticOption.PRETTY_PRINT))
    } catch (e: Exception) {
        return "Error decoding: ${e.message}"
    }
}