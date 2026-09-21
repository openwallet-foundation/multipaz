package org.multipaz.facenet

import android.content.Context
import android.graphics.Bitmap
import kotlinx.io.bytestring.ByteString
import org.multipaz.context.applicationContext
import org.multipaz.util.Logger
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "AndroidFaceNetInterpreter"

internal class AndroidFaceNetInterpreter(
    modelBytes: ByteString?,
    val config: FaceNetModelConfig
) : Closeable {

    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    private val lock = Any()
    @Volatile
    private var isClosed = false

    val imageSquareSize: Int
    val embeddingDim: Int
    private val isChannelsFirst: Boolean
    private val inputCount: Int
    private val outputBatch: Int

    init {
        val modelByteBuffer = if (modelBytes != null && modelBytes.size > 0) {
            val bytes = modelBytes.toByteArray()
            ByteBuffer.allocateDirect(bytes.size).apply {
                order(ByteOrder.nativeOrder())
                put(bytes)
                rewind()
            }
        } else {
            // Try loading default model from app assets
            loadModelFromAssets(applicationContext, "mobile_facenet.tflite")
                ?: throw IllegalStateException(
                    "No FaceNet model provided and 'mobile_facenet.tflite' not found in assets."
                )
        }

        val interpreterOptions = Interpreter.Options().apply {
            numThreads = 4
            useXNNPACK = true
            if (config.useGpu) {
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    try {
                        val delegate = GpuDelegate(compatList.bestOptionsForThisDevice)
                        gpuDelegate = delegate
                        addDelegate(delegate)
                        Logger.d(TAG, "GPU delegate enabled")
                    } catch (e: Exception) {
                        Logger.w(TAG, "Failed to initialize GPU delegate, falling back to CPU", e)
                    }
                }
            }
        }

        interpreter = Interpreter(modelByteBuffer, interpreterOptions)

        inputCount = interpreter.inputTensorCount
        val inputTensor0 = interpreter.getInputTensor(0)
        val inputShape = inputTensor0.shape()
        isChannelsFirst = inputShape.size == 4 && inputShape[1] == 3
        val inferredImageSize = when {
            isChannelsFirst -> inputShape[2]
            inputShape.size >= 3 -> inputShape[1]
            else -> 112
        }
        imageSquareSize = config.imageSquareSize ?: inferredImageSize

        val outputTensor0 = interpreter.getOutputTensor(0)
        val outputShape = outputTensor0.shape()
        outputBatch = if (outputShape.isNotEmpty()) outputShape[0] else 1
        val inferredEmbeddingDim = if (outputShape.size >= 2) outputShape[outputShape.size - 1] else 128
        embeddingDim = config.embeddingDim ?: inferredEmbeddingDim

        Logger.d(
            TAG,
            "FaceNet interpreter initialized: imageSquareSize=$imageSquareSize, " +
                    "embeddingDim=$embeddingDim, isChannelsFirst=$isChannelsFirst, " +
                    "inputCount=$inputCount, outputBatch=$outputBatch, normalization=${config.normalization}"
        )
    }

    fun getEmbedding(faceBitmap: Bitmap): FaceEmbedding? {
        synchronized(lock) {
            if (isClosed) return null

            val scaledBitmap = if (faceBitmap.width == imageSquareSize && faceBitmap.height == imageSquareSize) {
                faceBitmap
            } else {
                Bitmap.createScaledBitmap(faceBitmap, imageSquareSize, imageSquareSize, true)
            }

            val numPixels = imageSquareSize * imageSquareSize
            val pixels = IntArray(numPixels)
            scaledBitmap.getPixels(pixels, 0, imageSquareSize, 0, 0, imageSquareSize, imageSquareSize)
            if (scaledBitmap != faceBitmap) {
                scaledBitmap.recycle()
            }

            val floatValues = FloatArray(3 * numPixels)
            when (config.normalization) {
                NormalizationMethod.STANDARDIZE -> {
                    var sum = 0.0
                    for (i in 0 until numPixels) {
                        val p = pixels[i]
                        val r = ((p shr 16) and 0xFF).toDouble()
                        val g = ((p shr 8) and 0xFF).toDouble()
                        val b = (p and 0xFF).toDouble()
                        sum += r + g + b
                    }
                    val mean = sum / (3 * numPixels)
                    var sumSq = 0.0
                    for (i in 0 until numPixels) {
                        val p = pixels[i]
                        val r = ((p shr 16) and 0xFF).toDouble() - mean
                        val g = ((p shr 8) and 0xFF).toDouble() - mean
                        val b = (p and 0xFF).toDouble() - mean
                        sumSq += r * r + g * g + b * b
                    }
                    var std = sqrt(sumSq / (3 * numPixels)).toFloat()
                    std = max(std, 1.0f / sqrt((3 * numPixels).toFloat()))
                    if (isChannelsFirst) {
                        for (i in 0 until numPixels) {
                            val p = pixels[i]
                            floatValues[i] = ((((p shr 16) and 0xFF) - mean) / std).toFloat()
                            floatValues[numPixels + i] = ((((p shr 8) and 0xFF) - mean) / std).toFloat()
                            floatValues[2 * numPixels + i] = (((p and 0xFF) - mean) / std).toFloat()
                        }
                    } else {
                        var idx = 0
                        for (i in 0 until numPixels) {
                            val p = pixels[i]
                            floatValues[idx++] = ((((p shr 16) and 0xFF) - mean) / std).toFloat()
                            floatValues[idx++] = ((((p shr 8) and 0xFF) - mean) / std).toFloat()
                            floatValues[idx++] = (((p and 0xFF) - mean) / std).toFloat()
                        }
                    }
                }
                NormalizationMethod.SCALE_MINUS_ONE_TO_ONE -> {
                    if (isChannelsFirst) {
                        for (i in 0 until numPixels) {
                            val p = pixels[i]
                            floatValues[i] = (((p shr 16) and 0xFF) - 127.5f) / 128.0f
                            floatValues[numPixels + i] = (((p shr 8) and 0xFF) - 127.5f) / 128.0f
                            floatValues[2 * numPixels + i] = ((p and 0xFF) - 127.5f) / 128.0f
                        }
                    } else {
                        var idx = 0
                        for (i in 0 until numPixels) {
                            val p = pixels[i]
                            floatValues[idx++] = (((p shr 16) and 0xFF) - 127.5f) / 128.0f
                            floatValues[idx++] = (((p shr 8) and 0xFF) - 127.5f) / 128.0f
                            floatValues[idx++] = ((p and 0xFF) - 127.5f) / 128.0f
                        }
                    }
                }
                NormalizationMethod.SCALE_ZERO_TO_ONE -> {
                    if (isChannelsFirst) {
                        for (i in 0 until numPixels) {
                            val p = pixels[i]
                            floatValues[i] = ((p shr 16) and 0xFF) / 255.0f
                            floatValues[numPixels + i] = ((p shr 8) and 0xFF) / 255.0f
                            floatValues[2 * numPixels + i] = (p and 0xFF) / 255.0f
                        }
                    } else {
                        var idx = 0
                        for (i in 0 until numPixels) {
                            val p = pixels[i]
                            floatValues[idx++] = ((p shr 16) and 0xFF) / 255.0f
                            floatValues[idx++] = ((p shr 8) and 0xFF) / 255.0f
                            floatValues[idx++] = (p and 0xFF) / 255.0f
                        }
                    }
                }
            }

            val inputBuffer = ByteBuffer.allocateDirect(floatValues.size * Float.SIZE_BYTES).apply {
                order(ByteOrder.nativeOrder())
                asFloatBuffer().put(floatValues)
                rewind()
            }

            val inputs = Array<Any>(inputCount) {
                inputBuffer.duplicate().apply {
                    order(ByteOrder.nativeOrder())
                    rewind()
                }
            }

            val outputArray = Array(outputBatch) { FloatArray(embeddingDim) }
            val outputsMap = mapOf(0 to outputArray)
            interpreter.runForMultipleInputsOutputs(inputs, outputsMap)

            val embeddingFloats = outputArray[0]
            var magSq = 0.0f
            for (v in embeddingFloats) magSq += v * v
            val mag = sqrt(magSq)
            if (mag > 0.0f) {
                for (i in embeddingFloats.indices) {
                    embeddingFloats[i] /= mag
                }
            }

            return FaceEmbedding(embeddingFloats)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
            try {
                interpreter.close()
            } catch (e: Exception) {
                Logger.w(TAG, "Error closing interpreter", e)
            }
            try {
                gpuDelegate?.close()
            } catch (e: Exception) {
                Logger.w(TAG, "Error closing GPU delegate", e)
            }
            gpuDelegate = null
        }
    }

    companion object {
        fun loadModelFromAssets(context: Context, assetName: String): ByteBuffer? {
            return try {
                val fileDescriptor = context.assets.openFd(assetName)
                val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
                val fileChannel = inputStream.channel
                val startOffset = fileDescriptor.startOffset
                val declaredLength = fileDescriptor.declaredLength
                fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            } catch (e: Exception) {
                try {
                    context.assets.open(assetName).use { stream ->
                        val bytes = stream.readBytes()
                        ByteBuffer.allocateDirect(bytes.size).apply {
                            order(ByteOrder.nativeOrder())
                            put(bytes)
                            rewind()
                        }
                    }
                } catch (e2: Exception) {
                    null
                }
            }
        }
    }
}
