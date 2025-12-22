package org.multipaz.util

actual fun getPlatformLogPrinter() = Logger.LogPrinter { level, tag, msg, throwable ->
    println(Logger.prepareLine(level, tag, msg, throwable))
}
