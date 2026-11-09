package org.multipaz.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

internal actual fun getPlatformLogPrinter() = Logger.LogPrinter { level, tag, msg, throwable ->
    println(Logger.prepareLine(level, tag, msg, throwable))
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun isDebugEnabledByEnvironment(): Boolean =
    !getenv("MULTIPAZ_DEBUG")?.toKString().isNullOrEmpty()

