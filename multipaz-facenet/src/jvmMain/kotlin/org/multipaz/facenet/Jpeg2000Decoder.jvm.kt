package org.multipaz.facenet

import java.awt.image.BufferedImage

internal fun decodeJpeg2000ToBufferedImage(bytes: ByteArray): BufferedImage? =
    org.multipaz.jpeg2000.decodeJpeg2000ToBufferedImage(bytes)
