package helium314.keyboard.latin.ui.component

import helium314.keyboard.latin.ui.glass.GlassBackdrop
import helium314.keyboard.latin.ui.glass.GlassLook
import helium314.keyboard.latin.ui.glass.LocalGlass
import helium314.keyboard.latin.ui.glass.drawGround
import helium314.keyboard.latin.ui.glass.glassFloating
import helium314.keyboard.latin.ui.glass.glassZone
import helium314.keyboard.latin.ui.glass.rememberGlassBackdrop
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/*
 * Material 3's switch in glass: the shape, the sizes and the thumb positions of
 * Material's Switch, the track and the thumb made of our glass, and Kyant0's
 * press: the thumb grows, stretches with its speed and, while pressed, is a lens
 * bending the track.
 *
 * Material 3 (Apache-2.0), Switch.kt and tokens/SwitchTokens.kt,
 * https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Switch.kt
 * track 52 x 32 dp (TrackWidth, TrackHeight), outline 2 dp when off (TrackOutlineWidth), thumb 16 dp off
 * (UnselectedHandleWidth), 24 dp on (SelectedHandleWidth), 28 dp pressed (PressedHandleWidth), thumb centre
 * 16 dp from the start off and 36 dp on (ThumbNode.measure: minBound, maxBound), colours outline / onPrimary
 * (UnselectedHandleColor, SelectedHandleColor), the 2 dp outline in outline (UnselectedFocusTrackOutlineColor),
 * disabled alpha 0.38.
 *
 * Kyant0/AndroidLiquidGlass (Apache-2.0), LiquidToggle.kt,
 * https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidToggle.kt
 * with DampedDragAnimation.kt: the springs (pressedScale 1.5, damped scale X and Y), the velocity stretch, the
 * drag with a snap at 0.5, the lens while pressed. Their drawBackdrop is replaced by our glass (ui/glass).
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.
 */

// SwitchTokens: TrackWidth, TrackHeight, TrackOutlineWidth, UnselectedHandleWidth, SelectedHandleWidth, PressedHandleWidth.
private val SwitchWidth = 52.dp
private val SwitchHeight = 32.dp
private val TrackOutlineWidth = 2.dp
private val ThumbOff = 16.dp
private val ThumbOn = 24.dp
private val ThumbPressed = 28.dp
// The thumb's centre travels from SwitchHeight / 2 (off) to SwitchWidth - SwitchHeight / 2 (on): 20 dp
// (Switch.kt: minBound + 8 dp = 16, maxBound = SwitchWidth - ThumbDiameter - ThumbPadding = 24, plus half of 24).
private val DragWidth = 20.dp

// Room the recorded track keeps around itself for the pressed thumb, which is larger than the track's height
// and stretches: 8 dp on every side; the thumb is held to 40 dp, so it never reaches outside what was recorded.
private val ReachX = 8.dp
private val ReachY = 8.dp
private val MaxThumb = 40.dp

/**
 * A switch in glass, the signature of Material 3's Switch: change its name
 * and nothing else at the call site. [onCheckedChange] null leaves the
 * switch to a parent that toggles it (a row). Without glass it is Material's.
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val look = LocalGlass.current
    if (look == null) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
        return
    }
    GlassSwitchBody(look, checked, onCheckedChange, modifier, enabled)
}

// LiquidToggle.kt (link above), composable body: state, DampedDragAnimation, track, thumb.
@Composable
private fun GlassSwitchBody(
    look: GlassLook,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier,
    enabled: Boolean
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { DragWidth.toPx() }
    val slop = LocalViewConfiguration.current.touchSlop
    val haptics = rememberHaptics()

    // The animation is made once; what it reads is kept current.
    val ltrNow by rememberUpdatedState(isLtr)
    val dragWidthNow by rememberUpdatedState(dragWidth)
    val slopNow by rememberUpdatedState(slop)
    val checkedNow by rememberUpdatedState(checked)
    val commit by rememberUpdatedState<(Boolean) -> Unit> { on ->
        if (on != checked) {
            haptics.toggle(on)
            onCheckedChange?.invoke(on)
        }
    }

    val animationScope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    val moved = remember { FloatArray(2) }
    // `checked` is the only truth. The fraction below is the finger's, and counts only while [dragging]:
    // from the touch until two frames after the lift, the time the owner needs to hand `checked` back.
    // Then the thumb goes to `checked`, wherever the gesture left it (Material's Switch draws only `checked`).
    var dragging by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    val settle = remember { arrayOfNulls<Job>(1) }
    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {
                moved[0] = 0f; moved[1] = 0f
                didDrag = false
                settle[0]?.cancel()
                fraction = if (checkedNow) 1f else 0f
                dragging = true
            },
            onDragStopped = {
                val on = if (didDrag) targetValue >= 0.5f else !checkedNow
                didDrag = false
                // The thumb goes where the finger chose while the owner answers, then to what it answers.
                fraction = if (on) 1f else 0f
                commit(on)
                settle[0] = animationScope.launch {
                    withFrameNanos { }
                    withFrameNanos { }
                    dragging = false
                }
            },
            onDrag = { _, dragAmount ->
                // A tap that slips a pixel is still a tap, and a scroll across the switch is not a drag:
                // it is one when it goes sideways further than the touch slop (Compose's own detectHorizontalDragGestures).
                moved[0] += abs(dragAmount.x)
                moved[1] += abs(dragAmount.y)
                if (!didDrag && moved[0] > slopNow && moved[0] > moved[1]) didDrag = true
                if (didDrag) {
                    val delta = dragAmount.x / dragWidthNow
                    fraction =
                        if (ltrNow) (fraction + delta).coerceIn(0f, 1f)
                        else (fraction - delta).coerceIn(0f, 1f)
                }
            },
            onDragCancelled = {
                // A parent took the touch to scroll: nothing was chosen, the thumb returns to `checked`.
                didDrag = false
                dragging = false
            },
            claimsDrag = { didDrag },
            consumesTouch = true
        )
    }
    // Where the thumb must be: the finger's while it is down, else `checked`'s.
    val target by remember { derivedStateOf { if (dragging) fraction else if (checkedNow) 1f else 0f } }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { target }
            .collectLatest { target ->
                dampedDragAnimation.updateValue(target)
            }
    }
    // Their LaunchedEffect(selected): a change that does not come from the finger (a row that toggles it, the
    // owner) moves the thumb with the lens pulse of animateToValue.
    LaunchedEffect(checked) {
        if (!dragging && dampedDragAnimation.targetValue != target) {
            dampedDragAnimation.animateToValue(target)
        }
    }

    // The lens is drawn only while the thumb is pressed, dragged or settling: at rest the thumb is a glass
    // bead and nothing is recorded, so a list of switches scrolling costs nothing more than glass zones.
    val lensing by remember(dampedDragAnimation) { derivedStateOf { dampedDragAnimation.pressProgress > 0.001f } }
    val trackBackdrop = rememberGlassBackdrop()
    val interactive = onCheckedChange != null && enabled

    // On: Material's selected track is primary (SwitchTokens.SelectedTrackColor), so the glass is washed with
    // primary as GlassCheck's checked box (0.85), never the pale accentTint, so on and off read apart at a glance.
    val accentTint = scheme.primary.copy(alpha = 0.85f)
    val outline = scheme.outline
    val thumbOff = scheme.outline
    val thumbOn = scheme.onPrimary

    Box(
        modifier
            .minimumInteractiveComponentSize()
            .alpha(if (enabled) 1f else 0.38f)
            // Material's Switch semantics (Switch.kt, toggleable): the role, the state, the click.
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
                if (!enabled) disabled()
                // The thumb follows `checked` when the owner answers; nothing is drawn from the click itself.
                if (interactive) onClick {
                    commit(!checkedNow)
                    true
                }
            }
            .then(if (interactive) dampedDragAnimation.modifier else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .glassOverflow(ReachX, ReachY, SwitchWidth, SwitchHeight)
                .then(if (lensing) Modifier.glassTrackSource(trackBackdrop, look) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            // The track: a glass capsule; on, washed with the accent as ZoneSurface(accent = true) does;
            // off, Material's 2 dp outline in outline as a soft rim (Switch.kt: border(TrackOutlineWidth, borderColor)).
            Box(
                Modifier
                    .size(SwitchWidth, SwitchHeight)
                    .glassZone(CircleShape, look, lens = 1f)
                    .drawBehind {
                        val f = dampedDragAnimation.value.coerceIn(0f, 1f)
                        washShape(CircleShape, accentTint.copy(alpha = accentTint.alpha * f))
                        val stroke = TrackOutlineWidth.toPx()
                        drawRoundRect(
                            color = outline.copy(alpha = 0.55f * (1f - f)),
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius((size.height - stroke) / 2f),
                            style = Stroke(stroke)
                        )
                    }
            )
        }
        GlassBead(
            dampedDragAnimation = dampedDragAnimation,
            backdrop = trackBackdrop,
            look = look,
            lensing = lensing,
            velocityDivisor = 50f,
            report = { IntSize(SwitchWidth.roundToPx(), SwitchHeight.roundToPx()) },
            centreX = { (SwitchHeight / 2).toPx() + DragWidth.toPx() * dampedDragAnimation.value.coerceIn(0f, 1f) },
            // Material's thumb: 16 dp off, 24 dp on, 28 dp pressed; the press follows the damped scale springs
            // (1 to 1.5, X and Y apart) so it overshoots and settles as theirs does.
            beadSize = {
                val f = dampedDragAnimation.value.coerceIn(0f, 1f)
                val base = (ThumbOff + (ThumbOn - ThumbOff) * f).toPx()
                val pressed = ThumbPressed.toPx()
                val sx = (dampedDragAnimation.scaleX - 1f) / 0.5f
                val sy = (dampedDragAnimation.scaleY - 1f) / 0.5f
                Size(base + (pressed - base) * sx, base + (pressed - base) * sy)
            },
            maxSize = DpSize(MaxThumb, MaxThumb),
            shape = CircleShape,
            // By night the on-colour of Material (onPrimary on, outline off), by day lighter, the glass showing.
            under = {
                val f = dampedDragAnimation.value.coerceIn(0f, 1f)
                val wash = if (look.dark) lerp(thumbOff.copy(0.9f), thumbOn.copy(0.9f), f)
                else lerp(thumbOff.copy(0.4f), thumbOn.copy(0.75f), f)
                washShape(CircleShape, wash)
            },
            lensTint = Color.Transparent
        )
    }
}

/** Fills the shape of the node being drawn with [color]: the wash laid over the glass. */
internal fun DrawScope.washShape(shape: Shape, color: Color) {
    drawOutline(shape.createOutline(size, layoutDirection, this), color)
}

/**
 * The movable piece of GlassSwitch and GlassSlider, and the pressed piece of GlassCheck and GlassRadio:
 * a glass zone of [shape] (the resting bead) that grows by the press spring and stretches with its speed, and
 * over which, while pressed, our glass lens (glassFloating, over the track recorded in [backdrop]) fades in.
 * It is sized in layout, not scaled as a layer, so the glass sees its real size and place. [report] is the size
 * the piece takes in its parent, [centreX] where its centre lies in it in pixels (for a left to right layout),
 * [beadSize] its size before the stretch, held to [maxSize]. [under] draws on the glass beneath the lens, [over]
 * on top of it. The lens is clear (theirs when pressed has no surface), except for [lensTint].
 *
 * Port of Kyant0/AndroidLiquidGlass (Apache-2.0), LiquidToggle.kt and LiquidSlider.kt
 * https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidSlider.kt
 * : the layerBlock (scale by the springs, velocity stretch: scaleX / (1 - clamp(velocity * 0.75, 0.2)),
 * scaleY * (1 - clamp(velocity * 0.25, 0.2))) and the lens while pressed (lens, highlight and the surface fading with
 * the press progress), here with our glassZone and glassFloating (ui/glass/GlassBackdrop.kt).
 */
@Composable
internal fun GlassBead(
    dampedDragAnimation: DampedDragAnimation,
    backdrop: GlassBackdrop,
    look: GlassLook,
    lensing: Boolean,
    velocityDivisor: Float,
    report: Density.(Constraints) -> IntSize,
    centreX: Density.(IntSize) -> Float,
    beadSize: Density.() -> Size,
    maxSize: DpSize,
    shape: Shape,
    under: DrawScope.() -> Unit,
    lensTint: Color,
    modifier: Modifier = Modifier,
    gesture: Modifier = Modifier,
    over: (DrawScope.() -> Unit)? = null
) {
    Box(
        modifier
            .layout { measurable, constraints ->
                val reported = report(constraints)
                val velocity = dampedDragAnimation.velocity / velocityDivisor
                val natural = beadSize()
                val w = (natural.width / (1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)))
                    .coerceIn(1f, maxSize.width.toPx()).roundToInt()
                val h = (natural.height * (1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)))
                    .coerceIn(1f, maxSize.height.toPx()).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(w, h))
                val x = (centreX(reported) - w / 2f).roundToInt()
                val y = ((reported.height - h) / 2f).roundToInt()
                layout(reported.width, reported.height) { placeable.placeRelative(x, y) }
            }
            .then(gesture)
            .glassZone(shape, look, lens = 1f)
            .drawBehind { under() }
            .then(
                if (over == null) Modifier
                else Modifier.drawWithContent { drawContent(); over() }
            )
    ) {
        if (lensing) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = dampedDragAnimation.pressProgress.coerceIn(0f, 1f) }
                    .glassFloating(backdrop, shape, look, tint = lensTint)
            )
        }
    }
}

/**
 * Makes the box it is on [extraX] and [extraY] larger on every side than the
 * size it reports to its parent ([width] and [height], or the incoming
 * maximum when unspecified), so what it records reaches beyond the track.
 * Own layout, with Compose's Modifier.layout (the pattern of its own
 * Modifier.padding with negative offsets, foundation PaddingNode).
 */
internal fun Modifier.glassOverflow(
    extraX: Dp,
    extraY: Dp,
    width: Dp = Dp.Unspecified,
    height: Dp = Dp.Unspecified
): Modifier = layout { measurable, constraints ->
    val ex = extraX.roundToPx()
    val ey = extraY.roundToPx()
    val w = if (width != Dp.Unspecified) width.roundToPx() else constraints.maxWidth
    val h = if (height != Dp.Unspecified) height.roundToPx() else constraints.maxHeight
    val placeable = measurable.measure(Constraints.fixed(w + 2 * ex, h + 2 * ey))
    layout(w, h) { placeable.place(-ex, -ey) }
}

/**
 * Records this content over the ambient light as a backdrop for the thumb
 * lying above it, like glassSource (ui/glass/GlassBackdrop.kt, itself from
 * Kyant0/AndroidLiquidGlass layerBackdrop), but shows on screen only the
 * content: glassSource also draws the ground it records, an opaque patch
 * over the card a switch sits on.
 */
internal fun Modifier.glassTrackSource(backdrop: GlassBackdrop, look: GlassLook): Modifier =
    this then GlassTrackSourceElement(backdrop, look)

private data class GlassTrackSourceElement(val backdrop: GlassBackdrop, val look: GlassLook) :
    ModifierNodeElement<GlassTrackSourceNode>() {
    override fun create() = GlassTrackSourceNode(backdrop, look)
    override fun update(node: GlassTrackSourceNode) {
        node.backdrop = backdrop; node.look = look; node.invalidateDraw()
    }
    override fun InspectorInfo.inspectableProperties() { name = "glassTrackSource" }
}

private class GlassTrackSourceNode(var backdrop: GlassBackdrop, var look: GlassLook) :
    Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val o = coordinates.positionInRoot()
        val w = coordinates.findRootCoordinates().size.toSize()
        if (o != backdrop.origin || w != backdrop.window) {
            backdrop.origin = o; backdrop.window = w; invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val content = this
        backdrop.layer.record {
            if (backdrop.window.width > 0f) drawGround(look, look.ground, backdrop.origin, backdrop.window)
            content.drawContent()
        }
        content.drawContent()
    }
}
