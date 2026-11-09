package org.multipaz.testapp

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val app = App.getInstance()
    Window(
        onCloseRequest = ::exitApplication,
        title = TestAppConfiguration.appName,
        state = rememberWindowState(width = 450.dp, height = 900.dp)
    ) {
        app.Content()
    }
}
