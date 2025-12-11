package org.multipaz.compose.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap


internal actual fun DocumentModel.renderFallbackCardArt(
    fallbackBaseImage: ImageBitmap,
    primaryText: String?,
    secondaryText: String?
): ImageBitmap {
    val androidBitmap = fallbackBaseImage.asAndroidBitmap()
    val mutable = androidBitmap.copy(Bitmap.Config.ARGB_8888, true)
    val height = mutable.height.toFloat()
    val canvas = Canvas(mutable)
    val padding = height * 0.15f
    val primaryTextSize = height * 0.08f
    val secondaryTextSize = height * 0.06f
    val lineSpacing = primaryTextSize * 1.2f

    val primaryTextPaint = Paint().apply {
        color = Color.BLACK
        this.textSize = primaryTextSize
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }

    val secondaryTextPaint = Paint().apply {
        color = Color.BLACK
        this.textSize = secondaryTextSize
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }

    primaryText?.takeIf { it.isNotBlank() }?.let { text ->
        canvas.drawText(
            text,
            padding,
            padding + primaryTextSize,
            primaryTextPaint
        )
    }

    secondaryText?.takeIf { it.isNotBlank() }?.let { text ->
        canvas.drawText(
            text,
            padding,
            padding + primaryTextSize + lineSpacing,
            secondaryTextPaint
        )
    }

    return mutable.asImageBitmap()

}
