package org.multipaz.server.drawing

internal sealed class Effect {
    abstract val inset: Float
    abstract val offsetX: Float
    abstract val offsetY: Float

    abstract fun transformAlpha(
        width: Int,
        height: Int,
        shape: ByteArray
    ): ByteArray

    abstract fun applyAlpha(
        shape: ByteArray,
        width: Int,
        height: Int,
        target: IntArray,
        targetX: Int,
        targetY: Int,
        targetWidth: Int,
        targetHeight: Int,
    )

    companion object {
        fun paint(
            argb: UInt,
            shape: ByteArray,
            width: Int,
            height: Int,
            target: IntArray,
            targetX: Int,
            targetY: Int,
            targetWidth: Int,
            targetHeight: Int,
        ) {
            val ap = argb.toInt() ushr 24
            val rp = (argb.toInt() ushr 16) and 0xFF
            val gp = (argb.toInt() ushr 8) and 0xFF
            val bp = argb.toInt() and 0xFF
            for (y in 0..<targetHeight) {
                var si = (y + targetY) * width + targetX
                var ti = y * targetWidth
                repeat (targetWidth) {
                    val ad = ap * (shape[si++].toInt() and 0xFF) / 0xFF
                    if (ad == 0) {
                        ti++
                    } else {
                        val argb = target[ti]
                        val ao = (argb shr 24) and 0xFF
                        val ro = (argb shr 16) and 0xFF
                        val go = (argb shr 8) and 0xFF
                        val bo = argb and 0xFF

                        val f = 0xFF - ad
                        val af = (ao * f + ap * ad) / 0xFF
                        val rf = (ro * f + rp * ad) / 0xFF
                        val gf = (go * f + gp * ad) / 0xFF
                        val bf = (bo * f + bp * ad) / 0xFF

                        target[ti++] = (af shl 24) or (rf shl 16) or (gf shl 8) or bf
                    }
                }
            }
        }
    }
}