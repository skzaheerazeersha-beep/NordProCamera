package com.nordpro.camera.core.camera

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import android.view.Surface
import kotlin.math.abs
import kotlin.math.max

/**
 * Exact Camera2 TextureView center-crop, rotation, and sensor-coordinate mapping helper.
 */
object CameraTransformHelper {

    fun chooseOptimalPreviewSize(
        characteristics: CameraCharacteristics,
        targetAspect: Float = 4f / 3f
    ): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1440, 1080)
        val choices = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (choices.isEmpty()) return Size(1440, 1080)

        val safeTarget = if (targetAspect.isNaN() || targetAspect <= 0f) 4f / 3f else targetAspect

        // If target is 16:9 (video), prefer 1920x1080; otherwise prefer native 4:3 (1920x1440 or 1440x1080)
        val desiredRatio = if (abs(safeTarget - 16f / 9f) < 0.1f) 16f / 9f else 4f / 3f
        val matched = choices.filter { size ->
            val r = size.width.toFloat() / size.height.toFloat()
            abs(r - desiredRatio) < 0.06f && size.width in 720..1920
        }
        return matched.maxByOrNull { it.width * it.height }
            ?: choices.filter { size ->
                val r = size.width.toFloat() / size.height.toFloat()
                abs(r - (4f / 3f)) < 0.08f && size.width <= 1920
            }.maxByOrNull { it.width * it.height }
            ?: choices.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: choices.first()
    }

    /**
     * Computes a center-crop Matrix for TextureView so objects ALWAYS maintain
     * true 1:1 geometric proportions in full-screen, 4:3, 9:16, and 1:1 across 0/90/180/270.
     */
    fun buildPreviewTransformMatrix(
        viewWidth: Int,
        viewHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        displayRotation: Int
    ): Matrix {
        val matrix = Matrix()
        if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) {
            return matrix
        }

        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, bufferHeight.toFloat(), bufferWidth.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        val isViewLandscape = viewWidth > viewHeight
        if (isViewLandscape && (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270)) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(
                viewHeight.toFloat() / bufferHeight.toFloat(),
                viewWidth.toFloat() / bufferWidth.toFloat()
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (displayRotation - 2)).toFloat(), centerX, centerY)
        } else {
            val contentRatio = bufferHeight.toFloat() / bufferWidth.toFloat()
            val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()
            val scaleX: Float
            val scaleY: Float
            if (viewRatio > contentRatio) {
                scaleX = 1f
                scaleY = viewRatio / contentRatio
            } else {
                scaleX = contentRatio / viewRatio
                scaleY = 1f
            }
            matrix.setScale(scaleX, scaleY, centerX, centerY)
            if (displayRotation == Surface.ROTATION_180) {
                matrix.postRotate(180f, centerX, centerY)
            }
        }
        return matrix
    }

    /**
     * Maps normalized viewfinder coordinates [0..1] to Sensor Active Array Rect for AF/AE metering.
     */
    fun mapViewfinderToSensorRect(
        normX: Float,
        normY: Float,
        characteristics: CameraCharacteristics?,
        activeArray: Rect,
        displayRotation: Int,
        boxHalfSizePx: Int = 140
    ): Rect {
        val isFront = characteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val nx = normX.coerceIn(0f, 1f)
        val ny = normY.coerceIn(0f, 1f)

        val (sxNorm, syNorm) = when (displayRotation) {
            Surface.ROTATION_90 -> Pair(nx, ny)
            Surface.ROTATION_270 -> Pair(1f - nx, 1f - ny)
            Surface.ROTATION_180 -> if (isFront) Pair(1f - ny, 1f - nx) else Pair(ny, 1f - nx)
            else -> { // Portrait 0°
                if (isFront) Pair(1f - ny, 1f - nx) else Pair(ny, 1f - nx)
            }
        }

        val cx = activeArray.left + (sxNorm * activeArray.width()).toInt()
        val cy = activeArray.top + (syNorm * activeArray.height()).toInt()
        return Rect(
            (cx - boxHalfSizePx).coerceAtLeast(activeArray.left),
            (cy - boxHalfSizePx).coerceAtLeast(activeArray.top),
            (cx + boxHalfSizePx).coerceAtMost(activeArray.right),
            (cy + boxHalfSizePx).coerceAtMost(activeArray.bottom)
        )
    }

    fun getCaptureOrientationDegrees(
        characteristics: CameraCharacteristics?,
        displayRotation: Int
    ): Int {
        val sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val facing = characteristics?.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
        val deviceDegrees = when (displayRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + deviceDegrees) % 360
        } else {
            (sensorOrientation - deviceDegrees + 360) % 360
        }
    }
}
