package org.multipaz.facenet

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.multipaz.util.Logger

internal interface TfLiteCLibrary : Library {
    fun TfLiteVersion(): String
    fun TfLiteModelCreate(modelData: Pointer, modelDataSize: Long): Pointer?
    fun TfLiteModelDelete(model: Pointer)
    fun TfLiteInterpreterOptionsCreate(): Pointer?
    fun TfLiteInterpreterOptionsDelete(options: Pointer)
    fun TfLiteInterpreterOptionsSetNumThreads(options: Pointer, numThreads: Int)
    fun TfLiteInterpreterCreate(model: Pointer, options: Pointer): Pointer?
    fun TfLiteInterpreterDelete(interpreter: Pointer)
    fun TfLiteInterpreterAllocateTensors(interpreter: Pointer): Int
    fun TfLiteInterpreterInvoke(interpreter: Pointer): Int
    fun TfLiteInterpreterGetInputTensorCount(interpreter: Pointer): Int
    fun TfLiteInterpreterGetOutputTensorCount(interpreter: Pointer): Int
    fun TfLiteInterpreterGetInputTensor(interpreter: Pointer, inputIndex: Int): Pointer?
    fun TfLiteInterpreterGetOutputTensor(interpreter: Pointer, outputIndex: Int): Pointer?
    fun TfLiteTensorNumDims(tensor: Pointer): Int
    fun TfLiteTensorDim(tensor: Pointer, dimIndex: Int): Int
    fun TfLiteTensorType(tensor: Pointer): Int
    fun TfLiteTensorByteSize(tensor: Pointer): Long
    fun TfLiteTensorCopyFromBuffer(tensor: Pointer, inputData: Pointer, inputDataSize: Long): Int
    fun TfLiteTensorCopyToBuffer(tensor: Pointer, outputData: Pointer, outputDataSize: Long): Int

    companion object {
        private const val TAG = "TfLiteCLibrary"

        const val KTFLITE_OK = 0

        val instance: TfLiteCLibrary by lazy {
            try {
                Native.load("tensorflowlite_c", TfLiteCLibrary::class.java)
            } catch (t: Throwable) {
                Logger.e(TAG, "Failed to load tensorflowlite_c native library: ${t.message}", t)
                throw t
            }
        }

        val isAvailable: Boolean
            get() = try {
                instance
                true
            } catch (_: Throwable) {
                false
            }
    }
}
