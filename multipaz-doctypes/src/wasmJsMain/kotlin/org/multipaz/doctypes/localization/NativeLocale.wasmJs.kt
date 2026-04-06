package org.multipaz.doctypes.localization

import kotlinx.browser.window

internal actual object NativeLocale {
    actual fun currentLocale(): String {
        return window.navigator.language.substringBefore("-")
    }
}