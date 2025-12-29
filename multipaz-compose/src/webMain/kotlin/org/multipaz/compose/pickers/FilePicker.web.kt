package org.multipaz.compose.pickers

import androidx.compose.runtime.Composable
import kotlinx.io.bytestring.ByteString

@Composable
actual fun rememberFilePicker(
    types: List<String>,
    allowMultiple: Boolean,
    onResult: (fileData: List<ByteString>) -> Unit,
): FilePicker {
    TODO()
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
