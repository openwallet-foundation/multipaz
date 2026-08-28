@file:OptIn(
    kotlin.js.ExperimentalWasmJsInterop::class
)
package org.multipaz.tools.frontend

import js.typedarrays.Int8Array
import js.typedarrays.toByteArray
import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.Pkcs12
import org.multipaz.crypto.Pkcs12WrongPassphraseException
import web.blob.Blob
import web.blob.BlobPropertyBag
import web.file.File
import web.file.FileReader
import web.html.HTMLAnchorElement
import web.url.URL

/**
 * Triggers a browser download of a binary file.
 */
fun downloadBinaryFile(
    fileName: String,
    bytes: ByteArray,
    mimeType: String = "application/x-pkcs12"
) {
    val blob = Blob(arrayOf(bytes), BlobPropertyBag(type = mimeType))
    val blobUrl = URL.createObjectURL(blob)
    val anchor = document.createElement("a").unsafeCast<HTMLAnchorElement>()
    anchor.href = blobUrl
    anchor.download = fileName
    anchor.click()
    URL.revokeObjectURL(blobUrl)
}

/**
 * Reads a PKCS#12 [File] selected by the user.
 *
 * First attempts to decode without a passphrase. If the container requires a passphrase,
 * [onNeedPassphrase] is invoked with the raw bytes so a passphrase prompt dialog can be shown.
 */
fun loadPkcs12File(
    file: File,
    onLoaded: (p12: Pkcs12) -> Unit,
    onNeedPassphrase: (rawBytes: ByteArray) -> Unit,
    onError: (errorMessage: String) -> Unit
) {
    val reader = FileReader()
    reader.asDynamic().onload = {
        try {
            val arrayBuffer = reader.result.unsafeCast<js.buffer.ArrayBuffer>()
            val bytes = Int8Array(arrayBuffer).toByteArray()
            mainScope.launch {
                try {
                    val p12 = Pkcs12.fromDer(ByteString(bytes), passphrase = null)
                    onLoaded(p12)
                } catch (e: Pkcs12WrongPassphraseException) {
                    onNeedPassphrase(bytes)
                } catch (e: Throwable) {
                    onError("Failed to decode PKCS#12 file: ${e.message ?: e.toString()}")
                }
            }
        } catch (e: Throwable) {
            onError("Failed to read file: ${e.message ?: e.toString()}")
        }
    }
    reader.readAsArrayBuffer(file)
}

/**
 * Wrapper class for callback function to prevent React useState from executing it as a reducer.
 */
class Pkcs12Callback(val onDecoded: (p12: Pkcs12) -> Unit)

