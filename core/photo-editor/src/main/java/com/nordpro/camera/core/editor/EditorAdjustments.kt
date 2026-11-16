package com.nordpro.camera.core.editor

data class HslChannel(
    val hue: Float = 0f,        // -1.0 .. +1.0
    val saturation: Float = 0f, // -1.0 .. +1.0
    val luminance: Float = 0f   // -1.0 .. +1.0
)

data class ColorWheelGrade(
    val hueDegrees: Float = 0f, // 0..360
    val saturation: Float = 0f, // 0..1
    val luminance: Float = 0f   // -1..+1
)

data class CurvePoint(val x: Float, val y: Float)

data class CubeLut3D(
    val title: String,
    val size: Int,
    val data: FloatArray // size^3 * 3 RGB floats
)

/**
 * Complete Lightroom-level non-destructive adjustment state.
 * Can be applied globally or per-mask inside MaskCompositor.
 */
data class EditorAdjustments(
    // Light
    val exposureEv: Float = 0f,     // -4.0 .. +4.0
    val contrast: Float = 0f,       // -1.0 .. +1.0
    val highlights: Float = 0f,     // -1.0 .. +1.0
    val shadows: Float = 0f,        // -1.0 .. +1.0
    val whites: Float = 0f,         // -1.0 .. +1.0
    val blacks: Float = 0f,         // -1.0 .. +1.0

    // Color
    val temperature: Float = 0f,    // -1.0 (cool) .. +1.0 (warm)
    val tint: Float = 0f,           // -1.0 (green) .. +1.0 (magenta)
    val vibrance: Float = 0f,       // -1.0 .. +1.0
    val saturation: Float = 0f,     // -1.0 .. +1.0

    // Presence & Detail
    val texture: Float = 0f,        // -1.0 .. +1.0
    val clarity: Float = 0f,        // -1.0 .. +1.0
    val dehaze: Float = 0f,         // -1.0 .. +1.0
    val sharpening: Float = 0f,     // 0.0 .. +1.5
    val noiseReduction: Float = 0f, // 0.0 .. +1.0

    // Effects
    val vignette: Float = 0f,       // -1.0 .. +1.0
    val grain: Float = 0f,          // 0.0 .. +1.0

    // Advanced Color Grading
    val hslChannels: List<HslChannel> = List(8) { HslChannel() }, // R, O, Y, G, Aqua, B, P, M
    val rgbCurve: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 1f)),
    val shadowsWheel: ColorWheelGrade = ColorWheelGrade(),
    val midtonesWheel: ColorWheelGrade = ColorWheelGrade(),
    val highlightsWheel: ColorWheelGrade = ColorWheelGrade(),

    // LUT
    val lutName: String? = null,
    val lutStrength: Float = 1.0f
)

data class EditPreset(
    val id: String,
    val name: String,
    val isCustom: Boolean = false,
    val adjustments: EditorAdjustments
)
