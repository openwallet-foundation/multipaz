package org.multipaz.facenet

import cnames.structs.TfLiteInterpreter
import cnames.structs.TfLiteInterpreterOptions
import cnames.structs.TfLiteModel
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pin
import kotlinx.cinterop.usePinned
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.isEmpty
import org.multipaz.util.Logger
import org.tensorflow.lite.c.TfLiteDelegate
import org.tensorflow.lite.c.TfLiteInterpreterAllocateTensors
import org.tensorflow.lite.c.TfLiteInterpreterCreate
import org.tensorflow.lite.c.TfLiteInterpreterDelete
import org.tensorflow.lite.c.TfLiteInterpreterGetInputTensor
import org.tensorflow.lite.c.TfLiteInterpreterGetInputTensorCount
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

    private var pinnedModelBytes: Pinned<ByteArray>? = null
    private var model: CPointer<TfLiteModel>? = null
    private var options: CPointer<TfLiteInterpreterOptions>? = null
    private var xnnpackDelegate: CPointer<TfLiteDelegate>? = null
    private var interpreter: CPointer<TfLiteInterpreter>? = null
    private var isClosed = false

    val imageSquareSize: Int
    val embeddingDim: Int
    private val isChannelsFirst: Boolean
    private val inputCount: Int
    private val outputBatch: Int

    init {
        val bytes = when {
            modelBytes != null && !modelBytes.isEmpty() -> modelBytes.toByteArray()
            else -> loadModelFromBundle("mobile_facenet")
                ?: throw IllegalStateException(
                    "No FaceNet model provided and 'mobile_facenet.tflite' not found in main bundle."
                )
        }

        val pinned = bytes.pin()
        pinnedModelBytes = pinned
        val localModel = TfLiteModelCreate(pinned.addressOf(0), bytes.size.toULong())
            ?: throw IllegalStateException("Failed to parse TensorFlow Lite model from bytes.")
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

        inputCount = TfLiteInterpreterGetInputTensorCount(localInterpreter)
        val inputTensor = TfLiteInterpreterGetInputTensor(localInterpreter, 0)
        val numDims = if (inputTensor != null) TfLiteTensorNumDims(inputTensor) else 0
        isChannelsFirst = inputTensor != null && numDims == 4 && TfLiteTensorDim(inputTensor, 1) == 3
        val inferredImageSize = when {
            isChannelsFirst -> TfLiteTensorDim(inputTensor, 2)
            inputTensor != null && numDims >= 3 -> TfLiteTensorDim(inputTensor, 1)
            else -> 112
        }
        imageSquareSize = config.imageSquareSize ?: inferredImageSize

        val outputTensor = TfLiteInterpreterGetOutputTensor(localInterpreter, 0)
        val outNumDims = if (outputTensor != null) TfLiteTensorNumDims(outputTensor) else 0
        outputBatch = if (outputTensor != null && outNumDims >= 1) TfLiteTensorDim(outputTensor, 0) else 1
        val inferredEmbeddingDim = if (outputTensor != null && outNumDims >= 2) {
            TfLiteTensorDim(outputTensor, outNumDims - 1)
        } else {
            128
        }
        embeddingDim = config.embeddingDim ?: inferredEmbeddingDim

        Logger.d(
            TAG,
            "FaceNet interpreter initialized on iOS: imageSquareSize=$imageSquareSize, " +
                    "embeddingDim=$embeddingDim, isChannelsFirst=$isChannelsFirst, " +
                    "inputCount=$inputCount, outputBatch=$outputBatch, normalization=${config.normalization}"
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

        val numPixels = imageSquareSize * imageSquareSize
        val normalizedFloats = FloatArray(expectedSize)

        when (config.normalization) {
            NormalizationMethod.STANDARDIZE -> {
                var sum = 0.0f
                for (v in rawRgbFloats) sum += v
                val mean = sum / rawRgbFloats.size
                var sumSq = 0.0f
                for (v in rawRgbFloats) {
                    val diff = v - mean
                    sumSq += diff * diff
                }
                val std = sqrt(sumSq / rawRgbFloats.size)
                val stdAdj = max(std, 1.0f / sqrt(rawRgbFloats.size.toFloat()))
                if (isChannelsFirst) {
                    for (i in 0 until numPixels) {
                        normalizedFloats[i] = (rawRgbFloats[i * 3 + 0] - mean) / stdAdj
                        normalizedFloats[numPixels + i] = (rawRgbFloats[i * 3 + 1] - mean) / stdAdj
                        normalizedFloats[2 * numPixels + i] = (rawRgbFloats[i * 3 + 2] - mean) / stdAdj
                    }
                } else {
                    for (i in rawRgbFloats.indices) {
                        normalizedFloats[i] = (rawRgbFloats[i] - mean) / stdAdj
                    }
                }
            }
            NormalizationMethod.SCALE_MINUS_ONE_TO_ONE -> {
                if (isChannelsFirst) {
                    for (i in 0 until numPixels) {
                        normalizedFloats[i] = (rawRgbFloats[i * 3 + 0] - 127.5f) / 128.0f
                        normalizedFloats[numPixels + i] = (rawRgbFloats[i * 3 + 1] - 127.5f) / 128.0f
                        normalizedFloats[2 * numPixels + i] = (rawRgbFloats[i * 3 + 2] - 127.5f) / 128.0f
                    }
                } else {
                    for (i in 0 until numPixels) {
                        normalizedFloats[i * 3 + 0] = (rawRgbFloats[i * 3 + 0] - 127.5f) / 128.0f
                        normalizedFloats[i * 3 + 1] = (rawRgbFloats[i * 3 + 1] - 127.5f) / 128.0f
                        normalizedFloats[i * 3 + 2] = (rawRgbFloats[i * 3 + 2] - 127.5f) / 128.0f
                    }
                }
            }
            NormalizationMethod.SCALE_ZERO_TO_ONE -> {
                if (isChannelsFirst) {
                    for (i in 0 until numPixels) {
                        normalizedFloats[i] = rawRgbFloats[i * 3 + 0] / 255.0f
                        normalizedFloats[numPixels + i] = rawRgbFloats[i * 3 + 1] / 255.0f
                        normalizedFloats[2 * numPixels + i] = rawRgbFloats[i * 3 + 2] / 255.0f
                    }
                } else {
                    for (i in 0 until numPixels) {
                        normalizedFloats[i * 3 + 0] = rawRgbFloats[i * 3 + 0] / 255.0f
                        normalizedFloats[i * 3 + 1] = rawRgbFloats[i * 3 + 1] / 255.0f
                        normalizedFloats[i * 3 + 2] = rawRgbFloats[i * 3 + 2] / 255.0f
                    }
                }
            }
        }

        val inputByteSize = (normalizedFloats.size * Float.SIZE_BYTES).toULong()
        val copyInSuccess = normalizedFloats.usePinned { pinned ->
            var allOk = true
            for (idx in 0 until inputCount) {
                val inTensor = TfLiteInterpreterGetInputTensor(localInterpreter, idx)
                if (inTensor == null) {
                    allOk = false
                    break
                }
                val status = TfLiteTensorCopyFromBuffer(inTensor, pinned.addressOf(0), inputByteSize)
                if (status != kTfLiteOk) {
                    Logger.e(TAG, "TfLiteTensorCopyFromBuffer failed for input $idx with status $status")
                    allOk = false
                    break
                }
            }
            allOk
        }
        if (!copyInSuccess) return null

        val invokeStatus = TfLiteInterpreterInvoke(localInterpreter)
        if (invokeStatus != kTfLiteOk) {
            Logger.e(TAG, "TfLiteInterpreterInvoke failed with status $invokeStatus")
            return null
        }

        val outputTensor = TfLiteInterpreterGetOutputTensor(localInterpreter, 0) ?: return null
        val totalOutputFloats = outputBatch * embeddingDim
        val allOutputs = FloatArray(totalOutputFloats)
        val outputByteSize = (totalOutputFloats * Float.SIZE_BYTES).toULong()
        val copyOutStatus = allOutputs.usePinned { pinned ->
            TfLiteTensorCopyToBuffer(outputTensor, pinned.addressOf(0), outputByteSize)
        }
        if (copyOutStatus != kTfLiteOk) {
            Logger.e(TAG, "TfLiteTensorCopyToBuffer failed with status $copyOutStatus")
            return null
        }

        val outputFloats = allOutputs.copyOfRange(0, embeddingDim)

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
        pinnedModelBytes?.unpin()
        pinnedModelBytes = null
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
