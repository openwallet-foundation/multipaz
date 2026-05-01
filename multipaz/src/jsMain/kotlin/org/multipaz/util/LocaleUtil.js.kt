package org.multipaz.util

actual val currentLocale: String
    get() {
        return try {
            js("navigator.language || 'en'").toString().substringBefore("-")
        } catch (e: Throwable) {
            "en"
        }
    }
