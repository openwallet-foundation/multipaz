package org.multipaz.facenet

import cnames.structs.TfLiteInterpreter
import cnames.structs.TfLiteInterpreterOptions
import cnames.structs.TfLiteModel
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.isEmpty
import org.multipaz.util.Logger
import org.tensorflow.lite.c.TfLiteDelegate
import org.tensorflow.lite.c.TfLiteInterpreterAllocateTensors
import org.tensorflow.lite.c.TfLiteInterpreterCreate
import org.tensorflow.lite.c.TfLiteInterpreterDelete
import org.tensorflow.lite.c.TfLiteInterpreterGetInputTensor
import org.tensorflow.lite.c.TfLiteInterpreterGetOutputTensor
import org.tensorflow.lite.c.TfLiteInterpreterInvoke
import org.tensorflow.lite.c.TfLiteInterpreterOptionsAddDelegate
import org.tensorflow.lite.c.TfLiteInterpreterOptionsCreate
import org.tensorflow.lite.c.TfLiteInterpreterOptionsDelete
import org.tensorflow.lite.c.TfLiteInterpreterOptionsSetNumThreads
import org.tensorflow.lite.c.TfLiteModelCreate
import org.tensorflow.lite.c.TfLiteModelDelete
import org.tensorflow.lite.c.TfLiteTensorCopyFromBuffer
import org.tensorflow.lite.c.TfLiteTensorCopyToBuffer
import org.tensorflow.lite.c.TfLiteTensorDim
import org.tensorflow.lite.c.TfLiteTensorNumDims
import org.tensorflow.lite.c.TfLiteXNNPackDelegateCreate
import org.tensorflow.lite.c.TfLiteXNNPackDelegateDelete
import org.tensorflow.lite.c.kTfLiteOk
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "IosFaceNetInterpreter"

@OptIn(ExperimentalForeignApi::class)
internal class IosFaceNetInterpreter(
    modelBytes: ByteString?,
    val config: FaceNetModelConfig
) : AutoCloseable {

    private var model: CPointer<TfLiteModel>? = null
    private var options: CPointer<TfLiteInterpreterOptions>? = null
    private var xnnpackDelegate: CPointer<TfLiteDelegate>? = null
    private var interpreter: CPointer<TfLiteInterpreter>? = null
    private var isClosed = false

    val imageSquareSize: Int
    val embeddingDim: Int

    init {
        val bytes = when {
            modelBytes != null && !modelBytes.isEmpty() -> modelBytes.toByteArray()
            else -> loadModelFromBundle("facenet_512")
                ?: loadModelFromBundle("mobile_facenet")
                ?: throw IllegalStateException(
                    "No FaceNet model provided and neither 'facenet_512.tflite' nor 'mobile_facenet.tflite' " +
                            "found in main bundle."
                )
        }

        val localModel = bytes.usePinned { pinned ->
            TfLiteModelCreate(pinned.addressOf(0), bytes.size.toULong())
        } ?: throw IllegalStateException("Failed to parse TensorFlow Lite model from bytes.")
        model = localModel

        val localOptions = TfLiteInterpreterOptionsCreate()
            ?: throw IllegalStateException("Failed to create TfLiteInterpreterOptions.")
        options = localOptions
        TfLiteInterpreterOptionsSetNumThreads(localOptions, 4)

        try {
            val delegate = TfLiteXNNPackDelegateCreate(null)
            if (delegate != null) {
                xnnpackDelegate = delegate
                TfLiteInterpreterOptionsAddDelegate(localOptions, delegate)
                Logger.d(TAG, "XNNPACK delegate enabled for iOS FaceNet")
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to initialize XNNPACK delegate, falling back to CPU", e)
        }

        val localInterpreter = TfLiteInterpreterCreate(localModel, localOptions)
            ?: throw IllegalStateException("Failed to create TfLiteInterpreter.")
        interpreter = localInterpreter

        val allocStatus = TfLiteInterpreterAllocateTensors(localInterpreter)
        if (allocStatus != kTfLiteOk) {
            throw IllegalStateException("Failed to allocate tensors in TfLiteInterpreter: status $allocStatus")
        }

        val inputTensor = TfLiteInterpreterGetInputTensor(localInterpreter, 0)
        val numDims = if (inputTensor != null) TfLiteTensorNumDims(inputTensor) else 0
        val inferredImageSize = if (inputTensor != null && numDims >= 3) {
            TfLiteTensorDim(inputTensor, 1)
        } else {
            160
        }
        imageSquareSize = config.imageSquareSize ?: inferredImageSize

        val outputTensor = TfLiteInterpreterGetOutputTensor(localInterpreter, 0)
        val outNumDims = if (outputTensor != null) TfLiteTensorNumDims(outputTensor) else 0
        val inferredEmbeddingDim = if (outputTensor != null && outNumDims >= 2) {
            TfLiteTensorDim(outputTensor, 1)
        } else {
            512
        }
        embeddingDim = config.embeddingDim ?: inferredEmbeddingDim

        Logger.d(
            TAG,
            "FaceNet interpreter initialized on iOS: imageSquareSize=$imageSquareSize, " +
                    "embeddingDim=$embeddingDim, normalization=${config.normalization}"
        )
    }

    fun getEmbedding(rawRgbFloats: FloatArray): FaceEmbedding? {
        if (isClosed) return null
        val localInterpreter = interpreter ?: return null
        val expectedSize = imageSquareSize * imageSquareSize * 3
        if (rawRgbFloats.size != expectedSize) {
            Logger.w(TAG, "Input floats size (${rawRgbFloats.size}) does not match expected ($expectedSize)")
            return null
        }

        // Apply normalization
        val normalizedFloats = when (config.normalization) {
            NormalizationMethod.STANDARDIZE -> {
                val norm = FloatArray(rawRgbFloats.size)
                var sum = 0.0f
                for (i in rawRgbFloats.indices) {
                    val v = (rawRgbFloats[i] - 127.5f) / 128.0f
                    norm[i] = v
                    sum += v
                }
                val mean = sum / norm.size
                var sumSq = 0.0f
                for (v in norm) {
                    val diff = v - mean
                    sumSq += diff * diff
                }
                val std = sqrt(sumSq / norm.size)
                val stdAdj = max(std, 1.0f / sqrt(norm.size.toFloat()))
                for (i in norm.indices) {
                    norm[i] = (norm[i] - mean) / stdAdj
                }
                norm
            }
            NormalizationMethod.SCALE_MINUS_ONE_TO_ONE -> {
                val norm = FloatArray(rawRgbFloats.size)
                for (i in rawRgbFloats.indices) {
                    norm[i] = (rawRgbFloats[i] - 127.5f) / 128.0f
                }
                norm
            }
        }

        val inputTensor = TfLiteInterpreterGetInputTensor(localInterpreter, 0) ?: return null
        val inputByteSize = (normalizedFloats.size * Float.SIZE_BYTES).toULong()
        val copyInStatus = normalizedFloats.usePinned { pinned ->
            TfLiteTensorCopyFromBuffer(inputTensor, pinned.addressOf(0), inputByteSize)
        }
        if (copyInStatus != kTfLiteOk) {
            Logger.e(TAG, "TfLiteTensorCopyFromBuffer failed with status $copyInStatus")
            return null
        }

        val invokeStatus = TfLiteInterpreterInvoke(localInterpreter)
        if (invokeStatus != kTfLiteOk) {
            Logger.e(TAG, "TfLiteInterpreterInvoke failed with status $invokeStatus")
            return null
        }

        val outputTensor = TfLiteInterpreterGetOutputTensor(localInterpreter, 0) ?: return null
        val outputFloats = FloatArray(embeddingDim)
        val outputByteSize = (embeddingDim * Float.SIZE_BYTES).toULong()
        val copyOutStatus = outputFloats.usePinned { pinned ->
            TfLiteTensorCopyToBuffer(outputTensor, pinned.addressOf(0), outputByteSize)
        }
        if (copyOutStatus != kTfLiteOk) {
            Logger.e(TAG, "TfLiteTensorCopyToBuffer failed with status $copyOutStatus")
            return null
        }

        // L2 normalize the embedding
        var magSq = 0.0f
        for (v in outputFloats) magSq += v * v
        val mag = sqrt(magSq)
        if (mag > 0.0f) {
            for (i in outputFloats.indices) {
                outputFloats[i] /= mag
            }
        }

        return FaceEmbedding(outputFloats)
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        interpreter?.let {
            TfLiteInterpreterDelete(it)
            interpreter = null
        }
        options?.let {
            TfLiteInterpreterOptionsDelete(it)
            options = null
        }
        xnnpackDelegate?.let {
            TfLiteXNNPackDelegateDelete(it)
            xnnpackDelegate = null
        }
        model?.let {
            TfLiteModelDelete(it)
            model = null
        }
    }

    companion object {
        @OptIn(ExperimentalForeignApi::class)
        private fun loadModelFromBundle(name: String): ByteArray? {
            val path = NSBundle.mainBundle.pathForResource(name, ofType = "tflite") ?: return null
            val data = NSData.dataWithContentsOfFile(path) ?: return null
            val length = data.length.toInt()
            val bytes = ByteArray(length)
            if (length > 0) {
                bytes.usePinned { pinned ->
                    platform.posix.memcpy(pinned.addressOf(0), data.bytes, data.length)
                }
            }
            return bytes
        }
    }
}
