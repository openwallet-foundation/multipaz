package org.multipaz.testapp

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.awt.GraphicsEnvironment

fun main() = application {
    val app = App.getInstance()
    val (initialWidth, initialHeight) = remember {
        val bounds = runCatching {
            if (!GraphicsEnvironment.isHeadless()) {
                GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            } else {
                null
            }
        }.getOrNull()
        if (bounds != null && bounds.width > 0 && bounds.height > 0) {
            val width = (bounds.width - 80).coerceIn(360, 480).dp
            val height = (bounds.height * 4 / 5).coerceIn(480, 850).dp
            width to height
        } else {
            450.dp to 750.dp
        }
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = TestAppConfiguration.appName,
        resizable = true,
        state = rememberWindowState(width = initialWidth, height = initialHeight)
    ) {
        LaunchedEffect(window) {
            window.minimumSize = Dimension(320, 400)
        }
        app.Content()
    }
}
