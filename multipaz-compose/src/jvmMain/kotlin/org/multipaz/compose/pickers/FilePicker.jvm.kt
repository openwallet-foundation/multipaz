package org.multipaz.compose.pickers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import org.multipaz.util.Logger
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException

private const val TAG = "FilePicker.jvm"

@Composable
actual fun rememberFilePicker(
    types: List<String>,
    allowMultiple: Boolean,
    onResult: (fileData: List<ByteString>) -> Unit,
): FilePicker {
    val currentOnResult = rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    return remember(types, allowMultiple) {
        FilePicker(
            types = types,
            allowMultiple = allowMultiple,
            onLaunch = {
                scope.launch {
                    val results = withContext(Dispatchers.IO) {
                        var selectedFiles: Array<File> = emptyArray()
                        val showDialog = Runnable {
                            val dialog = FileDialog(null as Frame?, "Select File", FileDialog.LOAD)
                            try {
                                dialog.isMultipleMode = allowMultiple
                                dialog.isVisible = true
                                selectedFiles = if (allowMultiple) {
                                    dialog.files
                                } else {
                                    val dir = dialog.directory
                                    val file = dialog.file
                                    if (dir != null && file != null) arrayOf(File(dir, file)) else emptyArray()
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
                            Logger.w(TAG, "Failed to display file picker dialog", e)
                            return@withContext emptyList()
                        }
                        selectedFiles.mapNotNull { f ->
                            if (!f.isFile) {
                                null
                            } else {
                                try {
                                    ByteString(f.readBytes())
                                } catch (e: IOException) {
                                    Logger.w(TAG, "Failed to read selected file ${f.name}", e)
                                    null
                                }
                            }
                        }
                    }
                    currentOnResult.value(results)
                }
            }
        )
    }
}

actual class FilePicker actual constructor(
    val types: List<String>,
    val allowMultiple: Boolean,
    val onLaunch: () -> Unit
) {
    actual fun launch() {
        onLaunch()
    }
}
