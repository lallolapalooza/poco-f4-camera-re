package dev.pocof4.camera.photo

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Allocation-free scalar helpers shared by the computational-photography cores.
 *
 * Everything here is pure Kotlin/JVM (no android.*) so the algorithms can be
 * exercised by plain JUnit tests. Colour components are 8-bit and images are
 * packed ARGB_8888, the same layout as [android.graphics.Bitmap.getPixels].
 */
object ImageMath {

    fun luma(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (77 * r + 150 * g + 29 * b) ushr 8
    }

    fun lumaPlane(argb: IntArray, width: Int, height: Int): IntArray {
        val n = width * height
        val out = IntArray(n)
        for (i in 0 until n) out[i] = luma(argb[i])
        return out
    }

    fun channelPlane(argb: IntArray, width: Int, height: Int, shift: Int): FloatArray {
        val n = width * height
        val out = FloatArray(n)
        val inv = 1f / 255f
        for (i in 0 until n) out[i] = ((argb[i] ushr shift) and 0xFF) * inv
        return out
    }

    /** |dx|+|dy| luma gradient, clamped to 0..255. Translation invariant. */
    fun gradientPlane(luma: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val ym = if (y > 0) y - 1 else 0
            val yp = if (y < height - 1) y + 1 else height - 1
            val row = y * width
            for (x in 0 until width) {
                val xm = if (x > 0) x - 1 else 0
                val xp = if (x < width - 1) x + 1 else width - 1
                val gx = luma[row + xp] - luma[row + xm]
                val gy = luma[yp * width + x] - luma[ym * width + x]
                out[row + x] = min(255, (abs(gx) + abs(gy)) shr 1)
            }
        }
        return out
    }

    /** 2x2 box downsample. Result dimensions are max(1, w/2) x max(1, h/2). */
    fun downsampleLuma(src: IntArray, width: Int, height: Int): IntArray {
        val dw = max(1, width / 2)
        val dh = max(1, height / 2)
        val out = IntArray(dw * dh)
        for (y in 0 until dh) {
            val y0 = min(height - 1, y * 2)
            val y1 = min(height - 1, y0 + 1)
            for (x in 0 until dw) {
                val x0 = min(width - 1, x * 2)
                val x1 = min(width - 1, x0 + 1)
                out[y * dw + x] =
                    (src[y0 * width + x0] + src[y0 * width + x1] +
                        src[y1 * width + x0] + src[y1 * width + x1] + 2) shr 2
            }
        }
        return out
    }

    /** In-place 3-pass separable box blur, a cheap Gaussian approximation. */
    fun blurPlaneInPlace(plane: IntArray, scratch: IntArray, width: Int, height: Int, radius: Int) {
        if (radius <= 0) return
        repeat(3) {
            boxBlurH(plane, scratch, width, height, radius)
            boxBlurV(scratch, plane, width, height, radius)
        }
    }

    fun blurFloatInPlace(plane: FloatArray, scratch: FloatArray, width: Int, height: Int, radius: Int) {
        if (radius <= 0) return
        repeat(3) {
            boxBlurFloatH(plane, scratch, width, height, radius)
            boxBlurFloatV(scratch, plane, width, height, radius)
        }
    }

    fun blurFloat(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return src.copyOf()
        val out = src.copyOf()
        val scratch = FloatArray(src.size)
        blurFloatInPlace(out, scratch, width, height, radius)
        return out
    }

    private fun boxBlurH(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
        val r = min(radius, width - 1)
        if (r <= 0) {
            src.copyInto(dst)
            return
        }
        val window = 2 * r + 1
        for (y in 0 until height) {
            val row = y * width
            var sum = 0
            for (k in -r..r) sum += src[row + k.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                dst[row + x] = sum / window
                sum += src[row + (x + r + 1).coerceIn(0, width - 1)]
                sum -= src[row + (x - r).coerceIn(0, width - 1)]
            }
        }
    }

    private fun boxBlurV(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
        val r = min(radius, height - 1)
        if (r <= 0) {
            src.copyInto(dst)
            return
        }
        val window = 2 * r + 1
        for (x in 0 until width) {
            var sum = 0
            for (k in -r..r) sum += src[k.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                dst[y * width + x] = sum / window
                sum += src[(y + r + 1).coerceIn(0, height - 1) * width + x]
                sum -= src[(y - r).coerceIn(0, height - 1) * width + x]
            }
        }
    }

    private fun boxBlurFloatH(src: FloatArray, dst: FloatArray, width: Int, height: Int, radius: Int) {
        val r = min(radius, width - 1)
        if (r <= 0) {
            src.copyInto(dst)
            return
        }
        val window = (2 * r + 1).toFloat()
        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            for (k in -r..r) sum += src[row + k.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                dst[row + x] = sum / window
                sum += src[row + (x + r + 1).coerceIn(0, width - 1)]
                sum -= src[row + (x - r).coerceIn(0, width - 1)]
            }
        }
    }

    private fun boxBlurFloatV(src: FloatArray, dst: FloatArray, width: Int, height: Int, radius: Int) {
        val r = min(radius, height - 1)
        if (r <= 0) {
            src.copyInto(dst)
            return
        }
        val window = (2 * r + 1).toFloat()
        for (x in 0 until width) {
            var sum = 0f
            for (k in -r..r) sum += src[k.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                dst[y * width + x] = sum / window
                sum += src[(y + r + 1).coerceIn(0, height - 1) * width + x]
                sum -= src[(y - r).coerceIn(0, height - 1) * width + x]
            }
        }
    }

    /** 5-tap [1 4 6 4 1]/16 separable blur used before pyramid downsample. */
    fun blur5Float(src: FloatArray, width: Int, height: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val xm2 = (x - 2).coerceIn(0, width - 1)
                val xm1 = (x - 1).coerceIn(0, width - 1)
                val xp1 = (x + 1).coerceIn(0, width - 1)
                val xp2 = (x + 2).coerceIn(0, width - 1)
                tmp[row + x] = (
                    src[row + xm2] + 4f * src[row + xm1] + 6f * src[row + x] +
                        4f * src[row + xp1] + src[row + xp2]
                    ) * 0.0625f
            }
        }
        for (y in 0 until height) {
            val ym2 = (y - 2).coerceIn(0, height - 1)
            val ym1 = (y - 1).coerceIn(0, height - 1)
            val yp1 = (y + 1).coerceIn(0, height - 1)
            val yp2 = (y + 2).coerceIn(0, height - 1)
            for (x in 0 until width) {
                out[y * width + x] = (
                    tmp[ym2 * width + x] + 4f * tmp[ym1 * width + x] +
                        6f * tmp[y * width + x] + 4f * tmp[yp1 * width + x] +
                        tmp[yp2 * width + x]
                    ) * 0.0625f
            }
        }
        return out
    }

    /** Blur + subsample by two. Result dimensions are max(1, w/2) x max(1, h/2). */
    fun downsampleFloat(src: FloatArray, width: Int, height: Int): FloatArray {
        val blurred = blur5Float(src, width, height)
        val dw = max(1, width / 2)
        val dh = max(1, height / 2)
        val out = FloatArray(dw * dh)
        for (y in 0 until dh) {
            val sy = min(height - 1, y * 2)
            val row = sy * width
            for (x in 0 until dw) {
                out[y * dw + x] = blurred[row + min(width - 1, x * 2)]
            }
        }
        return out
    }

    /** Bilinear resample, edge clamped. Allocations are per plane, never per pixel. */
    fun upsampleFloat(
        src: FloatArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int
    ): FloatArray {
        if (srcWidth == dstWidth && srcHeight == dstHeight) return src.copyOf()
        val out = FloatArray(dstWidth * dstHeight)
        val xScale = srcWidth.toFloat() / dstWidth
        val yScale = srcHeight.toFloat() / dstHeight
        for (y in 0 until dstHeight) {
            val fy = ((y + 0.5f) * yScale - 0.5f).coerceIn(0f, (srcHeight - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(srcHeight - 1, y0 + 1)
            val ty = fy - y0
            val row0 = y0 * srcWidth
            val row1 = y1 * srcWidth
            val dstRow = y * dstWidth
            for (x in 0 until dstWidth) {
                val fx = ((x + 0.5f) * xScale - 0.5f).coerceIn(0f, (srcWidth - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(srcWidth - 1, x0 + 1)
                val tx = fx - x0
                val top = src[row0 + x0] + (src[row0 + x1] - src[row0 + x0]) * tx
                val bottom = src[row1 + x0] + (src[row1 + x1] - src[row1 + x0]) * tx
                out[dstRow + x] = top + (bottom - top) * ty
            }
        }
        return out
    }

    fun lerpArgb(c0: Int, c1: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val inv = 1f - f
        val a = (((c0 ushr 24) and 0xFF) * inv + ((c1 ushr 24) and 0xFF) * f).roundToInt()
        val r = (((c0 ushr 16) and 0xFF) * inv + ((c1 ushr 16) and 0xFF) * f).roundToInt()
        val g = (((c0 ushr 8) and 0xFF) * inv + ((c1 ushr 8) and 0xFF) * f).roundToInt()
        val b = ((c0 and 0xFF) * inv + (c1 and 0xFF) * f).roundToInt()
        return (a.coerceIn(0, 255) shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)
    }

    /** Bilinear ARGB resize used to cap the saliency working resolution. */
    fun resizeArgb(
        src: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int
    ): IntArray {
        if (srcWidth == dstWidth && srcHeight == dstHeight) return src.copyOf()
        val out = IntArray(dstWidth * dstHeight)
        val xScale = srcWidth.toFloat() / dstWidth
        val yScale = srcHeight.toFloat() / dstHeight
        for (y in 0 until dstHeight) {
            val fy = ((y + 0.5f) * yScale - 0.5f).coerceIn(0f, (srcHeight - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(srcHeight - 1, y0 + 1)
            val ty = fy - y0
            for (x in 0 until dstWidth) {
                val fx = ((x + 0.5f) * xScale - 0.5f).coerceIn(0f, (srcWidth - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(srcWidth - 1, x0 + 1)
                val tx = fx - x0
                val c00 = src[y0 * srcWidth + x0]
                val c01 = src[y0 * srcWidth + x1]
                val c10 = src[y1 * srcWidth + x0]
                val c11 = src[y1 * srcWidth + x1]
                val top = lerpArgb(c00, c01, tx)
                val bottom = lerpArgb(c10, c11, tx)
                out[y * dstWidth + x] = lerpArgb(top, bottom, ty)
            }
        }
        return out
    }
}
