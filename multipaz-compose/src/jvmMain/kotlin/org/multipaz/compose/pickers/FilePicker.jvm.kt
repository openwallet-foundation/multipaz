package org.multipaz.compose.pickers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

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
                        val dialog = FileDialog(null as Frame?, "Select File", FileDialog.LOAD)
                        try {
                            dialog.isMultipleMode = allowMultiple
                            dialog.isVisible = true
                            val selectedFiles: Array<File> = if (allowMultiple) {
                                dialog.files
                            } else {
                                val dir = dialog.directory
                                val file = dialog.file
                                if (dir != null && file != null) arrayOf(File(dir, file)) else emptyArray()
                            }
                            selectedFiles.mapNotNull { f ->
                                if (f.isFile) ByteString(f.readBytes()) else null
                            }
                        } finally {
                            dialog.dispose()
                        }
                    }
                    if (results.isNotEmpty()) {
                        currentOnResult.value(results)
                    }
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
