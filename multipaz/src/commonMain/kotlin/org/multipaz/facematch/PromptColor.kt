package org.multipaz.facematch

/**
 * Platform-independent 32-bit ARGB color value used by prompt dialogs and models.
 *
 * @property argb 32-bit ARGB integer encoded as Long (e.g. 0xFF00FF00L for opaque green).
 */
data class PromptColor(val argb: Long) {
    /** Alpha component in range 0.0f..1.0f. */
    val alpha: Float get() = ((argb shr 24) and 0xFF) / 255f

    /** Red component in range 0.0f..1.0f. */
    val red: Float get() = ((argb shr 16) and 0xFF) / 255f

    /** Green component in range 0.0f..1.0f. */
    val green: Float get() = ((argb shr 8) and 0xFF) / 255f

    /** Blue component in range 0.0f..1.0f. */
    val blue: Float get() = (argb and 0xFF) / 255f

    companion object {
        val TRANSPARENT = PromptColor(0x00000000L)
        val BLACK = PromptColor(0xFF000000L)
        val WHITE = PromptColor(0xFFFFFFFFL)
        val DARK_GRAY = PromptColor(0xFF2E2E2EL)
        val GRAY = PromptColor(0xFF757575L)
        val LIGHT_GRAY = PromptColor(0xFFBDBDBDL)
        val GREEN = PromptColor(0xFF00C853L)
        val BRIGHT_GREEN = PromptColor(0xFF69F0AEL)
        val RED = PromptColor(0xFFD50000L)
        val BLUE = PromptColor(0xFF2979FFL)

        /**
         * Creates a [PromptColor] from individual 0..255 RGB channels and optional alpha.
         */
        fun fromRgba(red: Int, green: Int, blue: Int, alpha: Int = 255): PromptColor {
            val a = (alpha.coerceIn(0, 255).toLong() and 0xFF) shl 24
            val r = (red.coerceIn(0, 255).toLong() and 0xFF) shl 16
            val g = (green.coerceIn(0, 255).toLong() and 0xFF) shl 8
            val b = (blue.coerceIn(0, 255).toLong() and 0xFF)
            return PromptColor(a or r or g or b)
        }

        /**
         * Linearly interpolates between two [PromptColor]s.
         *
         * @param start the starting color when fraction is 0.0.
         * @param end the ending color when fraction is 1.0.
         * @param fraction progress from 0.0 to 1.0.
         */
        fun lerp(start: PromptColor, end: PromptColor, fraction: Float): PromptColor {
            val f = fraction.coerceIn(0f, 1f)
            val a = start.alpha + (end.alpha - start.alpha) * f
            val r = start.red + (end.red - start.red) * f
            val g = start.green + (end.green - start.green) * f
            val b = start.blue + (end.blue - start.blue) * f
            val aInt = (a * 255f + 0.5f).toInt().coerceIn(0, 255).toLong() shl 24
            val rInt = (r * 255f + 0.5f).toInt().coerceIn(0, 255).toLong() shl 16
            val gInt = (g * 255f + 0.5f).toInt().coerceIn(0, 255).toLong() shl 8
            val bInt = (b * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
            return PromptColor(aInt or rInt or gInt or bInt)
        }
    }
}
