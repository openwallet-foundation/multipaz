package org.multipaz.facenet

import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.isEmpty
import org.multipaz.facematch.CameraFrame
import org.multipaz.util.Logger
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.atan2
import kotlin.math.hypot

private const val TAG = "JvmFaceDetector"

/**
 * Cross-platform face detector for JVM based on Google MediaPipe BlazeFace short-range TFLite model.
 *
 * Runs deterministic face detection and 6-landmark extraction via TensorFlowLite C API on JVM,
 * matching iOS and Android detection and alignment.
 */
internal class JvmFaceDetector(
    modelBytes: ByteString? = null
) : AutoCloseable {

    private val lib = TfLiteCLibrary.instance
    private var modelMemory: Memory? = null
    private var model: Pointer? = null
    private var options: Pointer? = null
    private var interpreter: Pointer? = null
    private val regressorsIndex: Int
    private val classificatorsIndex: Int
    private val lock = Any()
    @Volatile
    private var isClosed = false

    init {
        val bytes = when {
            modelBytes != null && !modelBytes.isEmpty() -> modelBytes.toByteArray()
            else -> BlazeFaceModelData.defaultModelBytes.toByteArray()
        }

        val mem = Memory(bytes.size.toLong())
        mem.write(0, bytes, 0, bytes.size)
        modelMemory = mem

        val localModel = lib.TfLiteModelCreate(mem, bytes.size.toLong())
            ?: throw IllegalStateException("Failed to parse BlazeFace model from bytes.")
        model = localModel

        val localOptions = lib.TfLiteInterpreterOptionsCreate()
            ?: throw IllegalStateException("Failed to create TfLiteInterpreterOptions for BlazeFace.")
        options = localOptions
        lib.TfLiteInterpreterOptionsSetNumThreads(localOptions, 4)

        val localInterpreter = lib.TfLiteInterpreterCreate(localModel, localOptions)
            ?: throw IllegalStateException("Failed to create TfLiteInterpreter for BlazeFace.")
        interpreter = localInterpreter

        val allocStatus = lib.TfLiteInterpreterAllocateTensors(localInterpreter)
        if (allocStatus != TfLiteCLibrary.KTFLITE_OK) {
            close()
            throw IllegalStateException("TfLiteInterpreterAllocateTensors failed for BlazeFace with status $allocStatus")
        }

        val out0 = lib.TfLiteInterpreterGetOutputTensor(localInterpreter, 0)
        val out1 = lib.TfLiteInterpreterGetOutputTensor(localInterpreter, 1)
        val dim0 = if (out0 != null) lib.TfLiteTensorDim(out0, 2) else 0
        val dim1 = if (out1 != null) lib.TfLiteTensorDim(out1, 2) else 0

        if (dim0 == BlazeFaceDecoder.NUM_COORDS) {
            regressorsIndex = 0
            classificatorsIndex = 1
        } else if (dim1 == BlazeFaceDecoder.NUM_COORDS) {
            regressorsIndex = 1
            classificatorsIndex = 0
        } else {
            regressorsIndex = 0
            classificatorsIndex = 1
        }

        Logger.d(TAG, "JvmFaceDetector initialized (regressors=$regressorsIndex, classificators=$classificatorsIndex)")
    }

    fun detectFaces(image: BufferedImage): List<BlazeFaceDetection> {
        synchronized(lock) {
            if (isClosed) return emptyList()
            val localInterp = interpreter ?: return emptyList()

            val imgW = image.width.toDouble()
            val imgH = image.height.toDouble()
            if (imgW <= 0.0 || imgH <= 0.0) return emptyList()

            // Letterbox to 128x128 preserving aspect ratio, matching Android and iOS BlazeFace input
            val maxDim = maxOf(imgW, imgH)
            val scale = 128.0 / maxDim
            val dw = (imgW * scale).toInt().coerceAtLeast(1)
            val dh = (imgH * scale).toInt().coerceAtLeast(1)
            val dx = (128 - dw) / 2
            val dy = (128 - dh) / 2

            val scaled = if (dw <= image.width / 2 || dh <= image.height / 2) {
                image.getScaledInstance(dw, dh, java.awt.Image.SCALE_SMOOTH)
            } else {
                image
            }

            val letterboxed = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)
            val g = letterboxed.createGraphics()
            g.color = java.awt.Color.BLACK
            g.fillRect(0, 0, 128, 128)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(scaled, dx, dy, dw, dh, null)
            g.dispose()

            val inMem = Memory(128 * 128 * 3 * 4)
            var idx = 0
            for (y in 0 until 128) {
                for (x in 0 until 128) {
                    val rgb = letterboxed.getRGB(x, y)
                    val r = (rgb shr 16) and 0xFF
                    val gVal = (rgb shr 8) and 0xFF
                    val b = rgb and 0xFF
                    inMem.setFloat((idx++ * 4).toLong(), (r - 127.5f) / 127.5f)
                    inMem.setFloat((idx++ * 4).toLong(), (gVal - 127.5f) / 127.5f)
                    inMem.setFloat((idx++ * 4).toLong(), (b - 127.5f) / 127.5f)
                }
            }

            val inTensor = lib.TfLiteInterpreterGetInputTensor(localInterp, 0) ?: return emptyList()
            val copyInStatus = lib.TfLiteTensorCopyFromBuffer(inTensor, inMem, inMem.size())
            if (copyInStatus != TfLiteCLibrary.KTFLITE_OK) {
                Logger.e(TAG, "Failed to copy input tensor for BlazeFace")
                return emptyList()
            }

            val invokeStatus = lib.TfLiteInterpreterInvoke(localInterp)
            if (invokeStatus != TfLiteCLibrary.KTFLITE_OK) {
                Logger.e(TAG, "BlazeFace invoke failed with status $invokeStatus")
                return emptyList()
            }

            val regTensor = lib.TfLiteInterpreterGetOutputTensor(localInterp, regressorsIndex) ?: return emptyList()
            val clsTensor = lib.TfLiteInterpreterGetOutputTensor(localInterp, classificatorsIndex) ?: return emptyList()

            val rawBoxesMem = Memory((BlazeFaceDecoder.NUM_ANCHORS * BlazeFaceDecoder.NUM_COORDS * 4).toLong())
            val rawScoresMem = Memory((BlazeFaceDecoder.NUM_ANCHORS * 4).toLong())

            val copyRegStatus = lib.TfLiteTensorCopyToBuffer(regTensor, rawBoxesMem, rawBoxesMem.size())
            val copyClsStatus = lib.TfLiteTensorCopyToBuffer(clsTensor, rawScoresMem, rawScoresMem.size())
            if (copyRegStatus != TfLiteCLibrary.KTFLITE_OK || copyClsStatus != TfLiteCLibrary.KTFLITE_OK) {
                Logger.e(TAG, "Failed to copy output tensors for BlazeFace")
                return emptyList()
            }

            val numBoxesFloats = BlazeFaceDecoder.NUM_ANCHORS * BlazeFaceDecoder.NUM_COORDS
            val rawBoxes = FloatArray(numBoxesFloats)
            for (i in 0 until numBoxesFloats) {
                rawBoxes[i] = rawBoxesMem.getFloat((i * 4).toLong())
            }

            val rawScores = FloatArray(BlazeFaceDecoder.NUM_ANCHORS)
            for (i in 0 until BlazeFaceDecoder.NUM_ANCHORS) {
                rawScores[i] = rawScoresMem.getFloat((i * 4).toLong())
            }

            return BlazeFaceDecoder.decode(
                rawBoxes = rawBoxes,
                rawScores = rawScores,
                imageWidth = imgW,
                imageHeight = imgH
            )
        }
    }

    fun detectFaces(imageBytes: ByteArray): List<BlazeFaceDetection> {
        val image = ImageIO.read(ByteArrayInputStream(imageBytes)) ?: return emptyList()
        return detectFaces(image)
    }

    fun detectFaces(frame: CameraFrame): List<BlazeFaceDetection> {
        val image = decodeFrameToImage(frame) ?: return emptyList()
        return detectFaces(image)
    }

    fun extractFaceCrop(sourceImage: BufferedImage, face: BlazeFaceDetection, targetSize: Int): FloatArray? {
        val imgW = sourceImage.width.toDouble()
        val imgH = sourceImage.height.toDouble()
        if (imgW <= 0.0 || imgH <= 0.0) return null

        val leftEye = face.leftEye
        val rightEye = face.rightEye
        val eyeDistance = hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y)

        val cropImage = BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_RGB)
        val g2d = cropImage.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        try {
            if (eyeDistance > 1.0) {
                val cx = (leftEye.x + rightEye.x) / 2.0
                val cy = (leftEye.y + rightEye.y) / 2.0
                val eyeAngleRad = atan2(leftEye.y - rightEye.y, leftEye.x - rightEye.x)

                val faceCropFactor = 3.2
                val faceVerticalOffsetFactor = 0.13
                val cropSize = eyeDistance * faceCropFactor
                val verticalOffset = eyeDistance * faceVerticalOffsetFactor
                val scale = targetSize.toDouble() / cropSize

                val at = AffineTransform()
                at.translate(targetSize / 2.0, targetSize / 2.0 - verticalOffset * scale)
                at.rotate(-eyeAngleRad)
                at.scale(scale, scale)
                at.translate(-cx, -cy)

                g2d.transform(at)
                g2d.drawImage(sourceImage, 0, 0, null)
            } else {
                val bb = face.boundingBox
                val marginX = bb.width * 0.1
                val marginY = bb.height * 0.1
                val left = (bb.left - marginX).coerceAtLeast(0.0)
                val top = (bb.top - marginY).coerceAtLeast(0.0)
                val width = (bb.width + marginX * 2.0).coerceAtMost(imgW - left)
                val height = (bb.height + marginY * 2.0).coerceAtMost(imgH - top)
                val scale = targetSize.toDouble() / maxOf(width, height, 1.0)

                val at = AffineTransform()
                at.scale(scale, scale)
                at.translate(-left, -top)
                g2d.transform(at)
                g2d.drawImage(sourceImage, 0, 0, null)
            }
        } finally {
            g2d.dispose()
        }

        val numPixels = targetSize * targetSize
        val floatPixels = FloatArray(numPixels * 3)
        var floatIdx = 0
        for (y in 0 until targetSize) {
            for (x in 0 until targetSize) {
                val rgb = cropImage.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val gVal = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                floatPixels[floatIdx++] = r.toFloat()
                floatPixels[floatIdx++] = gVal.toFloat()
                floatPixels[floatIdx++] = b.toFloat()
            }
        }
        return floatPixels
    }

    fun extractFaceCrop(imageBytes: ByteArray, face: BlazeFaceDetection, targetSize: Int): FloatArray? {
        val image = ImageIO.read(ByteArrayInputStream(imageBytes)) ?: return null
        return extractFaceCrop(image, face, targetSize)
    }

    fun extractFaceCrop(frame: CameraFrame, face: BlazeFaceDetection, targetSize: Int): FloatArray? {
        val image = decodeFrameToImage(frame) ?: return null
        return extractFaceCrop(image, face, targetSize)
    }

    private fun decodeFrameToImage(frame: CameraFrame): BufferedImage? {
        val rawImage = (frame.platformHandle as? BufferedImage)
            ?: if (frame.data.size > 0) {
                ImageIO.read(ByteArrayInputStream(frame.data.toByteArray()))
            } else null
            ?: return null

        if (frame.rotationDegrees == 0) return rawImage

        val angle = Math.toRadians(frame.rotationDegrees.toDouble())
        val isSideways = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
        val targetW = if (isSideways) rawImage.height else rawImage.width
        val targetH = if (isSideways) rawImage.width else rawImage.height

        val rotated = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
        val g = rotated.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val at = AffineTransform()
        at.translate(targetW / 2.0, targetH / 2.0)
        at.rotate(angle)
        at.translate(-rawImage.width / 2.0, -rawImage.height / 2.0)
        g.transform(at)
        g.drawImage(rawImage, 0, 0, null)
        g.dispose()
        return rotated
    }

    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
            interpreter?.let { lib.TfLiteInterpreterDelete(it) }
            interpreter = null
            options?.let { lib.TfLiteInterpreterOptionsDelete(it) }
            options = null
            model?.let { lib.TfLiteModelDelete(it) }
            model = null
            modelMemory = null
        }
    }
}
