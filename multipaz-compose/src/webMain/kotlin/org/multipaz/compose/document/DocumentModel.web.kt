package org.multipaz.compose.document

import androidx.compose.ui.graphics.ImageBitmap

// TODO: Implement text overlay
internal actual fun DocumentModel.renderFallbackCardArt(
    fallbackBaseImage: ImageBitmap,
    primaryText: String?,
    secondaryText: String?
): ImageBitmap = fallbackBaseImage
