package com.nordpro.camera.core.raw

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RawCaptureOutput(
    val dngUri: Uri?,
    val jpegUri: Uri?,
    val bitDepthNote: String
)

/**
 * True 10-bit/16-bit DNG RAW + simultaneous JPEG capture engine using Camera2 DngCreator.
 * Preserves original sensor Bayer/Quad-Bayer data without destructive ISP sharpening or tone curves.
 */
class DngRawCaptureEngine(private val context: Context) {

    suspend fun saveDngAndOptionalJpeg(
        characteristics: CameraCharacteristics,
        captureResult: CaptureResult,
        rawImage: Image,
        jpegBytes: ByteArray?,
        orientationDegrees: Int = 0
    ): RawCaptureOutput = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val baseName = "NORD_PRO_$timestamp"

        val dngUri = writeDngToMediaStore(
            fileName = "$baseName.dng",
            characteristics = characteristics,
            result = captureResult,
            rawImage = rawImage,
            orientation = orientationDegrees
        )

        val jpegUri = jpegBytes?.let { bytes ->
            writeJpegToMediaStore(
                fileName = "$baseName.jpg",
                jpegBytes = bytes
            )
        }

        val bitDepth = captureResult.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.let { whiteLevel ->
            when {
                whiteLevel >= 4095 -> "12-bit RAW (whiteLevel=$whiteLevel)"
                whiteLevel >= 1023 -> "10-bit RAW (whiteLevel=$whiteLevel)"
                else -> "RAW (whiteLevel=$whiteLevel)"
            }
        } ?: "10-bit+ RAW_SENSOR"

        RawCaptureOutput(
            dngUri = dngUri,
            jpegUri = jpegUri,
            bitDepthNote = bitDepth
        )
    }

    private fun writeDngToMediaStore(
        fileName: String,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        rawImage: Image,
        orientation: Int
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/NordProCamera/RAW")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        return runCatching {
            val dngCreator = DngCreator(characteristics, result).apply {
                setOrientation(toExifOrientation(orientation))
                setDescription("Captured with Nord Pro Camera (OnePlus Nord 6 CPH2793)")
            }
            resolver.openOutputStream(uri)?.use { out ->
                dngCreator.writeImage(out, rawImage)
            }
            dngCreator.close()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        }.getOrNull()
    }

    private fun writeJpegToMediaStore(fileName: String, jpegBytes: ByteArray): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/NordProCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { it.write(jpegBytes) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        }.getOrNull()
    }

    private fun toExifOrientation(degrees: Int): Int = when (((degrees % 360) + 360) % 360) {
        90 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90
        180 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180
        270 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
        else -> androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
    }
}
