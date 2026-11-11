package org.multipaz.compose.pickers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.io.bytestring.ByteString

@Composable
actual fun rememberImagePicker(
    allowMultiple: Boolean,
    onResult: (fileData: List<ByteString>) -> Unit,
): ImagePicker {
    val filePicker = rememberFilePicker(
        types = listOf("image/*"),
        allowMultiple = allowMultiple,
        onResult = onResult
    )
    return remember(allowMultiple, filePicker) {
        ImagePicker(
            allowMultiple = allowMultiple,
            onLaunch = { filePicker.launch() }
        )
    }
}

actual class ImagePicker actual constructor(
    val allowMultiple: Boolean,
    val onLaunch: () -> Unit
) {
    actual fun launch() {
        onLaunch()
    }
}
