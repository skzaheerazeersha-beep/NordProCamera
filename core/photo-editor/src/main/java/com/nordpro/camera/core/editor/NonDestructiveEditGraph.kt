package com.nordpro.camera.core.editor

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Non-destructive edit graph managing Undo/Redo history, built-in & custom presets,
 * and .cube 3D LUT parsing without ever mutating the original photo file.
 */
class NonDestructiveEditGraph(initial: EditorAdjustments = EditorAdjustments()) {

    private val history = mutableListOf(initial)
    private var cursor = 0

    val current: EditorAdjustments
        get() = history[cursor]

    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < history.lastIndex

    fun pushState(next: EditorAdjustments) {
        if (next == current) return
        while (history.lastIndex > cursor) {
            history.removeAt(history.lastIndex)
        }
        history.add(next)
        cursor = history.lastIndex
    }

    fun undo(): EditorAdjustments {
        if (canUndo) cursor--
        return current
    }

    fun redo(): EditorAdjustments {
        if (canRedo) cursor++
        return current
    }

    fun reset(): EditorAdjustments {
        pushState(EditorAdjustments())
        return current
    }

    companion object {
        val BUILT_IN_PRESETS = listOf(
            EditPreset(
                id = "hasselblad_natural",
                name = "Hasselblad Natural",
                adjustments = EditorAdjustments(
                    contrast = 0.08f,
                    highlights = -0.18f,
                    shadows = 0.12f,
                    vibrance = 0.10f,
                    texture = 0.12f,
                    sharpening = 0.20f
                )
            ),
            EditPreset(
                id = "vivo_zeiss_crisp",
                name = "ZEISS Crisp Vivid",
                adjustments = EditorAdjustments(
                    exposureEv = 0.10f,
                    contrast = 0.14f,
                    highlights = -0.25f,
                    shadows = 0.22f,
                    vibrance = 0.22f,
                    clarity = 0.15f,
                    sharpening = 0.30f
                )
            ),
            EditPreset(
                id = "leica_monochrome",
                name = "Noir Monochrome",
                adjustments = EditorAdjustments(
                    contrast = 0.35f,
                    highlights = -0.15f,
                    blacks = -0.18f,
                    saturation = -1.0f,
                    clarity = 0.25f,
                    grain = 0.18f,
                    vignette = -0.25f
                )
            ),
            EditPreset(
                id = "cinematic_teal_orange",
                name = "Cine Teal & Warm",
                adjustments = EditorAdjustments(
                    contrast = 0.16f,
                    highlights = -0.20f,
                    shadows = 0.10f,
                    shadowsWheel = ColorWheelGrade(hueDegrees = 195f, saturation = 0.28f),
                    highlightsWheel = ColorWheelGrade(hueDegrees = 35f, saturation = 0.22f)
                )
            )
        )

        fun parseCubeLut(titleFallback: String, input: InputStream): CubeLut3D? {
            var title = titleFallback
            var size = 0
            val values = ArrayList<Float>()

            BufferedReader(InputStreamReader(input)).use { reader ->
                reader.lineSequence().forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    when {
                        line.startsWith("TITLE") -> {
                            title = line.removePrefix("TITLE").trim().trim('"')
                        }
                        line.startsWith("LUT_3D_SIZE") -> {
                            size = line.removePrefix("LUT_3D_SIZE").trim().toIntOrNull() ?: 0
                        }
                        else -> {
                            val parts = line.split(Regex("\\s+"))
                            if (parts.size == 3) {
                                val r = parts[0].toFloatOrNull()
                                val g = parts[1].toFloatOrNull()
                                val b = parts[2].toFloatOrNull()
                                if (r != null && g != null && b != null) {
                                    values.add(r); values.add(g); values.add(b)
                                }
                            }
                        }
                    }
                }
            }
            if (size <= 1 || values.size != size * size * size * 3) return null
            return CubeLut3D(title = title, size = size, data = values.toFloatArray())
        }
    }
}
