package org.multipaz.facenet

import android.content.Context
import android.graphics.Bitmap
import kotlinx.io.bytestring.ByteString
import org.multipaz.context.applicationContext
import org.multipaz.util.Logger
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.TensorOperator
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.support.tensorbuffer.TensorBufferFloat
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.pow
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
    private val imageProcessor: ImageProcessor

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
            loadModelFromAssets(applicationContext, "facenet_512.tflite")
                ?: loadModelFromAssets(applicationContext, "mobile_facenet.tflite")
                ?: throw IllegalStateException(
                    "No FaceNet model provided and neither 'facenet_512.tflite' nor 'mobile_facenet.tflite' " +
                            "found in assets."
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

        val inputShape = interpreter.getInputTensor(0).shape()
        val inferredImageSize = if (inputShape.size >= 3) inputShape[1] else 160
        imageSquareSize = config.imageSquareSize ?: inferredImageSize

        val outputShape = interpreter.getOutputTensor(0).shape()
        val inferredEmbeddingDim = if (outputShape.size >= 2) outputShape[1] else 512
        embeddingDim = config.embeddingDim ?: inferredEmbeddingDim

        Logger.d(
            TAG,
            "FaceNet interpreter initialized: imageSquareSize=$imageSquareSize, " +
                    "embeddingDim=$embeddingDim, normalization=${config.normalization}"
        )

        val processorBuilder = ImageProcessor.Builder()
            .add(ResizeOp(imageSquareSize, imageSquareSize, ResizeOp.ResizeMethod.BILINEAR))

        when (config.normalization) {
            NormalizationMethod.STANDARDIZE -> {
                processorBuilder.add(StandardizeOp())
            }
            NormalizationMethod.SCALE_MINUS_ONE_TO_ONE -> {
                processorBuilder.add(NormalizeOp(127.5f, 128.0f))
            }
        }
        imageProcessor = processorBuilder.build()
    }

    fun getEmbedding(faceBitmap: Bitmap): FaceEmbedding? {
        synchronized(lock) {
            if (isClosed) return null
            val tensorImage = TensorImage.fromBitmap(faceBitmap)
            val inputs = imageProcessor.process(tensorImage).buffer
            val outputs = Array(1) { FloatArray(embeddingDim) }
            interpreter.run(inputs, outputs)
            return FaceEmbedding(outputs[0])
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

private class StandardizeOp : TensorOperator {
    override fun apply(bufferPointerTop: TensorBuffer?): TensorBuffer {
        val buffer = bufferPointerTop ?: throw IllegalArgumentException("Input TensorBuffer cannot be null")
        val pixels = buffer.floatArray
        if (pixels.isEmpty()) return buffer

        val mean = pixels.average().toFloat()
        var stdDev = sqrt(pixels.sumOf { (it - mean).toDouble().pow(2) } / pixels.size).toFloat()
        stdDev = max(stdDev, 1f / sqrt(pixels.size.toFloat()))
        val standardizedPixels = FloatArray(pixels.size) { i ->
            (pixels[i] - mean) / stdDev
        }
        val output = TensorBufferFloat.createFixedSize(buffer.shape, DataType.FLOAT32)
        output.loadArray(standardizedPixels)
        return output
    }
}
