package dev.pocof4.camera.ai

/**
 * Separable box blur over an 8-bit single-channel plane.
 *
 * Used by [BeautyProcessor] as the cheap "guided" smoothing operator: the blurred
 * plane is blended back into the source with an edge-stop weight. Two O(N) sliding
 * window passes (horizontal then vertical) avoid the O(N*r^2) cost of a real bilateral
 * filter, and the window is clipped at the borders instead of sampling outside.
 *
 * Pure Kotlin: no android.* imports.
 */
object BoxBlur {

    fun blur(src: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val count = width * height
        if (width <= 0 || height <= 0 || src.size < count) return src.copyOf()
        if (radius <= 0) return src.copyOf()

        val horizontal = IntArray(count)
        for (y in 0 until height) {
            val row = y * width
            var sum = 0L
            var window = 0
            val firstEnd = minOf(radius, width - 1)
            for (x in 0..firstEnd) {
                sum += src[row + x]
                window++
            }
            for (x in 0 until width) {
                horizontal[row + x] = ((sum + window / 2) / window).toInt()
                val add = x + radius + 1
                if (add < width) {
                    sum += src[row + add]
                    window++
                }
                val remove = x - radius
                if (remove >= 0) {
                    sum -= src[row + remove]
                    window--
                }
            }
        }

        val dst = IntArray(count)
        for (x in 0 until width) {
            var sum = 0L
            var window = 0
            val firstEnd = minOf(radius, height - 1)
            for (y in 0..firstEnd) {
                sum += horizontal[y * width + x]
                window++
            }
            for (y in 0 until height) {
                dst[y * width + x] = ((sum + window / 2) / window).toInt()
                val add = y + radius + 1
                if (add < height) {
                    sum += horizontal[add * width + x]
                    window++
                }
                val remove = y - radius
                if (remove >= 0) {
                    sum -= horizontal[remove * width + x]
                    window--
                }
            }
        }
        return dst
    }
}
