package org.multipaz.compose.branding

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import com.google.accompanist.drawablepainter.DrawablePainter
import org.multipaz.context.applicationContext

private fun getApplicationContextOrFallback(): Context? {
    try {
        return applicationContext
    } catch (_: Throwable) {
        // Fallback to ActivityThread.currentApplication() if not explicitly initialized yet
    }
    try {
        val activityThread = Class.forName("android.app.ActivityThread")
        val currentApp = activityThread.getMethod("currentApplication").invoke(null) as? Context
        if (currentApp != null) {
            return currentApp
        }
    } catch (_: Throwable) {
    }
    return null
}

internal actual val defaultAppName: String?
    get() {
        val context = getApplicationContextOrFallback() ?: return null
        val appInfo = context.applicationInfo
        return if (appInfo.labelRes != 0) {
            context.getString(appInfo.labelRes)
        } else {
            appInfo.nonLocalizedLabel?.toString()
        }
    }

internal actual val defaultAppIconPainter: Painter?
    get() {
        val context = getApplicationContextOrFallback() ?: return null
        return DrawablePainter(context.packageManager.getApplicationIcon(context.packageName))
    }

@Composable
private fun AppThemeDefault(content: @Composable () -> Unit) {
    val darkScheme = isSystemInDarkTheme()
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkScheme) {
            dynamicDarkColorScheme(context)
        } else {
            dynamicLightColorScheme(context)
        }
    } else {
        if (darkScheme) {
            darkColorScheme()
        } else {
            lightColorScheme()
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

internal actual val defaultTheme: @Composable (content: @Composable () -> Unit) -> Unit = { AppThemeDefault(it) }

internal actual fun createFontFamilyResolver(): FontFamily.Resolver {
    val context = getApplicationContextOrFallback()
        ?: throw IllegalStateException("Android Context is required to create FontFamily.Resolver")
    return createFontFamilyResolver(context)
}
