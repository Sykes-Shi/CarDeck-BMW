package io.github.shaonialife.cardeck.media

/** 从小样本提取深色基调，白封面也不会把文字背景冲成白色。 */
internal object ArtworkTone {
    const val DEFAULT = 0xFF111820.toInt()

    fun fromPixels(pixels: IntArray): Int {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        pixels.forEach { color ->
            if ((color ushr 24) >= 128) {
                red += color ushr 16 and 255
                green += color ushr 8 and 255
                blue += color and 255
                count++
            }
        }
        if (count == 0) return DEFAULT
        fun channel(sum: Long, base: Int): Int = ((sum / count) * 0.30 + base * 0.70).toInt().coerceIn(0, 95)
        return (255 shl 24) or (channel(red, 17) shl 16) or (channel(green, 24) shl 8) or channel(blue, 32)
    }
}
