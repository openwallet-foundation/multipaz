package org.multipaz.server.drawing

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.round

internal class Blur(
    val argb: UInt = 0xFF000000U,
    val radius: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f,
): Effect() {
    override val inset: Float get() = 3 * ceil(radius)

    override val offsetX: Float get() = dx
    override val offsetY: Float get() = dy

    override fun transformAlpha(
        width: Int,
        height: Int,
        shape: ByteArray
    ): ByteArray =
        shape.clone().also {
            blur(it, width, height)
        }

    override fun applyAlpha(
        shape: ByteArray,
        width: Int,
        height: Int,
        target: IntArray,
        targetX: Int,
        targetY: Int,
        targetWidth: Int,
        targetHeight: Int,
    ) {
        blur(shape, width, height)
        paint(argb, shape, width, height,
            target, targetX, targetY, targetWidth, targetHeight)
    }

    private fun blur(shape: ByteArray, width: Int, height: Int) {
        return if (radius > 2) {
            boxBlur(shape, width, height)
        } else {
            kernelBlur(shape, width, height)
        }
    }

    private fun kernelBlur(shape: ByteArray, width: Int, height: Int) {
        val size = 5
        val ksize = 2 * size + 1
        val kernel = IntArray(ksize)

        val f = -0.5 / (radius * radius)
        val maxValue = Int.MAX_VALUE / ksize
        var sum = 0
        for (i in 0..size) {
            val kv = round(maxValue * exp(f * i * i)).toInt()
            sum += kv
            kernel[size + i] = kv
            if (i > 0) {
                sum += kv
                kernel[size - i] = kv
            }
        }
        for (i in 0..<ksize) {
            kernel[i] = (0x10000L * kernel[i] / sum).toInt()
        }

        val queue = IntArray(ksize)

        for (y in 0..<height) {
            convolveLine(shape, kernel, queue, y * width, 1, width)
        }
        for (x in 0..<width) {
            convolveLine(shape, kernel, queue, x, width, height)
        }
    }

    private fun convolveLine(
        pixels: ByteArray,
        kernel: IntArray,
        queue: IntArray,
        index0: Int,
        stride: Int,
        length: Int
    ) {
        var index = index0
        var qp = 0
        val ksize = kernel.size
        val hsize = ksize / 2
        val hstride = hsize * stride
        repeat(length) {
            val pixel = pixels[index].toInt() and 0xFF
            if (pixel > 0) {
                for (k in 0..<ksize) {
                    val qi = (qp + k) % ksize
                    queue[qi] += kernel[k] * pixel
                }
            }
            val dstX = index - hstride
            if (dstX >= index0) {
                pixels[dstX] = (queue[qp] shr 16).toByte()
            }
            queue[qp] = 0
            qp = (qp + 1) % ksize
            index += stride
        }
        repeat(hsize - 1) {
            pixels[index - hstride] = (queue[qp] shr 16).toByte()
            queue[qp] = 0
            qp = (qp + 1) % ksize
            index += stride
        }
    }

    private fun boxBlur(shape: ByteArray, width: Int, height: Int) {
        val size = floor(radius * BOX_SCALE + 0.5).toInt()
        val queue = IntArray(size)
        if (size % 2 == 1) {
            for (y in 0..<height) {
                repeat (3) {
                    boxFilterLine(shape, 0, queue, y * width, 1, width)
                }
            }
            for (x in 0..<width) {
                repeat (3) {
                    boxFilterLine(shape, 0, queue, x, width, height)
                }
            }
        } else {
            val queue1 = IntArray(size + 1)
            for (y in 0..<height) {
                boxFilterLine(shape, 1, queue, y * width, 1, width)
                boxFilterLine(shape, 0, queue, y * width, 1, width)
                boxFilterLine(shape, 0, queue1, y * width, 1, width)
            }
            for (x in 0..<width) {
                boxFilterLine(shape, 1, queue, x, width, height)
                boxFilterLine(shape, 0, queue, x, width, height)
                boxFilterLine(shape, 0, queue1, x, width, height)
            }
        }
    }

    private fun boxFilterLine(
        pixels: ByteArray,
        bias: Int,
        queue: IntArray,
        index0: Int,
        stride: Int,
        length: Int
    ) {
        val size = queue.size
        var iIn = index0
        val halfSize = size / 2
        var iOut = index0 - (halfSize - bias) * stride
        var pAcc = 0
        val iMax = index0 + length * stride
        var qi = 0
        queue.fill(0)
        while (iOut < iMax) {
            val pIn = if (iIn >= iMax) {
                0
            } else {
                pixels[iIn].toInt() and 0xFF
            }
            iIn += stride
            pAcc += pIn
            queue[qi] = pIn
            qi = (qi + 1) % size
            if (iOut >= index0) {
                pixels[iOut] = ((pAcc + halfSize) / size).toByte()
            }
            pAcc -= queue[qi]
            iOut += stride
        }
    }

    companion object {
        // Scale is 3*sqrt(2*pi)/4 as defined in
        // https://www.w3.org/TR/2000/CR-SVG-20000802/filters.html#feGaussianBlur
        const val BOX_SCALE = 1.8799712059732503
    }
}