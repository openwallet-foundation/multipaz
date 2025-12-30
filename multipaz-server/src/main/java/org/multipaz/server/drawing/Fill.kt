package org.multipaz.server.drawing

internal class Fill(val argb: UInt): Effect() {
    override val inset: Float get() = 0f
    override val offsetX: Float get() = 0f
    override val offsetY: Float get() = 0f

    override fun transformAlpha(width: Int, height: Int, shape: ByteArray): ByteArray
        = shape.clone()

    override fun applyAlpha(
        shape: ByteArray,
        width: Int,
        height: Int,
        target: IntArray,
        targetX: Int,
        targetY: Int,
        targetWidth: Int,
        targetHeight: Int,
    ) = Effect.paint(argb, shape, width, height,
            target, targetX, targetY, targetWidth, targetHeight)
}