package org.multipaz.util

import android.util.Log
import org.multipaz.util.Logger.LogPrinter.Level

internal actual fun getPlatformLogPrinter() = Logger.LogPrinter { level, tag, msg, throwable ->
    // Android clamps the log message at just over 4000 characters so chunk and only include
    // the throwable with the first Log.x() call
    val messages = msg.chunked(4000)
    var t = throwable
    for (chunk in messages) {
        when (level) {
            Level.DEBUG -> t?.let { Log.d(tag, chunk, it) } ?: Log.d(tag, chunk)
            Level.INFO -> t?.let { Log.i(tag, chunk, it) } ?: Log.i(tag, chunk)
            Level.WARNING -> t?.let { Log.w(tag, chunk, it) } ?: Log.w(tag, chunk)
            Level.ERROR -> t?.let { Log.e(tag, chunk, it) } ?: Log.e(tag, chunk)
        }
        t = null
    }
}

internal actual fun isDebugEnabledByEnvironment(): Boolean =
    !System.getenv("MULTIPAZ_DEBUG").isNullOrEmpty()

