package helium314.keyboard.latin.ui.component

import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/**
 * A wave of tinted glass leaving a button (Dialer's hang up): its colour and
 * where it starts, in the window. With [sparkles], Android 12's sparkle
 * ripple glitters along its front (Android 13 and later).
 */
class GlassWave(val color: Color, val origin: Offset, val sparkles: Boolean = true) {
    internal val sparkle: SparkleRipple? by lazy {
        if (sparkles && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) SparkleRipple() else null
    }
}

/**
 * A wave of tinted glass at [progress] (0 to 1) from its origin to past the
 * farthest corner: a light wash of the colour, deeper towards the front, and
 * the front itself lit like the rim of a pane, glittering. [alpha] fades it as
 * a whole. The caller sets the pace: slow, it spreads like a drop (620 ms).
 */
fun DrawScope.drawGlassWave(wave: GlassWave, progress: Float, alpha: Float) {
    if (progress <= 0f || alpha <= 0f) return
    val o = wave.origin
    val far = maxOf(
        hypot(o.x, o.y), hypot(size.width - o.x, o.y),
        hypot(o.x, size.height - o.y), hypot(size.width - o.x, size.height - o.y)
    )
    val radius = far * progress
    if (radius < 1f) return
    drawCircle(
        Brush.radialGradient(
            0f to wave.color.copy(alpha = 0.10f * alpha),
            0.85f to wave.color.copy(alpha = 0.26f * alpha),
            1f to wave.color.copy(alpha = 0.34f * alpha),
            center = o,
            radius = radius
        ),
        radius = radius,
        center = o
    )
    // The front: a soft band of the colour, then a bright hairline on it.
    val front = 1f - progress * 0.5f
    drawCircle(wave.color.copy(alpha = 0.22f * alpha * front), radius, o, style = Stroke(width = 22.dp.toPx()))
    drawCircle(Color.White.copy(alpha = 0.7f * alpha * front), radius, o, style = Stroke(width = 1.5.dp.toPx()))
    // The sparkles ride on the front at the wave's own pace: the shader's ring sits at
    // maxRadius × its progress, so its progress follows the wave's up to where it fades.
    val sparkle = wave.sparkle
    if (sparkle != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val reach = SPARKLE_SPAN
        sparkle.update(
            centre = o,
            area = size,
            maxRadius = far / reach,
            progress = progress * reach,
            noisePhase = (SystemClock.uptimeMillis() % 100_000) * SparkleRipple.PHASE_PER_MS,
            color = Color.Transparent,
            sparkle = Color.White.copy(alpha = alpha)
        )
        // Only in a band on the front, trailing a little inside it.
        val band = androidx.compose.ui.graphics.Path().apply {
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
            addOval(androidx.compose.ui.geometry.Rect(o, radius + 26.dp.toPx()))
            addOval(androidx.compose.ui.geometry.Rect(o, (radius - 56.dp.toPx()).coerceAtLeast(0f)))
        }
        clipPath(band) { drawRect(ShaderBrush(sparkle.shader)) }
    }
}

/** How far into the shader's run the wave's end maps: its sparkles fade out between 0.4 and 0.5. */
private const val SPARKLE_SPAN = 0.47f
