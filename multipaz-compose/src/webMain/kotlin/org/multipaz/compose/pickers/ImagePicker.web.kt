package org.multipaz.compose.pickers

import androidx.compose.runtime.Composable
import kotlinx.io.bytestring.ByteString

@Composable
actual fun rememberImagePicker(
    allowMultiple: Boolean,
    onResult: (fileData: List<ByteString>) -> Unit,
): ImagePicker {
    TODO()
}

actual class ImagePicker actual constructor(
    val allowMultiple: Boolean,
    val onLaunch: () -> Unit
) {
    actual fun launch() {
        onLaunch()
    }
}
