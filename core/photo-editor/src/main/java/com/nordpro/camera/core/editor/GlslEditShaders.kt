package com.nordpro.camera.core.editor

import android.opengl.GLES30

/**
 * OpenGL ES 3.2 fragment shader for real-time, full-resolution, non-destructive
 * Lightroom-level editing on the Adreno 825 GPU (OnePlus Nord 6).
 */
object GlslEditShaders {

    const val VERTEX_SHADER = """#version 300 es
        layout(location = 0) in vec4 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        out vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """

    const val FRAGMENT_SHADER = """#version 300 es
        precision highp float;
        in vec2 vTexCoord;
        out vec4 fragColor;

        uniform sampler2D uTexture;
        uniform vec2 uTexelSize;

        uniform float uExposure;
        uniform float uContrast;
        uniform float uHighlights;
        uniform float uShadows;
        uniform float uWhites;
        uniform float uBlacks;
        uniform float uTemp;
        uniform float uTint;
        uniform float uVibrance;
        uniform float uSaturation;
        uniform float uClarity;
        uniform float uDehaze;
        uniform float uSharpening;
        uniform float uVignette;
        uniform float uGrain;
        uniform vec3 uShadowGrade;
        uniform vec3 uHighlightGrade;

        float luminance(vec3 c) {
            return dot(c, vec3(0.2126, 0.7152, 0.0722));
        }

        vec3 rgb2hsl(vec3 c) {
            float maxC = max(max(c.r, c.g), c.b);
            float minC = min(min(c.r, c.g), c.b);
            float l = (maxC + minC) * 0.5;
            if (maxC == minC) return vec3(0.0, 0.0, l);
            float d = maxC - minC;
            float s = l > 0.5 ? d / (2.0 - maxC - minC) : d / (maxC + minC);
            float h;
            if (maxC == c.r) h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
            else if (maxC == c.g) h = (c.b - c.r) / d + 2.0;
            else h = (c.r - c.g) / d + 4.0;
            return vec3(h / 6.0, s, l);
        }

        float rand2d(vec2 co) {
            return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
        }

        void main() {
            vec3 color = texture(uTexture, vTexCoord).rgb;

            // 1. Exposure (linear EV)
            color *= pow(2.0, uExposure);

            // 2. White Balance (Temp & Tint)
            color.r *= (1.0 + uTemp * 0.18 + uTint * 0.06);
            color.g *= (1.0 - uTint * 0.12);
            color.b *= (1.0 - uTemp * 0.18 + uTint * 0.06);

            // 3. Dehaze (atmospheric black-point + local contrast)
            if (abs(uDehaze) > 0.001) {
                color = (color - uDehaze * 0.06) / max(1.0 - uDehaze * 0.08, 0.25);
            }

            // 4. Highlights, Shadows, Whites, Blacks
            float lum = clamp(luminance(color), 0.0, 1.0);
            float shadowMask = pow(1.0 - lum, 2.2);
            float highlightMask = pow(lum, 2.2);
            color += color * (uShadows * 0.55 * shadowMask);
            color += color * (uHighlights * 0.45 * highlightMask);
            color = (color - uBlacks * 0.08) / max(1.0 - uWhites * 0.08, 0.3);

            // 5. Contrast (pivoted at 0.5)
            color = (color - 0.5) * (1.0 + uContrast * 0.85) + 0.5;

            // 6. Local Clarity & Unsharp Sharpening
            if (abs(uClarity) > 0.001 || uSharpening > 0.001) {
                vec3 blur = (
                    texture(uTexture, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb +
                    texture(uTexture, vTexCoord - vec2(uTexelSize.x, 0.0)).rgb +
                    texture(uTexture, vTexCoord + vec2(0.0, uTexelSize.y)).rgb +
                    texture(uTexture, vTexCoord - vec2(0.0, uTexelSize.y)).rgb
                ) * 0.25;
                vec3 detail = color - blur;
                color += clamp(detail, -0.12, 0.12) * (uSharpening * 0.9 + uClarity * 0.35);
            }

            // 7. Split Toning / Color Grading Wheels
            color += uShadowGrade * shadowMask * 0.25;
            color += uHighlightGrade * highlightMask * 0.25;

            // 8. Vibrance & Saturation
            float l2 = luminance(color);
            float maxCh = max(max(color.r, color.g), color.b);
            float minCh = min(min(color.r, color.g), color.b);
            float satAmount = maxCh - minCh;
            float vibFactor = uVibrance * (1.0 - satAmount);
            color = mix(vec3(l2), color, 1.0 + uSaturation + vibFactor);

            // 9. Vignette
            if (abs(uVignette) > 0.001) {
                float dist = distance(vTexCoord, vec2(0.5));
                float vig = smoothstep(0.35, 0.88, dist);
                color *= (1.0 + uVignette * vig * 0.75);
            }

            // 10. Film Grain
            if (uGrain > 0.001) {
                float n = (rand2d(vTexCoord * 1024.0) - 0.5) * uGrain * 0.09;
                color += vec3(n);
            }

            fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
        }
    """

    fun compileProgram(): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        return shader
    }
}
