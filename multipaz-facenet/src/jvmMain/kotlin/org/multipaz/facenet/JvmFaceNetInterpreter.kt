package org.multipaz.facenet

import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.isEmpty
import org.multipaz.util.Logger
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "JvmFaceNetInterpreter"

internal class JvmFaceNetInterpreter(
    modelBytes: ByteString,
    val config: FaceNetModelConfig
) : AutoCloseable {

    private val lib = TfLiteCLibrary.instance
    private var modelMemory: Memory? = null
    private var model: Pointer? = null
    private var options: Pointer? = null
    private var interpreter: Pointer? = null
    private val lock = Any()
    @Volatile
    private var isClosed = false

    val imageSquareSize: Int
    val embeddingDim: Int
    private val isChannelsFirst: Boolean
    private val inputCount: Int
    private val outputBatch: Int

    init {
        if (modelBytes.isEmpty()) {
            throw IllegalArgumentException("No FaceNet model bytes provided")
        }
        val bytes = modelBytes.toByteArray()

        val mem = Memory(bytes.size.toLong())
        mem.write(0, bytes, 0, bytes.size)
        modelMemory = mem

        val localModel = lib.TfLiteModelCreate(mem, bytes.size.toLong())
            ?: throw IllegalStateException("Failed to parse TensorFlow Lite model from bytes.")
        model = localModel

        val localOptions = lib.TfLiteInterpreterOptionsCreate()
            ?: throw IllegalStateException("Failed to create TfLiteInterpreterOptions.")
        options = localOptions
        lib.TfLiteInterpreterOptionsSetNumThreads(localOptions, 4)

        val localInterpreter = lib.TfLiteInterpreterCreate(localModel, localOptions)
            ?: throw IllegalStateException("Failed to create TfLiteInterpreter.")
        interpreter = localInterpreter

        val allocStatus = lib.TfLiteInterpreterAllocateTensors(localInterpreter)
        if (allocStatus != TfLiteCLibrary.KTFLITE_OK) {
            close()
            throw IllegalStateException("TfLiteInterpreterAllocateTensors failed with status $allocStatus")
        }

        inputCount = lib.TfLiteInterpreterGetInputTensorCount(localInterpreter)
        val inTensor = lib.TfLiteInterpreterGetInputTensor(localInterpreter, 0)
            ?: throw IllegalStateException("Failed to get input tensor 0.")
        val inDims = lib.TfLiteTensorNumDims(inTensor)

        val dim1 = if (inDims >= 2) lib.TfLiteTensorDim(inTensor, 1) else 112
        val dim2 = if (inDims >= 3) lib.TfLiteTensorDim(inTensor, 2) else 112
        val dim3 = if (inDims >= 4) lib.TfLiteTensorDim(inTensor, 3) else 0

        isChannelsFirst = dim1 == 3
        val inferredImageSize = if (isChannelsFirst && inDims >= 4) dim2 else dim1
        imageSquareSize = config.imageSquareSize ?: inferredImageSize

        val outTensor = lib.TfLiteInterpreterGetOutputTensor(localInterpreter, 0)
            ?: throw IllegalStateException("Failed to get output tensor 0.")
        val outDims = lib.TfLiteTensorNumDims(outTensor)
        outputBatch = if (outDims >= 1) lib.TfLiteTensorDim(outTensor, 0) else 1
        val inferredEmbeddingDim = if (outDims >= 2) lib.TfLiteTensorDim(outTensor, outDims - 1) else 128
        embeddingDim = config.embeddingDim ?: inferredEmbeddingDim

        Logger.d(
            TAG,
            "JVM FaceNet initialized: $imageSquareSize x $imageSquareSize, dim=$embeddingDim, " +
                "channelsFirst=$isChannelsFirst, inDims=$inDims [$dim1, $dim2, $dim3], " +
                "normalization=${config.normalization}"
        )
    }

    fun getEmbedding(rawRgbFloats: FloatArray): FaceEmbedding? {
        synchronized(lock) {
            if (isClosed) return null
            val localInterpreter = interpreter ?: return null

            val numPixels = imageSquareSize * imageSquareSize
            val expectedSize = numPixels * 3
            if (rawRgbFloats.size != expectedSize) {
                Logger.e(TAG, "Input size mismatch: expected $expectedSize floats, got ${rawRgbFloats.size}")
                return null
            }

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

            val inputMem = Memory((normalizedFloats.size * 4).toLong())
            for (i in normalizedFloats.indices) {
                inputMem.setFloat((i * 4).toLong(), normalizedFloats[i])
            }

            var allOk = true
            for (idx in 0 until inputCount) {
                val inTensor = lib.TfLiteInterpreterGetInputTensor(localInterpreter, idx)
                if (inTensor == null) {
                    allOk = false
                    break
                }
                val status = lib.TfLiteTensorCopyFromBuffer(inTensor, inputMem, inputMem.size())
                if (status != TfLiteCLibrary.KTFLITE_OK) {
                    Logger.e(TAG, "TfLiteTensorCopyFromBuffer failed for input $idx with status $status")
                    allOk = false
                    break
                }
            }
            if (!allOk) return null

            val invokeStatus = lib.TfLiteInterpreterInvoke(localInterpreter)
            if (invokeStatus != TfLiteCLibrary.KTFLITE_OK) {
                Logger.e(TAG, "TfLiteInterpreterInvoke failed with status $invokeStatus")
                return null
            }

            val outputTensor = lib.TfLiteInterpreterGetOutputTensor(localInterpreter, 0) ?: return null
            val totalOutputFloats = outputBatch * embeddingDim
            val outputMem = Memory((totalOutputFloats * 4).toLong())
            val copyOutStatus = lib.TfLiteTensorCopyToBuffer(outputTensor, outputMem, outputMem.size())
            if (copyOutStatus != TfLiteCLibrary.KTFLITE_OK) {
                Logger.e(TAG, "TfLiteTensorCopyToBuffer failed with status $copyOutStatus")
                return null
            }

            val outputFloats = FloatArray(embeddingDim)
            for (i in 0 until embeddingDim) {
                outputFloats[i] = outputMem.getFloat((i * 4).toLong())
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
