package org.multipaz.compose.sharemanager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

actual class ShareManager {
    actual suspend fun shareDocument(
        content: ByteArray,
        filename: String,
        mimeType: String,
        title: String?
    ) {
        withContext(Dispatchers.IO) {
            val dialog = FileDialog(null as Frame?, title ?: "Save $filename", FileDialog.SAVE)
            dialog.file = filename
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            if (dir != null && file != null) {
                File(dir, file).writeBytes(content)
            }
        }
    }
}
