package org.multipaz.compose.branding

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver as createComposeFontFamilyResolver

internal actual val defaultAppName: String?
    get() = null

internal actual val defaultAppIconPainter: Painter?
    get() = null

@Composable
private fun AppThemeDefault(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content
    )
}

internal actual val defaultTheme: @Composable (content: @Composable () -> Unit) -> Unit = { AppThemeDefault(it) }

internal actual fun createFontFamilyResolver(): FontFamily.Resolver {
    return createComposeFontFamilyResolver()
}
