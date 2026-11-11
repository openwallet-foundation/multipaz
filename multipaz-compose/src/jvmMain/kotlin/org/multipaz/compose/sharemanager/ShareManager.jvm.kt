package org.multipaz.compose.sharemanager

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.multipaz.util.Logger
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException

private const val TAG = "ShareManager.jvm"

actual class ShareManager {
    actual suspend fun shareDocument(
        content: ByteArray,
        filename: String,
        mimeType: String,
        title: String?
    ) {
        withContext(Dispatchers.IO) {
            val safeName = File(filename).name.ifEmpty { "document" }
            var targetFile: File? = null
            val showDialog = Runnable {
                val dialog = FileDialog(null as Frame?, title ?: "Save $safeName", FileDialog.SAVE)
                try {
                    dialog.file = safeName
                    dialog.isVisible = true
                    val dir = dialog.directory
                    val file = dialog.file
                    if (dir != null && file != null) {
                        targetFile = File(dir, File(file).name)
                    }
                } finally {
                    dialog.dispose()
                }
            }
            try {
                if (EventQueue.isDispatchThread()) {
                    showDialog.run()
                } else {
                    EventQueue.invokeAndWait(showDialog)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to display save dialog for $safeName", e)
                return@withContext
            }
            targetFile?.let { out ->
                try {
                    out.writeBytes(content)
                } catch (e: IOException) {
                    Logger.w(TAG, "Failed to write shared file ${out.name}", e)
                }
            }
        }
    }
}
