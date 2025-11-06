package org.multipaz.testapp

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.runBlocking

private val app = App.getInstance()

fun MainViewController() = ComposeUIViewController {
    app.Content()
}

fun HandleUrl(url: String) = app.handleUrl(url)