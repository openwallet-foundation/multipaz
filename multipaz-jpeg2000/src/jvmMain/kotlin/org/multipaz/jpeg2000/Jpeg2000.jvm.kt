package org.multipaz.jpeg2000

import java.awt.image.BufferedImage
import java.io.File
import kotlin.io.path.createTempDirectory

internal actual object NativeLoader {
    private val loaded by lazy {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()

        val platformDir = when {
            os.contains("mac") && (arch == "x86_64" || arch == "amd64") -> "macos-x86_64"
            os.contains("mac") && (arch == "aarch64" || arch == "arm64") -> "macos-arm64"
            os.contains("linux") && (arch == "x86_64" || arch == "amd64") -> "linux-x86_64"
            os.contains("linux") && (arch == "aarch64" || arch == "arm64") -> "linux-aarch64"
            else -> error("Unsupported platform for OpenJPEG JNI: $os $arch")
        }

        val ext = if (os.contains("mac")) "dylib" else "so"
        val libFileName = "libmultipaz_openjpeg_jni.$ext"
        val libPath = "/nativeLibs/$platformDir/$libFileName"

        val url = NativeLoader::class.java.getResource(libPath)
            ?: error("Could not find native library resource: $libPath")

        val tempDir = createTempDirectory("multipaz_openjpeg").toFile().apply {
            deleteOnExit()
        }
        val tempFile = File(tempDir, libFileName).apply {
            deleteOnExit()
            writeBytes(url.readBytes())
        }

        @Suppress("UnsafeDynamicallyLoadedCode")
        System.load(tempFile.absolutePath)
        true
    }

    actual fun ensureLoaded() {
        loaded
    }
}

/**
 * Decodes JPEG 2000 encoded image data (either `.jp2` or `.j2k`) into a JVM [BufferedImage]
 * of type [BufferedImage.TYPE_INT_ARGB], or returns `null` if decoding fails.
 */
fun decodeJpeg2000ToBufferedImage(encodedData: ByteArray): BufferedImage? {
    val decoded = decodeJpeg2000(encodedData) ?: return null
    val image = BufferedImage(decoded.width, decoded.height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, decoded.width, decoded.height, decoded.argbPixels, 0, decoded.width)
    return image
}
