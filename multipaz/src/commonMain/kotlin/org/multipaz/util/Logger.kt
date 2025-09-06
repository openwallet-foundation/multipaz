/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multipaz.util

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import kotlin.time.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.multipaz.cbor.DataItem
import org.multipaz.util.Logger.LogPrinter.Level

/**
 * Logging facility.
 */
object Logger {
    private const val TAG = "Logger"

    /**
     * Log printer interface that receives every emitted log entry. Default implementation from
     * [getPlatformLogPrinter] routes every message to the platform-specific logging facility.
     *
     * @see logPrinter
     */
    fun interface LogPrinter {
        /**
         * The log level, from the lowest to the highest priority.
         */
        enum class Level {
            DEBUG,
            INFO,
            WARNING,
            ERROR,
        }

        /**
         * Print the given log [msg], with the [level], [tag], and an optional [throwable].
         */
        fun print(level: Level, tag: String, msg: String, throwable: Throwable?)
    }

    var isDebugEnabled = true // TODO: make false by default

    /**
     * Optional [LogPrinter] property for overriding the default functionality with a custom
     * implementation.
     */
    var logPrinter: LogPrinter? = null

    private var fileWriter: Sink? = null
    private var fileWriterPath: Path? = null

    fun startLoggingToFile(logPath: Path) {
        if (fileWriter != null) {
            w(TAG, "startLoggingToFile: Already logging to file $fileWriterPath")
            fileWriter!!.close()
            fileWriter = null
            fileWriterPath = null
        }
        d(TAG, "Starting logging to file $fileWriterPath")
        fileWriter = SystemFileSystem.sink(logPath).buffered()
        fileWriterPath = logPath
    }

    fun stopLoggingToFile() {
        if (fileWriter == null) {
            w(TAG, "startLoggingToFile: Not logging to file")
            return
        }
        fileWriter!!.close()
        fileWriter = null
        d(TAG, "Stopped logging to file ${fileWriterPath!!.name}")
        fileWriterPath = null
    }

    internal fun prepareLine(
        level: Level,
        tag: String,
        msg: String,
        throwable: Throwable?
    ): String {
        val sb = StringBuilder()
        val now = Clock.System.now()
        val dt = now.toLocalDateTime(TimeZone.currentSystemDefault())
        val timeStamp = dt.format(LocalDateTime.Formats.ISO)
        sb.append(timeStamp)
        sb.append(": ")
        when (level) {
            Level.DEBUG -> sb.append("DEBUG")
            Level.INFO -> sb.append("INFO")
            Level.WARNING -> sb.append("WARNING")
            Level.ERROR -> sb.append("ERROR")
        }
        sb.append(": ")
        sb.append(tag)
        sb.append(": ")
        sb.append(msg)
        if (throwable != null) {
            sb.append("\nEXCEPTION: ")
            sb.append(throwable)
        }
        return sb.toString()
    }

    private fun printLine(
        level: Level,
        tag: String,
        msg: String,
        throwable: Throwable?
    ) {
        val printer = this.logPrinter ?: getPlatformLogPrinter()
        var logLine: String? = null
        printer.print(level, tag, msg, throwable)
        if (fileWriter != null) {
            if (logLine == null) {
                logLine = prepareLine(level, tag, msg, throwable)
            }
            try {
                fileWriter!!.write((logLine + "\n").encodeToByteArray())
                fileWriter!!.flush()
            } catch (e: Throwable) {
                printer.print(Level.ERROR, tag, "Error writing log message to file", e)
                e.printStackTrace()
            }
        }
    }

    fun d(tag: String, msg: String) {
        if (isDebugEnabled) {
            printLine(Level.DEBUG, tag, msg, null)
        }
    }

    fun d(tag: String, msg: String, throwable: Throwable) {
        if (isDebugEnabled) {
            printLine(Level.DEBUG, tag, msg, throwable)
        }
    }

    fun i(tag: String, msg: String) {
        printLine(Level.INFO, tag, msg, null)
    }

    fun i(tag: String, msg: String, throwable: Throwable) {
        printLine(Level.INFO, tag, msg, throwable)
    }

    fun w(tag: String, msg: String) {
        printLine(Level.WARNING, tag, msg, null)
    }

    fun w(tag: String, msg: String, throwable: Throwable) {
        printLine(Level.WARNING, tag, msg, throwable)
    }

    fun e(tag: String, msg: String) {
        printLine(Level.ERROR, tag, msg, null)
    }

    fun e(tag: String, msg: String, throwable: Throwable) {
        printLine(Level.ERROR, tag, msg, throwable)
    }

    private fun hex(level: Level, tag: String, message: String, data: ByteArray) {
        val sb = "$message: ${data.size} bytes of data: " + data.toHex()
        printLine(level, tag, sb, null)
    }

    fun dHex(tag: String, message: String, data: ByteArray) {
        if (isDebugEnabled) {
            hex(Level.DEBUG, tag, message, data)
        }
    }

    fun iHex(tag: String, message: String, data: ByteArray) {
        hex(Level.INFO, tag, message, data)
    }

    fun wHex(tag: String, message: String, data: ByteArray) {
        hex(Level.WARNING, tag, message, data)
    }

    fun eHex(tag: String, message: String, data: ByteArray) {
        hex(Level.ERROR, tag, message, data)
    }

    private fun cbor(level: Level, tag: String, message: String, encodedCbor: ByteArray) {
        val sb = "$message: ${encodedCbor.size} bytes of CBOR: " + encodedCbor.toHex() +
                "\n" +
                "In diagnostic notation:\n" +
                Cbor.toDiagnostics(
                    encodedCbor,
                    setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR)
                )
        printLine(level, tag, sb, null)
    }

    fun dCbor(tag: String, message: String, encodedCbor: ByteArray) {
        if (isDebugEnabled) {
            cbor(Level.DEBUG, tag, message, encodedCbor)
        }
    }

    fun iCbor(tag: String, message: String, encodedCbor: ByteArray) {
        cbor(Level.INFO, tag, message, encodedCbor)
    }

    fun wCbor(tag: String, message: String, encodedCbor: ByteArray) {
        cbor(Level.WARNING, tag, message, encodedCbor)
    }

    fun eCbor(tag: String, message: String, encodedCbor: ByteArray) {
        cbor(Level.ERROR, tag, message, encodedCbor)
    }

    fun dCbor(tag: String, message: String, dataItem: DataItem) {
        if (isDebugEnabled) {
            cbor(Level.DEBUG, tag, message, Cbor.encode(dataItem))
        }
    }

    fun iCbor(tag: String, message: String, dataItem: DataItem) {
        cbor(Level.INFO, tag, message, Cbor.encode(dataItem))
    }

    fun wCbor(tag: String, message: String, dataItem: DataItem) {
        cbor(Level.WARNING, tag, message, Cbor.encode(dataItem))
    }

    fun eCbor(tag: String, message: String, dataItem: DataItem) {
        cbor(Level.ERROR, tag, message, Cbor.encode(dataItem))
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun json(level: Level, tag: String, message: String, json: JsonElement) {
        val prettyJson = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
        printLine(level, tag, "${message}: " + prettyJson.encodeToString(json), null)
    }

    fun dJson(tag: String, message: String, json: JsonElement) {
        if (isDebugEnabled) {
            json(Level.DEBUG, tag, message, json)
        }
    }

    fun iJson(tag: String, message: String, json: JsonElement) {
        json(Level.INFO, tag, message, json)
    }

    fun wJson(tag: String, message: String, json: JsonElement) {
        json(Level.WARNING, tag, message, json)
    }

    fun eJson(tag: String, message: String, json: JsonElement) {
        json(Level.ERROR, tag, message, json)
    }
}

// Low-level platform-specific printer
internal expect fun getPlatformLogPrinter(): Logger.LogPrinter
