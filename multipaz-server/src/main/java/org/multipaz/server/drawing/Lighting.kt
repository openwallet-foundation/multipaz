package org.multipaz.server.drawing

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal class Lighting(
    val argb: UInt = 0xFFFFFFFFU,
    val rgbLight: UInt = 0xFFFFFFU,
    val amplitude: Float = 1f,
    surfaceScale: Float = 1.5f,
    azimuth: Float = 140f,
    elevation: Float = 30f,
    val specularExponent: Float = 13f,
    val elevationMapEffect: Effect = Blur()
): Effect() {
    override val inset: Float
        get() = elevationMapEffect.inset
    override val offsetX: Float
        get() = 0f
    override val offsetY: Float
        get() = 0f

    private val ss = -surfaceScale / (4 * 255)
    private val hx: Double
    private val hy: Double
    private val hz: Double

    init {
        val elevationR = elevation * (PI / 180)
        val azimuthR = azimuth * (PI / 180)
        val ce = cos(elevationR)
        val mx = cos(azimuthR) * ce
        val my = -sin(azimuthR) * ce
        val mz = sin(elevationR) + 1
        val ml = sqrt(mx * mx + my * my + mz * mz)
        hx = mx / ml
        hy = my / ml
        hz = mz / ml
    }

    override fun transformAlpha(
        width: Int,
        height: Int,
        shape: ByteArray
    ): ByteArray = shape.clone()

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
        val elevationMap = elevationMapEffect.transformAlpha(width, height, shape)
        val ap = argb.toInt() ushr 24
        val rp = (argb.toInt() ushr 16) and 0xFF
        val gp = (argb.toInt() ushr 8) and 0xFF
        val bp = argb.toInt() and 0xFF
        val rl = (rgbLight.toInt() ushr 16) and 0xFF
        val gl = (rgbLight.toInt() ushr 8) and 0xFF
        val bl = rgbLight.toInt() and 0xFF
        val minX = max(1, targetX)
        val minY = max(1, targetY)
        val maxX = min(width - 1, targetX + targetWidth)
        val maxY = min(height - 1, targetY + targetHeight)
        for (y in minY..<maxY) {
            for (x in minX..<maxX) {
                val si = y * width + x
                val mask = shape[si].toInt() and 0xFF
                if (mask == 0) {
                    continue
                }

                val light = light(elevationMap, width, height, x, y)

                val ti = (y - targetY) * targetWidth + x - targetX
                val argb = target[ti]
                val ao = (argb shr 24) and 0xFF
                val ro = (argb shr 16) and 0xFF
                val go = (argb shr 8) and 0xFF
                val bo = argb and 0xFF

                val ai = ap * mask / 0xFF
                val ri = min(0xFF, rp + (light * rl) / 0xFF) * ai / 0xFF
                val gi = min(0xFF, gp + (light * gl) / 0xFF) * ai / 0xFF
                val bi = min(0xFF, bp + (light * bl) / 0xFF) * ai / 0xFF

                val f = 0xFF - ai
                val af = (ao * f + ai * ai) / 0xFF
                val rf = (ro * f + ri * ai) / 0xFF
                val gf = (go * f + gi * ai) / 0xFF
                val bf = (bo * f + bi * ai) / 0xFF

                target[ti] = (af shl 24) or (rf shl 16) or (gf shl 8) or bf
            }
        }
    }

    private fun light(
        src: ByteArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int
    ): Int {
        if (x == 0 || x == width - 1 || y == 0 || y == height - 1) {
            return 0
        }
        val i = y * width + x
        val nx = ss * (src[i + 1 - width].i() + 2 * src[i + 1].i() + src[i + 1 + width].i()
                - src[i - 1 - width].i() - 2 * src[i - 1].i() - src[i - 1 + width].i())
        val ny = ss * (src[i - 1 + width].i() + 2 * src[i + width].i() + src[i + 1 + width].i()
                - src[i - 1 - width].i() - 2 * src[i - width].i() - src[i + 1 - width].i())
        val nl = sqrt(nx * nx + ny * ny + 1)
        val dot = (nx * hx + ny * hy + hz) / nl
        val power = dot.pow(specularExponent.toDouble())
        return min(255.0, 255.0f * amplitude * power).toInt()
    }

    companion object {
        private fun Byte.i(): Int = toInt() and 0xFF
    }
}