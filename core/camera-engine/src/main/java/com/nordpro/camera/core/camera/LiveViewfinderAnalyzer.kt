package com.nordpro.camera.core.camera

import android.graphics.RectF
import android.media.Image
import kotlin.math.abs

data class ViewfinderTelemetry(
    val lumaHistogram: IntArray, // 64 bins normalized 0..100
    val clippedHighlightPercent: Float,
    val focusPeakingPoints: FloatArray, // packed x, y normalized [0..1] pairs
    val detectedFaces: List<RectF> = emptyList(), // normalized [0..1] face/eye tracking boxes
    val trackedObject: RectF? = null // normalized [0..1] tapped object tracking box
)

/**
 * Lightweight real-time Y-plane analyzer for live Histogram, Zebra exposure warning,
 * Sobel gradient Focus Peaking, and fast Object Tracking on the viewfinder.
 */
class LiveViewfinderAnalyzer {

    private var templatePatch: IntArray? = null
    private var templateSize = 12
    private var trackNormX = -1f
    private var trackNormY = -1f
    private var isFrontCamera = false

    fun startTracking(viewNormX: Float, viewNormY: Float, frontCamera: Boolean) {
        isFrontCamera = frontCamera
        // Convert portrait viewfinder [0..1] to landscape YUV buffer [0..1]
        if (frontCamera) {
            trackNormX = 1f - viewNormY
            trackNormY = 1f - viewNormX
        } else {
            trackNormX = viewNormY
            trackNormY = 1f - viewNormX
        }
        templatePatch = null
    }

    fun clearTracking() {
        trackNormX = -1f
        trackNormY = -1f
        templatePatch = null
    }

    fun isTrackingActive(): Boolean = trackNormX >= 0f && trackNormY >= 0f

    fun analyzeYuvFrame(
        image: Image,
        computeHistogramOrPeaking: Boolean,
        computePeaking: Boolean,
        zebraThreshold: Float
    ): ViewfinderTelemetry {
        val yPlane = image.planes[0]
        val buffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val width = image.width
        val height = image.height

        var normalized = IntArray(64)
        var clippedPct = 0f
        var peakArray = FloatArray(0)

        if (computeHistogramOrPeaking) {
            val bins = IntArray(64)
            var totalSamples = 0
            var clippedCount = 0
            val zebraByte = (zebraThreshold.coerceIn(0.75f, 0.99f) * 255f).toInt()
            val step = 6
            val peakCoords = ArrayList<Float>(256)

            for (y in step until height - step step step) {
                val rowOffset = y * rowStride
                for (x in step until width - step step step) {
                    val idx = rowOffset + x * pixelStride
                    val luma = buffer.get(idx).toInt() and 0xFF
                    bins[luma shr 2]++
                    totalSamples++
                    if (luma >= zebraByte) clippedCount++

                    if (computePeaking && peakCoords.size < 1024) {
                        val left = buffer.get(rowOffset + (x - step) * pixelStride).toInt() and 0xFF
                        val right = buffer.get(rowOffset + (x + step) * pixelStride).toInt() and 0xFF
                        val up = buffer.get((y - step) * rowStride + x * pixelStride).toInt() and 0xFF
                        val down = buffer.get((y + step) * rowStride + x * pixelStride).toInt() and 0xFF
                        val grad = abs(right - left) + abs(down - up)
                        if (grad > 68) {
                            peakCoords.add(x.toFloat() / width)
                            peakCoords.add(y.toFloat() / height)
                        }
                    }
                }
            }
            val maxBin = bins.maxOrNull()?.coerceAtLeast(1) ?: 1
            normalized = IntArray(64) { (bins[it] * 100) / maxBin }
            clippedPct = if (totalSamples > 0) (clippedCount * 100f) / totalSamples else 0f
            peakArray = peakCoords.toFloatArray()
        }

        // Fast SAD template object tracking in Y-plane
        var trackedRect: RectF? = null
        if (isTrackingActive() && width > 64 && height > 64) {
            val cx = (trackNormX.coerceIn(0.08f, 0.92f) * width).toInt()
            val cy = (trackNormY.coerceIn(0.08f, 0.92f) * height).toInt()
            val half = templateSize / 2
            val patchStep = 3

            val currentTemplate = templatePatch
            if (currentTemplate == null) {
                val newPatch = IntArray(templateSize * templateSize)
                var k = 0
                for (dy in -half until half) {
                    val py = (cy + dy * patchStep).coerceIn(0, height - 1)
                    for (dx in -half until half) {
                        val px = (cx + dx * patchStep).coerceIn(0, width - 1)
                        newPatch[k++] = buffer.get(py * rowStride + px * pixelStride).toInt() and 0xFF
                    }
                }
                templatePatch = newPatch
            } else {
                var bestX = cx
                var bestY = cy
                var bestSad = Int.MAX_VALUE
                val searchRadius = 36
                for (sy in (cy - searchRadius)..(cy + searchRadius) step 4) {
                    if (sy - half * patchStep < 0 || sy + half * patchStep >= height) continue
                    for (sx in (cx - searchRadius)..(cx + searchRadius) step 4) {
                        if (sx - half * patchStep < 0 || sx + half * patchStep >= width) continue
                        var sad = 0
                        var k = 0
                        for (dy in -half until half) {
                            val py = sy + dy * patchStep
                            val rowOff = py * rowStride
                            for (dx in -half until half) {
                                val px = sx + dx * patchStep
                                val v = buffer.get(rowOff + px * pixelStride).toInt() and 0xFF
                                sad += abs(v - currentTemplate[k++])
                            }
                            if (sad > bestSad) break
                        }
                        if (sad < bestSad) {
                            bestSad = sad
                            bestX = sx
                            bestY = sy
                        }
                    }
                }
                trackNormX = bestX.toFloat() / width
                trackNormY = bestY.toFloat() / height
            }

            // Convert back to portrait viewfinder [0..1]
            val vx = if (isFrontCamera) 1f - trackNormY else 1f - trackNormY
            val vy = if (isFrontCamera) 1f - trackNormX else trackNormX
            val boxHalf = 0.08f
            trackedRect = RectF(
                (vx - boxHalf).coerceIn(0f, 1f),
                (vy - boxHalf).coerceIn(0f, 1f),
                (vx + boxHalf).coerceIn(0f, 1f),
                (vy + boxHalf).coerceIn(0f, 1f)
            )
        }

        return ViewfinderTelemetry(
            lumaHistogram = normalized,
            clippedHighlightPercent = clippedPct,
            focusPeakingPoints = peakArray,
            trackedObject = trackedRect
        )
    }
}
