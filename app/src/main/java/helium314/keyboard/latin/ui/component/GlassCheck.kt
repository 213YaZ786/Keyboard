package helium314.keyboard.latin.ui.component

import helium314.keyboard.latin.ui.glass.GlassLook
import helium314.keyboard.latin.ui.glass.LocalGlass
import helium314.keyboard.latin.ui.glass.glassZone
import helium314.keyboard.latin.ui.glass.rememberGlassBackdrop
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.max

/*
 * Material 3's check box and radio button in glass: their shapes and sizes, drawn as a glass zone with the
 * outline as its rim, washed with the accent when chosen, the check or the dot in onPrimary; and Kyant0's
 * press: the piece grows and, while pressed, is a lens over itself.
 *
 * Material 3 (Apache-2.0), Checkbox.kt and RadioButton.kt (androidx-main),
 * https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Checkbox.kt
 * https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/RadioButton.kt
 * Check box: CheckboxSize 20 dp with 2 dp around it (CheckboxDefaultPadding), corner 2 dp (RadiusSize), outline
 * 2 dp floored to pixels (CheckboxDefaults.StrokeWidth, drawBox), outline onSurfaceVariant (UnselectedOutlineColor)
 * and primary when checked, the check in onPrimary: a path of three points (0.2, 0.5) (0.4, 0.7) (0.8, 0.3) of the
 * width, drawn along its length by checkDrawFraction, 2 dp stroke with square caps (drawCheck), springs
 * DefaultSpatial (0.9, 700; tokens/StandardMotionTokens.kt), box out snapping after 100 ms (SnapAnimationDelay).
 * The token CheckboxTokens.ContainerSize is 18 dp, used only when ComposeMaterial3Flags.isCheckboxStylingFixEnabled
 * is set, which it is not by default: the 20 dp box is what Material draws today.
 * Radio button: RadioButtonTokens.IconSize 20 dp with 2 dp around it (RadioButtonPadding), ring 2 dp
 * (RadioStrokeWidth) of radius IconSize / 2 - stroke / 2, dot of RadioButtonDotSize 12 dp, drawn with radius
 * dotRadius - stroke / 2, grown by the FastSpatial spring (0.9, 1400), colours onSurfaceVariant and primary.
 * Touch target 48 dp (minimumInteractiveComponentSize), toggleable / selectable with the role.
 *
 * Kyant0/AndroidLiquidGlass (Apache-2.0), DampedDragAnimation.kt (pressedScale 1.5, press and release springs),
 * https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/DampedDragAnimation.kt
 * and LiquidToggle.kt for the lens while pressed.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.
 */

// Checkbox.kt: CheckboxSize, RadiusSize; RadioButtonTokens.IconSize.
private val CheckBoxSize = 20.dp
private val CheckCorner = 2.dp
private val RadioSize = 20.dp
private val RadioDotSize = 12.dp
private val MarkStroke = 2.dp
// CheckboxDefaultPadding and RadioButtonPadding.
private val MarkPadding = 2.dp
// The recorded mark reaches 8 dp beyond its 24 dp, and the pressed piece (1.5 times 20 dp) is held to 36 dp.
private val MarkReach = 8.dp
private val MaxMark = 36.dp

/**
 * A check box in glass, the signature of Material 3's Checkbox. [onCheckedChange]
 * null leaves it to a parent that toggles it (a row). Without glass it is Material's.
 */
@Composable
fun GlassCheck(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val look = LocalGlass.current
    if (look == null) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
        return
    }
    val haptics = rememberHaptics()
    val source = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary
    val outline = scheme.onSurfaceVariant
    val check = scheme.onPrimary
    val shape = RoundedCornerShape(CheckCorner)
    // Checkbox.kt drawCheck keeps one path and measures it again for each frame; so do we.
    val path = remember { Path() }
    val measure = remember { PathMeasure() }
    val drawn = remember { Path() }
    GlassMark(
        look = look,
        on = checked,
        enabled = enabled,
        source = source,
        modifier = if (onCheckedChange == null) modifier else modifier.toggleable(
            value = checked,
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = { haptics.toggle(it); onCheckedChange(it) }
        ),
        shape = shape,
        markSize = CheckBoxSize,
        motion = { if (it) spring(0.9f, 700f) else snap(delayMillis = 100) },
        under = { chosen ->
            washShape(shape, primary.copy(alpha = 0.85f * chosen.coerceIn(0f, 1f)))
            // drawBox: the border inside the box, radius - half the stroke.
            val stroke = floor(MarkStroke.toPx())
            val half = stroke / 2f
            drawRoundRect(
                color = lerp(outline, primary, chosen.coerceIn(0f, 1f)),
                topLeft = Offset(half, half),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(max(0f, CheckCorner.toPx() - half)),
                style = Stroke(stroke)
            )
        },
        over = { chosen ->
            val fraction = chosen.coerceIn(0f, 1f)
            if (fraction > 0f) {
                // drawCheck.
                val width = size.width
                path.rewind()
                path.moveTo(width * 0.2f, width * 0.5f)
                path.lineTo(width * 0.4f, width * 0.7f)
                path.lineTo(width * 0.8f, width * 0.3f)
                measure.setPath(path, false)
                drawn.rewind()
                measure.getSegment(0f, measure.length * fraction, drawn, true)
                drawPath(drawn, check, style = Stroke(width = floor(MarkStroke.toPx()), cap = StrokeCap.Square))
            }
        }
    )
}

/**
 * A radio button in glass, the signature of Material 3's RadioButton. [onClick]
 * null leaves it to a parent that selects it (a row). Without glass it is Material's.
 */
@Composable
fun GlassRadio(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val look = LocalGlass.current
    if (look == null) {
        RadioButton(selected = selected, onClick = onClick, modifier = modifier, enabled = enabled)
        return
    }
    val haptics = rememberHaptics()
    val source = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary
    val ring = scheme.onSurfaceVariant
    val dot = scheme.onPrimary
    GlassMark(
        look = look,
        on = selected,
        enabled = enabled,
        source = source,
        modifier = if (onClick == null) modifier else modifier.selectable(
            selected = selected,
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = { haptics.tick(); onClick() }
        ),
        shape = CircleShape,
        markSize = RadioSize,
        motion = { spring(0.9f, 1400f) },
        under = { chosen ->
            washShape(CircleShape, primary.copy(alpha = 0.85f * chosen.coerceIn(0f, 1f)))
            // RadioButtonImpl: the ring, radius IconSize / 2 - stroke / 2.
            val stroke = MarkStroke.toPx()
            drawCircle(
                color = lerp(ring, primary, chosen.coerceIn(0f, 1f)),
                radius = size.minDimension / 2f - stroke / 2f,
                style = Stroke(stroke)
            )
        },
        over = { chosen ->
            // The dot: RadioButtonDotSize / 2 grown by the spring, drawn with radius dotRadius - stroke / 2.
            val grow = size.minDimension / RadioSize.toPx()
            val radius = RadioDotSize.toPx() / 2f * chosen.coerceAtLeast(0f) * grow - MarkStroke.toPx() / 2f
            if (radius > 0f) drawCircle(dot, radius)
        }
    )
}

// The piece both share: the resting mark (a glass zone of [shape] with [under] and [over] drawn on it), and
// while pressed a glass bead that grows over it with the lens of GlassBead. [motion] is the animation of the
// chosen fraction toward `on`.
@Composable
private fun GlassMark(
    look: GlassLook,
    on: Boolean,
    enabled: Boolean,
    source: MutableInteractionSource,
    modifier: Modifier,
    shape: Shape,
    markSize: Dp,
    motion: (Boolean) -> AnimationSpec<Float>,
    under: DrawScope.(Float) -> Unit,
    over: DrawScope.(Float) -> Unit
) {
    val scope = rememberCoroutineScope()
    // The press and its springs only: no drag here, the touch belongs to toggleable / selectable.
    val pressSprings = remember(scope) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = 0f,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {},
            onDrag = { _, _ -> }
        )
    }
    val pressed by source.collectIsPressedAsState()
    LaunchedEffect(pressed) {
        if (pressed) pressSprings.press() else if (pressSprings.pressProgress > 0.001f) pressSprings.release()
    }
    val lensing by remember(pressSprings) { derivedStateOf { pressSprings.pressProgress > 0.001f } }
    val backdrop = rememberGlassBackdrop()
    val chosen = animateFloatAsState(targetValue = if (on) 1f else 0f, animationSpec = motion(on), label = "glassMarkChosen")
    val whole = markSize + MarkPadding * 2

    Box(
        modifier
            .minimumInteractiveComponentSize()
            .alpha(if (enabled) 1f else 0.38f),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .glassOverflow(MarkReach, MarkReach, whole, whole)
                .then(if (lensing) Modifier.glassTrackSource(backdrop, look) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(markSize)
                    .glassZone(shape, look, lens = 1f)
                    .drawBehind { under(chosen.value) }
                    .drawWithContent { drawContent(); over(chosen.value) }
            )
        }
        if (lensing) {
            GlassBead(
                dampedDragAnimation = pressSprings,
                backdrop = backdrop,
                look = look,
                lensing = true,
                velocityDivisor = 50f,
                report = { IntSize(whole.roundToPx(), whole.roundToPx()) },
                centreX = { whole.toPx() / 2f },
                beadSize = { Size(markSize.toPx() * pressSprings.scaleX, markSize.toPx() * pressSprings.scaleY) },
                maxSize = DpSize(MaxMark, MaxMark),
                shape = shape,
                under = { under(chosen.value) },
                over = { over(chosen.value) },
                lensTint = Color.Transparent
            )
        }
    }
}
