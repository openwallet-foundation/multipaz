package org.multipaz.util

import kotlinx.browser.window

actual val currentLocale: String
    get() {
        return window.navigator.language.substringBefore("-")
    }
