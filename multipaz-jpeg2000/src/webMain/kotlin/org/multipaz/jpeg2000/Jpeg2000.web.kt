package org.multipaz.jpeg2000

actual fun decodeJpeg2000(encodedData: ByteArray): DecodedJpeg2000Image? {
    throw UnsupportedOperationException("JPEG 2000 decoding is not supported on Web targets")
}
