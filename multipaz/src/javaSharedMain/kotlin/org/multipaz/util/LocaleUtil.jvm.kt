package org.multipaz.util

import java.util.Locale

actual val currentLocale: String
    get() {
        return Locale.getDefault().language
    }
