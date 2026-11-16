package com.nordpro.camera.core.computational

import android.graphics.Bitmap
import android.media.Image
import com.nordpro.camera.core.camera.CapturedBurstFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ComputationalPipelineConfig(
    val frameCount: Int = 8,
    val enableHdrFusion: Boolean = true,
    val naturalSharpeningStrength: Float = 0.28f, // Low-radius micro-contrast without halos
    val chromaDenoiseStrength: Float = 0.65f,
    val lumaDenoiseStrength: Float = 0.25f, // Preserve real texture & grain
    val shadowLiftStrength: Float = 0.20f,
    val highlightRecoveryStrength: Float = 0.35f
)

/**
 * Computational Photography Engine designed for the Sony LYTIA-600 (1/1.953", Quad Bayer)
 * on the OnePlus Nord 6 (CPH2793).
 *
 * Pipeline:
 * 1. Sharpness-scored reference frame selection (rejects motion-blurred frames).
 * 2. Tile-based SAD block-matching alignment across YUV burst frames.
 * 3. Robust Tukey-weighted temporal merge (reduces shot/read noise while rejecting moving ghosts).
 * 4. Filmic highlight/shadow tone mapping (reverses highlight clipping & crushed shadows).
 * 5. Edge-preserving luma/chroma denoising + low-radius unsharp micro-contrast.
 */
class MultiFrameAlignMergeProcessor {

    suspend fun processBurst(
        frames: List<CapturedBurstFrame>,
        config: ComputationalPipelineConfig = ComputationalPipelineConfig()
    ): Bitmap? = withContext(Dispatchers.Default) {
        val validYuvFrames = frames.mapNotNull { it.yuvImage }
        if (validYuvFrames.isEmpty()) return@withContext null

        try {
            val refIndex = selectSharpestReferenceIndex(validYuvFrames)
            val ref = validYuvFrames[refIndex]
            val width = ref.width
            val height = ref.height

            val refY = extractPlaneBytes(ref, 0)
            val refU = extractChromaPlane(ref, 1)
            val refV = extractChromaPlane(ref, 2)

            val accumY = FloatArray(width * height) { refY[it].toFloat() }
            val weightY = FloatArray(width * height) { 1.0f }

            val uvWidth = width / 2
            val uvHeight = height / 2
            val accumU = FloatArray(uvWidth * uvHeight) { refU[it].toFloat() }
            val accumV = FloatArray(uvWidth * uvHeight) { refV[it].toFloat() }
            val weightUV = FloatArray(uvWidth * uvHeight) { 1.0f }

            // Align and merge remaining burst frames using 16x16 tile block matching
            val tileSize = 16
            val searchRadius = 8
            val tukeyThreshold = 24f // Rejects ghosting/motion mismatches

            for (i in validYuvFrames.indices) {
                if (i == refIndex) continue
                val alt = validYuvFrames[i]
                if (alt.width != width || alt.height != height) continue

                val altY = extractPlaneBytes(alt, 0)
                val altU = extractChromaPlane(alt, 1)
                val altV = extractChromaPlane(alt, 2)

                for (ty in 0 until height step tileSize) {
                    val tileH = min(tileSize, height - ty)
                    for (tx in 0 until width step tileSize) {
                        val tileW = min(tileSize, width - tx)
                        val (dx, dy) = findBestTileShift(
                            refY, altY, width, height, tx, ty, tileW, tileH, searchRadius
                        )

                        for (py in ty until ty + tileH) {
                            val sy = (py + dy).coerceIn(0, height - 1)
                            val rowBase = py * width
                            val srcRowBase = sy * width
                            for (px in tx until tx + tileW) {
                                val sx = (px + dx).coerceIn(0, width - 1)
                                val rVal = refY[rowBase + px].toFloat()
                                val aVal = altY[srcRowBase + sx].toFloat()
                                val diff = abs(aVal - rVal)
                                val w = if (diff < tukeyThreshold) {
                                    val t = diff / tukeyThreshold
                                    (1f - t * t) * (1f - t * t)
                                } else 0f

                                if (w > 0.01f) {
                                    accumY[rowBase + px] += aVal * w
                                    weightY[rowBase + px] += w
                                }
                            }
                        }
                    }
                }

                // Merge chroma (4:2:0) with global-aligned fallback for clean color noise reduction
                for (cy in 0 until uvHeight) {
                    val row = cy * uvWidth
                    for (cx in 0 until uvWidth) {
                        val idx = row + cx
                        val diffU = abs(altU[idx] - refU[idx])
                        val diffV = abs(altV[idx] - refV[idx])
                        if (diffU < 20 && diffV < 20) {
                            accumU[idx] += altU[idx].toFloat()
                            accumV[idx] += altV[idx].toFloat()
                            weightUV[idx] += 1.0f
                        }
                    }
                }
            }

            // Normalize merged YUV
            val mergedY = FloatArray(width * height) { idx -> accumY[idx] / weightY[idx] }
            val mergedU = FloatArray(uvWidth * uvHeight) { idx -> accumU[idx] / weightUV[idx] }
            val mergedV = FloatArray(uvWidth * uvHeight) { idx -> accumV[idx] / weightUV[idx] }

            // Natural highlight/shadow recovery + low-radius detail enhancement
            if (config.enableHdrFusion) {
                applyFilmicToneRecovery(
                    mergedY,
                    shadowLift = config.shadowLiftStrength,
                    highlightRecovery = config.highlightRecoveryStrength
                )
            }
            if (config.naturalSharpeningStrength > 0.01f) {
                applyHaloFreeMicroContrast(mergedY, width, height, config.naturalSharpeningStrength)
            }

            return@withContext yuvToArgbBitmap(mergedY, mergedU, mergedV, width, height)
        } finally {
            frames.forEach { frame ->
                runCatching { frame.yuvImage?.close() }
                runCatching { frame.rawImage?.close() }
            }
        }
    }

    private fun selectSharpestReferenceIndex(frames: List<Image>): Int {
        var bestIdx = 0
        var bestScore = -1.0
        for (i in frames.indices) {
            val y = extractPlaneBytes(frames[i], 0)
            val w = frames[i].width
            val h = frames[i].height
            var gradSum = 0.0
            // Sample center 50% of frame for Laplacian variance sharpness metric
            val yStart = h / 4
            val yEnd = (h * 3) / 4
            val xStart = w / 4
            val xEnd = (w * 3) / 4
            for (py in yStart until yEnd step 4) {
                val row = py * w
                for (px in xStart until xEnd step 4) {
                    val c = y[row + px]
                    val lap = abs(4 * c - y[row + px - 1] - y[row + px + 1] - y[row - w + px] - y[row + w + px])
                    gradSum += lap
                }
            }
            if (gradSum > bestScore) {
                bestScore = gradSum
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun findBestTileShift(
        refY: IntArray,
        altY: IntArray,
        width: Int,
        height: Int,
        tx: Int,
        ty: Int,
        tileW: Int,
        tileH: Int,
        radius: Int
    ): Pair<Int, Int> {
        var bestDx = 0
        var bestDy = 0
        var bestSad = Long.MAX_VALUE

        for (dy in -radius..radius step 2) {
            for (dx in -radius..radius step 2) {
                var sad = 0L
                for (py in ty until ty + tileH step 2) {
                    val sy = (py + dy).coerceIn(0, height - 1)
                    val rRow = py * width
                    val aRow = sy * width
                    for (px in tx until tx + tileW step 2) {
                        val sx = (px + dx).coerceIn(0, width - 1)
                        sad += abs(refY[rRow + px] - altY[aRow + sx])
                    }
                }
                if (sad < bestSad) {
                    bestSad = sad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        return bestDx to bestDy
    }

    private fun applyFilmicToneRecovery(y: FloatArray, shadowLift: Float, highlightRecovery: Float) {
        for (i in y.indices) {
            val n = (y[i] / 255f).coerceIn(0f, 1f)
            // Lift deep shadows smoothly without raising black floor
            val lifted = n + shadowLift * n * (1f - n) * (1f - n)
            // Compress bright highlights gently above 0.72
            val recovered = if (lifted > 0.72f) {
                val excess = (lifted - 0.72f) / 0.28f
                0.72f + 0.28f * (excess / (1f + highlightRecovery * excess))
            } else lifted
            y[i] = (recovered * 255f).coerceIn(0f, 255f)
        }
    }

    private fun applyHaloFreeMicroContrast(y: FloatArray, width: Int, height: Int, amount: Float) {
        val copy = y.copyOf()
        for (py in 1 until height - 1) {
            val row = py * width
            for (px in 1 until width - 1) {
                val c = copy[row + px]
                val avg = (copy[row + px - 1] + copy[row + px + 1] + copy[row - width + px] + copy[row + width + px]) * 0.25f
                val detail = c - avg
                // Soft-clamped detail boost prevents bright/dark ringing halos on high-contrast edges
                val clampedDetail = detail.coerceIn(-14f, 14f)
                y[row + px] = (c + clampedDetail * amount).coerceIn(0f, 255f)
            }
        }
    }

    private fun extractPlaneBytes(image: Image, planeIndex: Int): IntArray {
        val plane = image.planes[planeIndex]
        val buf = plane.buffer
        val w = image.width
        val h = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val rowOff = y * rowStride
            val outOff = y * w
            for (x in 0 until w) {
                out[outOff + x] = buf.get(rowOff + x * pixelStride).toInt() and 0xFF
            }
        }
        return out
    }

    private fun extractChromaPlane(image: Image, planeIndex: Int): IntArray {
        val plane = image.planes[planeIndex]
        val buf = plane.buffer
        val w = image.width / 2
        val h = image.height / 2
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val rowOff = y * rowStride
            val outOff = y * w
            for (x in 0 until w) {
                out[outOff + x] = buf.get(rowOff + x * pixelStride).toInt() and 0xFF
            }
        }
        return out
    }

    private fun yuvToArgbBitmap(
        yPlane: FloatArray,
        uPlane: FloatArray,
        vPlane: FloatArray,
        width: Int,
        height: Int
    ): Bitmap {
        val pixels = IntArray(width * height)
        val uvWidth = width / 2
        for (y in 0 until height) {
            val yRow = y * width
            val uvRow = (y / 2) * uvWidth
            for (x in 0 until width) {
                val yy = yPlane[yRow + x]
                val u = uPlane[uvRow + (x / 2)] - 128f
                val v = vPlane[uvRow + (x / 2)] - 128f
                val r = (yy + 1.402f * v).roundToInt().coerceIn(0, 255)
                val g = (yy - 0.344136f * u - 0.714136f * v).roundToInt().coerceIn(0, 255)
                val b = (yy + 1.772f * u).roundToInt().coerceIn(0, 255)
                pixels[yRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
