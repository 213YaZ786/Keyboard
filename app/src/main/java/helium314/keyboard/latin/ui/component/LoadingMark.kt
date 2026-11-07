// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Keyboard's loading mark, the launcher icon in motion: the keycap is pressed and let go, with a small spring.
 * The key is the icon's: a grey body multiplied by the theme's accent, its light over it. Ported from
 * https://github.com/213YaZ786/SMS/blob/main/app/src/main/java/com/yaz/sms/ui/component/LoadingMark.kt#LoadingMark
 * (the bubble's breathing and the three hopping dots are not ported: the icon has no dots, its motion is the press).
 *
 * [progress] from 0 to 1 presses the key as far as a gesture has gone (a download shows the key going down). While
 * [running] and without [progress] it presses and lets go on its own; above 0 [progress] rules, as the Material
 * indicator this replaces did, so DictionaryRows' determinate download keeps working unchanged.
 */
@Composable
fun LoadingMark(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    running: Boolean = true,
    progress: Float = 0f
) {
    val body = ImageBitmap.imageResource(R.drawable.key_body)
    val shine = ImageBitmap.imageResource(R.drawable.key_shine)
    // The launcher icon's own tone (ic_launcher_key), so the mark is the icon by day and by night.
    val accent = colorResource(android.R.color.system_accent1_500)
    val tint = remember(accent) { ColorFilter.tint(accent, BlendMode.Modulate) }

    val transition = rememberInfiniteTransition(label = "keypress")
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(LOOP_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "loop"
    )
    val press = if (running && progress <= 0f) pressAt(clock * LOOP_MILLIS) else progress.coerceIn(0f, 1f)

    Canvas(modifier.size(size)) {
        val side = this.size.minDimension * LAYER
        // The key's own centre laid on the mark's centre (the layers are the launcher's canvas, the key sits a little high in it).
        val rest = (0.5f - KEY_CENTRE) * side
        val bottom = center.y - side / 2f + rest + KEY_BOTTOM * side
        // Pressed, the key goes down by 6 % of its side and flattens by 4 % toward its base.
        translate(top = side * 0.06f * press) {
            scale(1f, 1f - 0.04f * press, pivot = Offset(center.x, bottom)) {
                layer(body, side, rest, tint, 1f)
                layer(shine, side, rest, null, 1f - 0.35f * press)
            }
        }
    }
}

/** One loop of [LOOP_MILLIS]: down in 120 ms (sin ease), held 80 ms, up in 300 ms with a small spring, then rest. */
private fun pressAt(t: Float): Float {
    if (t < 120f) return sin(t / 120f * PI.toFloat() / 2f)
    if (t < 200f) return 1f
    if (t < 500f) {
        val u = (t - 200f) / 300f
        return cos(u * PI.toFloat() / 2f) - if (u > 0.5f) 0.12f * sin(u * PI.toFloat()) else 0f
    }
    return 0f
}

private fun DrawScope.layer(image: ImageBitmap, side: Float, dy: Float, filter: ColorFilter?, alpha: Float) {
    val s = side.toInt()
    drawImage(
        image,
        dstOffset = IntOffset((center.x - side / 2f).toInt(), (center.y - side / 2f + dy).toInt()),
        dstSize = IntSize(s, s),
        alpha = alpha,
        colorFilter = filter,
        filterQuality = FilterQuality.High
    )
}

/** The key's share of the mark's width, as SMS's bubble (0.667). */
private const val BUBBLE = 0.667f
/** The key in the launcher's 108 dp layers (xxxhdpi, 432 px): 239 px wide, centred at 205 px, its foot at 311 px. */
private const val KEY_WIDTH = 239f / 432f
private const val KEY_CENTRE = 205f / 432f
private const val KEY_BOTTOM = 311f / 432f
/** The layers' side in the mark's side, so the key itself spans [BUBBLE] of it (the canvas around it is clear). */
private const val LAYER = BUBBLE / KEY_WIDTH
private const val LOOP_MILLIS = 1400
