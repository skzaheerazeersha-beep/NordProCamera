package com.nordpro.camera.core.aimasking

import com.nordpro.camera.core.editor.EditorAdjustments
import java.util.UUID

enum class MaskType(val label: String) {
    SUBJECT("Subject"),
    SKY("Sky"),
    BACKGROUND("Background"),
    FACE_SKIN("Face & Skin"),
    HAIR("Hair"),
    OBJECT("Object"),
    BRUSH("Brush"),
    LINEAR_GRADIENT("Linear"),
    RADIAL_GRADIENT("Radial"),
    COLOR_RANGE("Color Range"),
    LUMINANCE_RANGE("Luma Range")
}

data class MaskLayer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: MaskType,
    val width: Int,
    val height: Int,
    val alphaMask: FloatArray, // 0.0f .. 1.0f per pixel
    val inverted: Boolean = false,
    val featherRadiusPx: Float = 8f,
    val opacity: Float = 1.0f,
    val adjustments: EditorAdjustments = EditorAdjustments()
)

data class BrushStroke(
    val normalizedPoints: List<Pair<Float, Float>>,
    val radiusNormalized: Float = 0.04f,
    val hardness: Float = 0.5f,
    val erase: Boolean = false
)
