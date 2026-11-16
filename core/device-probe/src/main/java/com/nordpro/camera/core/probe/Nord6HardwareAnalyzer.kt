package com.nordpro.camera.core.probe

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.os.Build
import android.os.PowerManager
import android.util.Range
import android.util.Size

enum class LensRole { MAIN_WIDE, ULTRA_WIDE, TELEPHOTO, FRONT, AUXILIARY }

data class SensorCapabilityProfile(
    val cameraId: String,
    val lensFacing: Int,
    val role: LensRole,
    val hardwareLevel: Int,
    val hardwareLevelLabel: String,
    val focalLengthsMm: FloatArray,
    val equivalent35mmFocalLength: Int,
    val apertures: FloatArray,
    val sensorPhysicalWidthMm: Float,
    val activeArrayWidth: Int,
    val activeArrayHeight: Int,
    val supportsRawCapture: Boolean,
    val supportsManualSensor: Boolean,
    val supportsManualPostProcessing: Boolean,
    val supportsOis: Boolean,
    val supportsPreviewStabilization: Boolean,
    val supportsMacroFocus: Boolean,
    val minimumFocusDistanceDiopters: Float,
    val isoRange: Range<Int>?,
    val exposureTimeRangeNs: Range<Long>?,
    val evCompensationRange: Range<Int>,
    val evCompensationStep: Float,
    val maxZoomRatio: Float,
    val supports10BitHlgVideo: Boolean,
    val supportsLogProfileVia10BitShader: Boolean,
    val maxJpegSize: Size?,
    val maxRawSize: Size?,
    val supportedVideoSizes4k: List<Size>,
    val supportedHighSpeedSizes: List<Pair<Size, Range<Int>>>,
    val isHiRes50MpExposed: Boolean
)

data class DeviceHardwareReport(
    val model: String,
    val manufacturer: String,
    val isOnePlusNord6: Boolean,
    val androidApiLevel: Int,
    val sensors: List<SensorCapabilityProfile>,
    val currentThermalStatus: Int
)

/**
 * Runtime hardware capability probe for OnePlus Nord 6 (CPH2793) and compatible Android 16+ devices.
 * Never assumes feature support; queries Camera2 CameraCharacteristics dynamically.
 */
class Nord6HardwareAnalyzer(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun probeDevice(): DeviceHardwareReport {
        val profiles = mutableListOf<SensorCapabilityProfile>()
        for (cameraId in cameraManager.cameraIdList) {
            runCatching {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                profiles.add(inspectCamera(cameraId, chars))
            }
        }
        val model = Build.MODEL.orEmpty()
        return DeviceHardwareReport(
            model = model,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            isOnePlusNord6 = model.contains("CPH2793", ignoreCase = true) ||
                model.contains("CPH2795", ignoreCase = true) ||
                model.contains("Nord 6", ignoreCase = true),
            androidApiLevel = Build.VERSION.SDK_INT,
            sensors = profiles,
            currentThermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                powerManager.currentThermalStatus
            } else 0
        )
    }

    private fun inspectCamera(cameraId: String, chars: CameraCharacteristics): SensorCapabilityProfile {
        val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
        val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
        val hwLabel = when (hwLevel) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "LEGACY"
        }

        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val supportsRaw = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val supportsManualSensor = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val supportsManualPost = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)

        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(0f)
        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf(0f)
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val sensorWidthMm = sensorSize?.width ?: 6.4f
        val primaryFocal = focalLengths.firstOrNull() ?: 4.7f
        val eq35mm = if (sensorWidthMm > 0f) ((36.0f / sensorWidthMm) * primaryFocal).toInt() else 26

        val minFocusDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        // Macro requires autofocus capability with close minimum focus distance (>= 10 diopters => <= 10cm)
        val supportsMacro = minFocusDiopters >= 10.0f

        val role = when {
            facing == CameraCharacteristics.LENS_FACING_FRONT -> LensRole.FRONT
            eq35mm <= 20 -> LensRole.ULTRA_WIDE
            eq35mm >= 55 -> LensRole.TELEPHOTO
            else -> LensRole.MAIN_WIDE
        }

        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
        val supportsOis = oisModes.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON)

        val videoStabModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
        val supportsPreviewStab = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            videoStabModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
        } else false

        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val activeW = activeArray?.width() ?: 0
        val activeH = activeArray?.height() ?: 0

        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSizes = streamMap?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        val rawSizes = streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
        val maxJpeg = jpegSizes.maxByOrNull { it.width * it.height }
        val maxRaw = rawSizes.maxByOrNull { it.width * it.height }
        val isHiRes50Mp = (maxJpeg != null && maxJpeg.width * maxJpeg.height >= 48_000_000) ||
            (activeW * activeH >= 48_000_000)

        val videoSizes4k = streamMap?.getOutputSizes(android.media.MediaRecorder::class.java)
            ?.filter { it.width >= 3840 && it.height >= 2160 }
            .orEmpty()

        val highSpeedList = mutableListOf<Pair<Size, Range<Int>>>()
        streamMap?.highSpeedVideoSizes?.forEach { size ->
            streamMap.getHighSpeedVideoFpsRangesFor(size)?.forEach { range ->
                highSpeedList.add(size to range)
            }
        }

        var supports10BitHlg = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val drProfiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            val supported = drProfiles?.supportedProfiles.orEmpty()
            supports10BitHlg = supported.contains(DynamicRangeProfiles.HLG10) ||
                supported.contains(DynamicRangeProfiles.HDR10)
        }

        val maxZoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 10f
        } else {
            chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 10f
        }

        val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(-12, 12)
        val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: (1f / 3f)

        return SensorCapabilityProfile(
            cameraId = cameraId,
            lensFacing = facing,
            role = role,
            hardwareLevel = hwLevel,
            hardwareLevelLabel = hwLabel,
            focalLengthsMm = focalLengths,
            equivalent35mmFocalLength = eq35mm,
            apertures = apertures,
            sensorPhysicalWidthMm = sensorWidthMm,
            activeArrayWidth = activeW,
            activeArrayHeight = activeH,
            supportsRawCapture = supportsRaw && maxRaw != null,
            supportsManualSensor = supportsManualSensor,
            supportsManualPostProcessing = supportsManualPost,
            supportsOis = supportsOis,
            supportsPreviewStabilization = supportsPreviewStab,
            supportsMacroFocus = supportsMacro,
            minimumFocusDistanceDiopters = minFocusDiopters,
            isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureTimeRangeNs = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            evCompensationRange = evRange,
            evCompensationStep = evStep,
            maxZoomRatio = maxZoom,
            supports10BitHlgVideo = supports10BitHlg,
            // Flat/LOG tone curve is only safe without 8-bit banding when 10-bit capture pipeline is present
            supportsLogProfileVia10BitShader = supports10BitHlg,
            maxJpegSize = maxJpeg,
            maxRawSize = maxRaw,
            supportedVideoSizes4k = videoSizes4k,
            supportedHighSpeedSizes = highSpeedList,
            isHiRes50MpExposed = isHiRes50Mp
        )
    }
}
