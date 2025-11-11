package org.multipaz.util

import kotlinx.io.bytestring.ByteString

private val HEX_DIGITS_LOWER = "0123456789abcdef".toCharArray()
private val HEX_DIGITS_UPPER = "0123456789ABCDEF".toCharArray()

object HexUtil {
    /**
     * Encodes a byte array into hexadecimal numbers.
     *
     * The returned string can be decoded using the [fromHex] method.
     *
     * @param bytes the byte array to encode.
     * @param upperCase if `true`, will use upper-case characters, otherwise lower-case is used.
     * @param byteDivider a string to separate bytes (hex pairs).
     * @param decodeAsString if `true`, will include the value decoded as a string with only
     *   printable characters.
     * @return a string with hexadecimal numbers optionally separated by [byteDivider].
     */
    fun toHex(
        bytes: ByteArray,
        upperCase: Boolean = false,
        byteDivider: String = "",
        decodeAsString: Boolean = false
    ): String {
        val sb = StringBuilder(bytes.size * 2)
        for (n in 0 until bytes.size) {
            val b = bytes[n]
            val digits = if (upperCase) HEX_DIGITS_UPPER else HEX_DIGITS_LOWER
            if (n != 0) {
                sb.append(byteDivider)
            }
            sb.append(digits[b.toInt().and(0xff) shr 4])
            sb.append(digits[b.toInt().and(0x0f)])
        }
        if (decodeAsString) {
            val str = bytes.decodeToString().map { char ->
                if (char.isISOControl() || char.isWhitespace()) {
                    '.'
                } else {
                    char
                }
            }.joinToString("")
            sb.append(" (\"${str}\")")
        }
        return sb.toString()
    }

    /**
     * Decodes a string with hexadecimal numbers.
     *
     * The string must contain no whitespace.
     *
     * @param stringWithHex a string with hexadecimal numbers.
     * @throws IllegalArgumentException if the string isn't properly formatted.
     */
    fun fromHex(stringWithHex: String): ByteArray {
        val stringLength = stringWithHex.length
        require(stringLength % 2 == 0) { "Invalid length of hex string: $stringLength" }
        val numBytes = stringLength / 2
        val data = ByteArray(numBytes)
        for (n in 0 until numBytes) {
            val byteStr = stringWithHex.substring(2 * n, 2 * n + 2)
            data[n] = byteStr.toInt(16).toByte()
        }
        return data
    }
}

/**
 * Extension to encode a [ByteArray] to a string with hexadecimal numbers.
 */
fun ByteArray.toHex(upperCase: Boolean = false, byteDivider: String = "", decodeAsString: Boolean = false): String =
    HexUtil.toHex(this, upperCase = upperCase, byteDivider = byteDivider, decodeAsString = decodeAsString)

fun ByteString.toHex(upperCase: Boolean = false, byteDivider: String = "", decodeAsString: Boolean = false): String =
    HexUtil.toHex(toByteArray(), upperCase = upperCase, byteDivider = byteDivider, decodeAsString = decodeAsString)

/**
 * Extension to decode a [ByteArray] from a string with hexadecimal numbers.
 */
fun String.fromHex(): ByteArray = HexUtil.fromHex(this)

/**
 * Extension to decode a [ByteString] from a string with hexadecimal numbers.
 */
fun String.fromHexByteString(): ByteString = ByteString(HexUtil.fromHex(this))
