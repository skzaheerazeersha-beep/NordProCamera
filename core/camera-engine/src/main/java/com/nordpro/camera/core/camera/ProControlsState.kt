package com.nordpro.camera.core.camera

import android.graphics.ColorMatrix

enum class CaptureMode(val label: String) {
    TIMELAPSE("TIME-LAPSE"),
    SLO_MO("SLO-MO"),
    VIDEO("VIDEO"),
    PHOTO("PHOTO"),
    PORTRAIT("PORTRAIT"),
    DOC_SCAN("DOC SCAN"),
    NIGHT("NIGHT"),
    PRO("PRO"),
    HI_RES_50MP("50MP"),
    PANORAMA("PANO")
}

enum class RawFormatOption(val label: String) {
    JPEG_ONLY("JPG"),
    RAW_DNG("RAW"),
    RAW_PLUS_JPEG("RAW+JPG")
}

enum class FlashMode(val label: String) {
    OFF("OFF"),
    AUTO("AUTO"),
    ON("ON"),
    TORCH("TORCH")
}

enum class HdrMode(val label: String) {
    AUTO("HDR AUTO"),
    ON("HDR ON"),
    OFF("HDR OFF")
}

enum class TimerOption(val seconds: Int, val label: String) {
    OFF(0, "OFF"),
    THREE(3, "3s"),
    TEN(10, "10s")
}

enum class AspectRatioOption(val ratioWidth: Float, val ratioHeight: Float, val label: String) {
    RATIO_4_3(4f, 3f, "4:3"),
    RATIO_16_9(16f, 9f, "9:16"),
    RATIO_1_1(1f, 1f, "1:1"),
    FULL(0f, 0f, "FULL");

    fun safeAspectFloat(): Float = if (ratioWidth > 0f && ratioHeight > 0f) ratioWidth / ratioHeight else 4f / 3f
}

enum class VolumeButtonAction(val label: String) {
    SHUTTER("Shutter"),
    ZOOM("Zoom"),
    VOLUME("Volume")
}

enum class CameraFilter(val label: String) {
    ORIGINAL("Original"),
    VIVID("Vivid"),
    WARM("Warm"),
    COOL("Cool"),
    BW("B&W"),
    NOIR("Noir"),
    GLOW("Glow");

    fun toColorMatrix(): ColorMatrix {
        return when (this) {
            ORIGINAL -> ColorMatrix()
            VIVID -> ColorMatrix().apply {
                setSaturation(1.45f)
                postConcat(contrastMatrix(1.12f, -12f))
            }
            WARM -> ColorMatrix(
                floatArrayOf(
                    1.12f, 0f, 0f, 0f, 6f,
                    0f, 1.03f, 0f, 0f, 2f,
                    0f, 0f, 0.88f, 0f, -4f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            COOL -> ColorMatrix(
                floatArrayOf(
                    0.90f, 0f, 0f, 0f, -4f,
                    0f, 1.02f, 0f, 0f, 2f,
                    0f, 0f, 1.16f, 0f, 8f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            BW -> ColorMatrix().apply { setSaturation(0f) }
            NOIR -> ColorMatrix().apply {
                setSaturation(0f)
                postConcat(contrastMatrix(1.38f, -38f))
            }
            GLOW -> ColorMatrix().apply {
                setSaturation(1.18f)
                postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            1.08f, 0f, 0f, 0f, 14f,
                            0f, 1.06f, 0f, 0f, 12f,
                            0f, 0f, 1.04f, 0f, 10f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }
        }
    }

    private fun contrastMatrix(scale: Float, translate: Float): ColorMatrix = ColorMatrix(
        floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
    )
}

data class ProControlsState(
    val isManualIso: Boolean = false,
    val iso: Int = 100,
    val isManualShutter: Boolean = false,
    val shutterSpeedNs: Long = 16_666_666L,
    val isManualFocus: Boolean = false,
    val focusDistanceDiopters: Float = 0f,
    val isManualWhiteBalance: Boolean = false,
    val whiteBalanceKelvin: Int = 5500,
    val evCompensationIndex: Int = 0,
    val portraitFStop: Float = 4.5f,
    val aeLocked: Boolean = false,
    val afLocked: Boolean = false,
    val focusPeakingEnabled: Boolean = false,
    val zebraStripesEnabled: Boolean = false,
    val zebraThreshold: Float = 0.95f,
    val histogramEnabled: Boolean = false,
    val gridEnabled: Boolean = true,
    val levelIndicatorEnabled: Boolean = true,
    val rawFormatOption: RawFormatOption = RawFormatOption.JPEG_ONLY,
    val flashMode: FlashMode = FlashMode.AUTO,
    val hdrMode: HdrMode = HdrMode.AUTO,
    val timerOption: TimerOption = TimerOption.OFF,
    val aspectRatio: AspectRatioOption = AspectRatioOption.RATIO_4_3,
    val selectedFilter: CameraFilter = CameraFilter.ORIGINAL,
    val video4kEnabled: Boolean = true,
    val video60fpsEnabled: Boolean = true,
    val sloMoFps: Int = 120,
    val timelapseSpeed: Double = 2.0,
    val micEnabled: Boolean = true,
    val zoomRatio: Float = 1.0f,
    // Full Camera Settings options
    val watermarkEnabled: Boolean = false,
    val shutterSoundEnabled: Boolean = true,
    val mirrorSelfie: Boolean = true,
    val locationTagsEnabled: Boolean = false,
    val preserveSettings: Boolean = true,
    val touchHoldRecordVideo: Boolean = true,
    val qrScanningEnabled: Boolean = true,
    val portraitDistortionCorrection: Boolean = true,
    val volumeButtonAction: VolumeButtonAction = VolumeButtonAction.SHUTTER,
    val quickLaunchEnabled: Boolean = true,
    val smartSuggestionsEnabled: Boolean = false
)
