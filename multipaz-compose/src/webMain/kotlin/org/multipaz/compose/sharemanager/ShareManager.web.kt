package org.multipaz.compose.sharemanager

actual class ShareManager {
    actual suspend fun shareDocument(content: ByteArray, filename: String, mimeType: String, title: String?) {
        throw NotImplementedError()
    }
}
