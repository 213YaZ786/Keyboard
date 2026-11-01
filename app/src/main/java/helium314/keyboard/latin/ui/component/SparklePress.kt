package helium314.keyboard.latin.ui.component

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlinx.coroutines.launch

/**
 * The press of an item as Android 12 draws it: the sparkle ripple, clipped to the item's own [shape]
 * (a rounded inset for a row, a circle for a round button or an emoji), never Material's rectangle of
 * the bounds. The official recipe "Create and apply a custom Indication": an IndicationNodeFactory whose
 * Modifier.Node is a DrawModifierNode collecting Press, Release and Cancel
 * (https://developer.android.com/develop/ui/compose/touch-input/user-interactions/handling-interactions,
 * "Create and apply a custom Indication"), generalised from PillMenu's former PillPressIndication.
 *
 * The shape is filled inside the insets, [horizontal] from the left and the right, [vertical] from the
 * top and the bottom. The ripple is drawn behind the content. With [fromCentre] it starts at the centre
 * of the shape instead of the touch (for an indication placed on a child of the clickable, which the
 * touch position does not belong to). [color] is the wave, [sparkle] the glitter: AOSP's ripple colour is
 * the theme's colorControlHighlight; see [rememberSparklePress] for the theme's.
 * Below Android 13 (no RuntimeShader) the shape is a plain fill of [color] faded in and out.
 */
class SparklePress(
    private val shape: Shape,
    private val horizontal: Dp = 0.dp,
    private val vertical: Dp = 0.dp,
    private val color: Color,
    private val sparkle: Color,
    private val fromCentre: Boolean = false
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        SparklePressNode(interactionSource, shape, horizontal, vertical, color, sparkle, fromCentre)

    override fun equals(other: Any?): Boolean =
        other is SparklePress && other.shape == shape && other.horizontal == horizontal && other.vertical == vertical &&
            other.color == color && other.sparkle == sparkle && other.fromCentre == fromCentre

    override fun hashCode(): Int {
        var h = shape.hashCode()
        h = 31 * h + horizontal.hashCode()
        h = 31 * h + vertical.hashCode()
        h = 31 * h + color.hashCode()
        h = 31 * h + sparkle.hashCode()
        h = 31 * h + fromCentre.hashCode()
        return h
    }
}

/**
 * [SparklePress] in the theme's colours: the wave is onSurface at 12 %, the glitter onSurface at 50 %
 * (AOSP's ripple takes the theme's colorControlHighlight, an onSurface-like grey at low alpha).
 */
@Composable
fun rememberSparklePress(
    shape: Shape,
    horizontal: Dp = 0.dp,
    vertical: Dp = 0.dp,
    fromCentre: Boolean = false
): SparklePress {
    val ink = MaterialTheme.colorScheme.onSurface
    return remember(shape, horizontal, vertical, fromCentre, ink) {
        SparklePress(shape, horizontal, vertical, ink.copy(alpha = 0.12f), ink.copy(alpha = 0.5f), fromCentre)
    }
}

/**
 * The node of [SparklePress]. The timing is AOSP's RippleAnimationSession
 * (https://github.com/aosp-mirror/platform_frameworks_base/blob/main/graphics/java/android/graphics/drawable/RippleAnimationSession.java):
 * progress runs 0 to 0.5 in 450 ms from the touch (the shader moves the centre from in_touch to in_origin
 * as it goes), then 0.5 to 1 in 375 ms after the release, the exit waiting for the end of the enter. The
 * radius is RippleDrawable's bounded one, half the diagonal of the bounds
 * (https://github.com/aosp-mirror/platform_frameworks_base/blob/main/graphics/java/android/graphics/drawable/RippleDrawable.java).
 * The shader is SparkleRipple, AOSP's RippleShader, driven as GlassWave drives it.
 */
private class SparklePressNode(
    private val interactionSource: InteractionSource,
    private val shape: Shape,
    private val horizontal: Dp,
    private val vertical: Dp,
    private val color: Color,
    private val sparkle: Color,
    private val fromCentre: Boolean
) : Modifier.Node(), DrawModifierNode {
    private val progress = Animatable(1f)
    private val shown = Animatable(0f)
    private var touch = Offset.Zero
    private var ripple: SparkleRipple? = null
    private var brush: ShaderBrush? = null

    override fun onAttach() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val r = SparkleRipple()
            ripple = r
            brush = ShaderBrush(r.shader)
        }
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        touch = interaction.pressPosition
                        // A new animation on the Animatable replaces the running one, so a repeat press restarts cleanly.
                        launch {
                            if (ripple != null) {
                                progress.snapTo(0f)
                                progress.animateTo(0.5f, tween(SparkleRipple.ENTER_MS, easing = LinearEasing))
                            } else {
                                shown.animateTo(1f, tween(90))
                            }
                        }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> launch {
                        if (ripple != null) {
                            val left = ((0.5f - progress.value) / 0.5f * SparkleRipple.ENTER_MS).toInt()
                            if (left > 0 && progress.value < 0.5f) progress.animateTo(0.5f, tween(left, easing = LinearEasing))
                            progress.animateTo(1f, tween(SparkleRipple.EXIT_MS, easing = LinearEasing))
                        } else {
                            shown.animateTo(0f, tween(220))
                        }
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val running = if (ripple != null) progress.value in 0.0001f..0.9999f else shown.value > 0f
        if (running) {
            inset(left = horizontal.toPx(), top = vertical.toPx(), right = horizontal.toPx(), bottom = vertical.toPx()) {
                val outline = shape.createOutline(size, layoutDirection, this)
                val r = ripple
                val b = brush
                if (r != null && b != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    r.update(
                        centre = centre,
                        area = size,
                        maxRadius = hypot(size.width, size.height) / 2f,
                        progress = progress.value,
                        noisePhase = (SystemClock.uptimeMillis() % 100_000) * SparkleRipple.PHASE_PER_MS,
                        color = color,
                        sparkle = sparkle
                    )
                    // The touch, in the inset area's own coordinates; the shader glides from it to the centre.
                    val from = if (fromCentre) centre else Offset(touch.x - horizontal.toPx(), touch.y - vertical.toPx())
                    r.shader.setFloatUniform("in_touch", from.x, from.y)
                    drawOutline(outline, b)
                } else {
                    drawOutline(outline, color, alpha = shown.value)
                }
            }
        }
        drawContent()
    }
}
