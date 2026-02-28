package org.multipaz.compose.branding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.bytestring.ByteString
import org.jetbrains.compose.resources.getDrawableResourceBytes
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import org.multipaz.compose.decodeImage
import org.multipaz.document.Document
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.default_card_art

/**
 * An interface the application can use to express what branding to use in multipaz-compose.
 *
 * Library code will use [Branding.Current] which is set to [Branding.Default] by default.
 * The default branding should work for any application but if an application has specific
 * needs it may override the branding using [Branding.setCurrent].
 */
interface Branding {
    /**
     * The name of the application.
     *
     * The default implementation gets this information from the OS, if available.
     */
    val appName: String?

    /**
     * The icon for the application.
     *
     * The default implementation gets this information from the OS, if available.
     */
    val appIconPainter: Painter?

    /**
     * The theme for the application.
     */
    val theme: @Composable (content: @Composable () -> Unit) -> Unit

    /**
     * A function to render fallback card art for a [Document].
     *
     * The default implementation renders [Document.displayName] and [Document.typeDisplayName]
     * on top of some default card art.
     */
    suspend fun renderFallbackCardArt(document: Document): ImageBitmap

    /**
     * A composable which renders an avatar icon.
     *
     * The default implementation renders a colored circle with the two initials of the name
     * in a random but predictable color.
     *
     * @param size the size of the avatar.
     * @param name the name of the avatar.
     * @param additionalData optional additional data to use for calculating the color. This exists
     * such that two avatars which the same name can get a different color.
     * @param modifier a [Modifier].
     */
    @Composable
    fun AvatarIcon(
        size: Dp,
        name: String,
        additionalData: ByteArray? = null,
        modifier: Modifier = Modifier
    )

    companion object {
        /**
         * The default branding.
         */
        val Default: Branding = DefaultBranding

        private var mutableCurrent = MutableStateFlow<Branding>(DefaultBranding)

        /**
         * The current branding, as a [StateFlow].
         */
        val Current: StateFlow<Branding> = mutableCurrent.asStateFlow()

        /**
         * A function to set the current branding.
         *
         * To revert back to the default branding, simply pass [Branding.Default] here.
         *
         * @param current the [Branding] to use.
         */
        fun setCurrent(current: Branding) {
            mutableCurrent.value = current
        }
    }
}

private object DefaultBranding: Branding {
    override val appName = defaultAppName

    override val appIconPainter = defaultAppIconPainter

    override val theme = defaultTheme

    override suspend fun renderFallbackCardArt(document: Document): ImageBitmap =
        defaultRenderFallbackCardArt(document)

    @Composable
    override fun AvatarIcon(size: Dp, name: String, additionalData: ByteArray?, modifier: Modifier) =
        defaultAvatarIcon(size, name, additionalData, modifier)
}

internal expect val defaultAppName: String?

internal expect val defaultAppIconPainter: Painter?

internal expect val defaultTheme: @Composable (content: @Composable () -> Unit) -> Unit

internal val textMeasurer: TextMeasurer by lazy {
    TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(),
        defaultDensity = Density(1.0f),
        defaultLayoutDirection = LayoutDirection.Ltr,
        cacheSize = 8
    )
}

internal expect fun createFontFamilyResolver(): FontFamily.Resolver

private var fallbackBaseImage: ImageBitmap? = null

private suspend fun getFallbackBaseImage(): ImageBitmap {
    if (fallbackBaseImage != null) {
        return fallbackBaseImage!!
    }
    val baseImageBytes = getDrawableResourceBytes(
        getSystemResourceEnvironment(),
        Res.drawable.default_card_art
    )
    fallbackBaseImage = decodeImage(baseImageBytes)
    return fallbackBaseImage!!
}

private suspend fun defaultRenderFallbackCardArt(
    document: Document
): ImageBitmap {
    val fallbackBaseImage = getFallbackBaseImage()
    val width = fallbackBaseImage.width
    val height = fallbackBaseImage.height
    val bitmap = ImageBitmap(width, height)
    val canvas = Canvas(bitmap)

    val drawScope = CanvasDrawScope()
    drawScope.draw(
        density = Density(1.0f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = canvas,
        size = Size(fallbackBaseImage.width.toFloat(), fallbackBaseImage.height.toFloat())
    ) {
        drawImage(
            image = fallbackBaseImage,
        )
        document.displayName?.let {
            drawText(
                topLeft = Offset(width * 0.05f, height * 0.35f),
                textMeasurer = textMeasurer,
                text = it,
                style = TextStyle(fontSize = 70.sp, color = Color.White, fontWeight = FontWeight.Bold),
            )
        }
        document.typeDisplayName?.let {
            drawText(
                topLeft = Offset(width * 0.05f, height * 0.55f),
                textMeasurer = textMeasurer,
                text = it,
                style = TextStyle(fontSize = 30.sp, color = Color.White)
            )
        }
    }
    return bitmap
}

@Composable
private fun defaultAvatarIcon(
    size: Dp,
    name: String,
    additionalData: ByteArray?,
    modifier: Modifier
) {
    val initials = name
        .split(" ")
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")

    var colorRgb = name.hashCode()
    additionalData?.let {
        colorRgb = colorRgb.xor(it.contentHashCode())
    }
    val color = Color(colorRgb.toLong() or 0xFF000000) // Ensure alpha is not zero

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = TextStyle(color = Color.White, fontSize = (size.value/2).sp)
        )
    }
}
