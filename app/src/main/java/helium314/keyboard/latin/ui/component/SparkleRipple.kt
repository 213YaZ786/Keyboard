package helium314.keyboard.latin.ui.component

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.sin

/**
 * Android 12's sparkle ripple: AOSP's RippleShader (frameworks/base,
 * graphics/drawable/RippleShader.java, Apache License 2.0), its shader and
 * its uniforms kept as they are, without the mask. [progress] runs 0 to 0.5
 * as it spreads, 0.5 to 1 as it fades (RippleAnimationSession).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class SparkleRipple {

    val shader = RuntimeShader(SHADER).apply {
        // Without a mask the child shader is never read, yet it must be set.
        setInputShader("in_shader", android.graphics.LinearGradient(0f, 0f, 1f, 1f, android.graphics.Color.BLACK, android.graphics.Color.BLACK, android.graphics.Shader.TileMode.CLAMP))
    }

    fun update(centre: Offset, area: Size, maxRadius: Float, progress: Float, noisePhase: Float, color: Color, sparkle: Color) {
        shader.setFloatUniform("in_origin", centre.x, centre.y)
        shader.setFloatUniform("in_touch", centre.x, centre.y)
        shader.setFloatUniform("in_progress", progress)
        shader.setFloatUniform("in_maxRadius", maxRadius)
        shader.setFloatUniform("in_resolutionScale", 1f / area.width, 1f / area.height)
        shader.setFloatUniform("in_noiseScale", DENSITY / area.width, DENSITY / area.height)
        shader.setFloatUniform("in_hasMask", 0f)
        shader.setColorUniform("in_color", color.toArgb())
        shader.setColorUniform("in_sparkleColor", sparkle.toArgb())
        phase(noisePhase)
    }

    /** RippleShader.setNoisePhase, as AOSP writes it. */
    private fun phase(phase: Float) {
        shader.setFloatUniform("in_noisePhase", phase * 0.001f)
        shader.setFloatUniform("in_turbulencePhase", phase)
        val scale = 1.5
        shader.setFloatUniform("in_tCircle1", (scale * 0.5 + phase * 0.01 * cos(scale * 0.55)).toFloat(), (scale * 0.5 + phase * 0.01 * sin(scale * 0.55)).toFloat())
        shader.setFloatUniform("in_tCircle2", (scale * 0.2 + phase * -0.0066 * cos(scale * 0.45)).toFloat(), (scale * 0.2 + phase * -0.0066 * sin(scale * 0.45)).toFloat())
        shader.setFloatUniform("in_tCircle3", (scale + phase * -0.0066 * cos(scale * 0.35)).toFloat(), (scale + phase * -0.0066 * sin(scale * 0.35)).toFloat())
        val r1 = phase * ROTATE_RIGHT + 1.7 * Math.PI
        shader.setFloatUniform("in_tRotation1", cos(r1).toFloat(), sin(r1).toFloat())
        val r2 = phase * ROTATE_LEFT + 2 * Math.PI
        shader.setFloatUniform("in_tRotation2", cos(r2).toFloat(), sin(r2).toFloat())
        val r3 = phase * ROTATE_RIGHT + 2.75 * Math.PI
        shader.setFloatUniform("in_tRotation3", cos(r3).toFloat(), sin(r3).toFloat())
    }

    companion object {
        /** RippleAnimationSession's times: spreading, then fading. */
        const val ENTER_MS = 450
        const val EXIT_MS = 375
        /** The noise phase's pace: MAX_NOISE_PHASE over NOISE_ANIMATION_DURATION. */
        const val PHASE_PER_MS = (7000f / 214f) / 7000f
        private const val DENSITY = 2.1f
        private const val ROTATE_RIGHT = Math.PI * 0.0078125
        private const val ROTATE_LEFT = Math.PI * -0.0078125

        private const val SHADER = """
uniform vec2 in_origin;
uniform vec2 in_touch;
uniform float in_progress;
uniform float in_maxRadius;
uniform vec2 in_resolutionScale;
uniform vec2 in_noiseScale;
uniform float in_hasMask;
uniform float in_noisePhase;
uniform float in_turbulencePhase;
uniform vec2 in_tCircle1;
uniform vec2 in_tCircle2;
uniform vec2 in_tCircle3;
uniform vec2 in_tRotation1;
uniform vec2 in_tRotation2;
uniform vec2 in_tRotation3;
layout(color) uniform vec4 in_color;
layout(color) uniform vec4 in_sparkleColor;
uniform shader in_shader;
float triangleNoise(vec2 n) {
  n  = fract(n * vec2(5.3987, 5.4421));
  n += dot(n.yx, n.xy + vec2(21.5351, 14.3137));
  float xy = n.x * n.y;
  return fract(xy * 95.4307) + fract(xy * 75.04961) - 1.0;
}const float PI = 3.1415926535897932384626;

float threshold(float v, float l, float h) {
    return step(l, v) * (1.0 - step(h, v));
}
float sparkles(vec2 uv, float t) {
  float n = triangleNoise(uv);
  float s = 0.0;
  for (float i = 0; i < 4; i += 1) {
    float l = i * 0.1;
    float h = l + 0.05;
    float o = sin(PI * (t + 0.35 * i));
    s += threshold(n + o, l, h);
  }
  return saturate(s) * in_sparkleColor.a;
}
float softCircle(vec2 uv, vec2 xy, float radius, float blur) {
  float blurHalf = blur * 0.5;
  float d = distance(uv, xy);
  return 1. - smoothstep(1. - blurHalf, 1. + blurHalf, d / radius);
}
float softRing(vec2 uv, vec2 xy, float radius, float progress, float blur) {
  float thickness = 0.05 * radius;
  float currentRadius = radius * progress;
  float circle_outer = softCircle(uv, xy, currentRadius + thickness, blur);
  float circle_inner = softCircle(uv, xy, max(currentRadius - thickness, 0.),     blur);
  return saturate(circle_outer - circle_inner);
}
float subProgress(float start, float end, float progress) {
    float sub = clamp(progress, start, end);
    return (sub - start) / (end - start); 
}
mat2 rotate2d(vec2 rad){
  return mat2(rad.x, -rad.y, rad.y, rad.x);
}
float circle_grid(vec2 resolution, vec2 coord, float time, vec2 center,
    vec2 rotation, float cell_diameter) {
  coord = rotate2d(rotation) * (center - coord) + center;
  coord = mod(coord, cell_diameter) / resolution;
  float normal_radius = cell_diameter / resolution.y * 0.5;
  float radius = 0.65 * normal_radius;
  return softCircle(coord, vec2(normal_radius), radius, radius * 50.0);
}
float turbulence(vec2 uv, float t) {
  const vec2 scale = vec2(0.8);
  uv = uv * scale;
  float g1 = circle_grid(scale, uv, t, in_tCircle1, in_tRotation1, 0.17);
  float g2 = circle_grid(scale, uv, t, in_tCircle2, in_tRotation2, 0.2);
  float g3 = circle_grid(scale, uv, t, in_tCircle3, in_tRotation3, 0.275);
  float v = (g1 * g1 + g2 - g3) * 0.5;
  return saturate(0.45 + 0.8 * v);
}
vec4 main(vec2 p) {
    float fadeIn = subProgress(0., 0.13, in_progress);
    float scaleIn = subProgress(0., 1.0, in_progress);
    float fadeOutNoise = subProgress(0.4, 0.5, in_progress);
    float fadeOutRipple = subProgress(0.4, 1., in_progress);
    vec2 center = mix(in_touch, in_origin, saturate(in_progress * 2.0));
    float ring = softRing(p, center, in_maxRadius, scaleIn, 1.);
    float alpha = min(fadeIn, 1. - fadeOutNoise);
    vec2 uv = p * in_resolutionScale;
    vec2 densityUv = uv - mod(uv, in_noiseScale);
    float turb = turbulence(uv, in_turbulencePhase);
    float sparkleAlpha = sparkles(densityUv, in_noisePhase) * ring * alpha * turb;
    float fade = min(fadeIn, 1. - fadeOutRipple);
    float waveAlpha = softCircle(p, center, in_maxRadius * scaleIn, 1.) * fade * in_color.a;
    vec4 waveColor = vec4(in_color.rgb * waveAlpha, waveAlpha);
    vec4 sparkleColor = vec4(in_sparkleColor.rgb * in_sparkleColor.a, in_sparkleColor.a);
    float mask = in_hasMask == 1. ? in_shader.eval(p).a > 0. ? 1. : 0. : 1.;
    return mix(waveColor, sparkleColor, sparkleAlpha) * mask;
}
"""
    }
}
