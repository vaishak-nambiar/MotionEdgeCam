package com.motionedge.cam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * MotionEdgeProcessor
 * -------------------
 * Pure Kotlin/Android image processor — no OpenCV dependency needed.
 *
 * Pipeline per frame:
 *  1. Convert RGB frame → greyscale
 *  2. Gaussian blur (5×5) to suppress sensor noise
 *  3. Absolute diff with previous blurred frame → motion map
 *  4. Threshold motion map by [motionThreshold]
 *  5. Remove small blobs below [noiseSuppression] × 100 px² (connected-component filter)
 *  6. Dilate motion mask (9×9) so it covers object boundaries
 *  7. Canny-style edge detection on blurred grey frame
 *  8. Mask edges with dilated motion mask → only moving edges survive
 *  9. Output: white edges on pure black bitmap
 */
class MotionEdgeProcessor {

    // ── Tuneable parameters (updated live from UI sliders) ─────────────────
    @Volatile var motionThreshold: Int = 20   // 1–80
    @Volatile var noiseSuppression: Int = 5   // 0–20  (multiplied by 100 → min px area)
    @Volatile var edgeThreshold: Int = 20     // 1–80  (Canny lo; hi = lo×3)

    private var prevGrey: IntArray? = null
    private var prevWidth  = 0
    private var prevHeight = 0

    // ── Public entry point ─────────────────────────────────────────────────
    fun process(input: Bitmap): Bitmap {
        val w = input.width
        val h = input.height
        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)

        // Step 1: greyscale
        val grey = toGrey(pixels, w, h)

        // Step 2: Gaussian blur 5×5
        val blurred = gaussianBlur5(grey, w, h)

        // Step 3 & 4: motion diff + threshold
        val motionMask: BooleanArray
        val prev = prevGrey
        if (prev != null && prevWidth == w && prevHeight == h) {
            motionMask = buildMotionMask(blurred, prev, w, h, motionThreshold)
        } else {
            motionMask = BooleanArray(w * h) { false }
        }
        prevGrey   = blurred
        prevWidth  = w
        prevHeight = h

        // Step 5: remove small blobs
        val cleanMask = suppressNoise(motionMask, w, h, noiseSuppression * 100)

        // Step 6: dilate mask
        val dilated = dilateMask(cleanMask, w, h, 9)

        // Step 7: Canny edges
        val edges = cannyEdges(blurred, w, h, edgeThreshold)

        // Step 8 & 9: combine → output bitmap
        return buildOutput(edges, dilated, w, h)
    }

    fun reset() { prevGrey = null }

    // ── Step 1: RGB → greyscale (luminance) ────────────────────────────────
    private fun toGrey(pixels: IntArray, w: Int, h: Int): IntArray {
        val grey = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr  8) and 0xFF
            val b =  c         and 0xFF
            grey[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }
        return grey
    }

    // ── Step 2: Gaussian blur 5×5 ──────────────────────────────────────────
    private val gaussKernel5 = arrayOf(
        intArrayOf(1, 4,  6,  4,  1),
        intArrayOf(4, 16, 24, 16, 4),
        intArrayOf(6, 24, 36, 24, 6),
        intArrayOf(4, 16, 24, 16, 4),
        intArrayOf(1, 4,  6,  4,  1)
    )
    private val gaussSum5 = 256

    private fun gaussianBlur5(src: IntArray, w: Int, h: Int): IntArray {
        val dst = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0
                for (ky in -2..2) {
                    for (kx in -2..2) {
                        val sy = (y + ky).coerceIn(0, h - 1)
                        val sx = (x + kx).coerceIn(0, w - 1)
                        acc += src[sy * w + sx] * gaussKernel5[ky + 2][kx + 2]
                    }
                }
                dst[y * w + x] = acc / gaussSum5
            }
        }
        return dst
    }

    // ── Steps 3&4: motion mask ─────────────────────────────────────────────
    private fun buildMotionMask(
        cur: IntArray, prev: IntArray, w: Int, h: Int, thresh: Int
    ): BooleanArray {
        val mask = BooleanArray(w * h)
        for (i in cur.indices) {
            mask[i] = abs(cur[i] - prev[i]) > thresh
        }
        // Morphological close (3×3) to fill holes
        return morphClose3(mask, w, h)
    }

    private fun morphClose3(src: BooleanArray, w: Int, h: Int): BooleanArray {
        // dilate then erode
        val dilated = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            outer@ for (dy in -1..1) for (dx in -1..1) {
                val ny = (y + dy).coerceIn(0, h - 1)
                val nx = (x + dx).coerceIn(0, w - 1)
                if (src[ny * w + nx]) { dilated[y * w + x] = true; break@outer }
            }
        }
        val eroded = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var allOn = true
            outer@ for (dy in -1..1) for (dx in -1..1) {
                val ny = (y + dy).coerceIn(0, h - 1)
                val nx = (x + dx).coerceIn(0, w - 1)
                if (!dilated[ny * w + nx]) { allOn = false; break@outer }
            }
            eroded[y * w + x] = allOn
        }
        return eroded
    }

    // ── Step 5: noise suppression via connected components ─────────────────
    private fun suppressNoise(mask: BooleanArray, w: Int, h: Int, minArea: Int): BooleanArray {
        if (minArea <= 0) return mask
        val label  = IntArray(w * h) { -1 }
        val areas  = mutableListOf<Int>()
        var nextLabel = 0
        // BFS flood fill
        val queue = ArrayDeque<Int>()
        for (start in mask.indices) {
            if (!mask[start] || label[start] >= 0) continue
            val lbl = nextLabel++
            var area = 0
            queue.add(start)
            label[start] = lbl
            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                area++
                val y = idx / w; val x = idx % w
                for ((dy, dx) in NEIGHBORS4) {
                    val ny = y + dy; val nx = x + dx
                    if (ny < 0 || ny >= h || nx < 0 || nx >= w) continue
                    val ni = ny * w + nx
                    if (mask[ni] && label[ni] < 0) { label[ni] = lbl; queue.add(ni) }
                }
            }
            areas.add(area)
        }
        val result = BooleanArray(w * h)
        for (i in mask.indices) {
            if (label[i] >= 0 && areas[label[i]] >= minArea) result[i] = true
        }
        return result
    }

    // ── Step 6: dilation ───────────────────────────────────────────────────
    private fun dilateMask(src: BooleanArray, w: Int, h: Int, kernelSize: Int): BooleanArray {
        val half = kernelSize / 2
        val dst  = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                outer@ for (dy in -half..half) {
                    for (dx in -half..half) {
                        val ny = (y + dy).coerceIn(0, h - 1)
                        val nx = (x + dx).coerceIn(0, w - 1)
                        if (src[ny * w + nx]) { dst[y * w + x] = true; break@outer }
                    }
                }
            }
        }
        return dst
    }

    // ── Step 7: Canny edge detection ───────────────────────────────────────
    private fun cannyEdges(grey: IntArray, w: Int, h: Int, loThresh: Int): BooleanArray {
        val hiThresh = loThresh * 3

        // Sobel gradients
        val mag   = IntArray(w * h)
        val angle = IntArray(w * h) // 0=H, 1=D+, 2=V, 3=D-
        val sobelX = arrayOf(intArrayOf(-1,0,1), intArrayOf(-2,0,2), intArrayOf(-1,0,1))
        val sobelY = arrayOf(intArrayOf(-1,-2,-1), intArrayOf(0,0,0), intArrayOf(1,2,1))

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var gx = 0; var gy = 0
                for (ky in -1..1) for (kx in -1..1) {
                    val v = grey[(y + ky) * w + (x + kx)]
                    gx += v * sobelX[ky + 1][kx + 1]
                    gy += v * sobelY[ky + 1][kx + 1]
                }
                mag[y * w + x] = sqrt((gx * gx + gy * gy).toDouble()).toInt()
                // Quantise angle to 4 directions
                val deg = Math.toDegrees(Math.atan2(gy.toDouble(), gx.toDouble())).toFloat()
                val normDeg = ((deg % 180) + 180) % 180
                angle[y * w + x] = when {
                    normDeg < 22.5 || normDeg >= 157.5 -> 0
                    normDeg < 67.5  -> 1
                    normDeg < 112.5 -> 2
                    else            -> 3
                }
            }
        }

        // Non-maximum suppression
        val suppressed = IntArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val m = mag[y * w + x]
                val (n1, n2) = when (angle[y * w + x]) {
                    0    -> Pair(mag[y * w + x - 1],     mag[y * w + x + 1])
                    1    -> Pair(mag[(y - 1) * w + x + 1], mag[(y + 1) * w + x - 1])
                    2    -> Pair(mag[(y - 1) * w + x],   mag[(y + 1) * w + x])
                    else -> Pair(mag[(y - 1) * w + x - 1], mag[(y + 1) * w + x + 1])
                }
                suppressed[y * w + x] = if (m >= n1 && m >= n2) m else 0
            }
        }

        // Double threshold + hysteresis
        val strong = BooleanArray(w * h)
        val weak   = BooleanArray(w * h)
        for (i in suppressed.indices) {
            when {
                suppressed[i] >= hiThresh -> strong[i] = true
                suppressed[i] >= loThresh -> weak[i]   = true
            }
        }
        // Promote weak pixels connected to strong
        val edges = strong.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            if (!weak[y * w + x]) continue
            outer@ for (dy in -1..1) for (dx in -1..1) {
                if (strong[(y + dy) * w + (x + dx)]) { edges[y * w + x] = true; break@outer }
            }
        }
        return edges
    }

    // ── Steps 8&9: build output bitmap ─────────────────────────────────────
    private fun buildOutput(edges: BooleanArray, mask: BooleanArray, w: Int, h: Int): Bitmap {
        val out = IntArray(w * h) { Color.BLACK }
        for (i in edges.indices) {
            if (edges[i] && mask[i]) out[i] = Color.WHITE
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    companion object {
        private val NEIGHBORS4 = listOf(Pair(-1,0), Pair(1,0), Pair(0,-1), Pair(0,1))
    }
}
