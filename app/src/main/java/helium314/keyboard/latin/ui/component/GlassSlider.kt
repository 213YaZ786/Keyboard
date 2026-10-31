package helium314.keyboard.latin.ui.component

import helium314.keyboard.latin.ui.glass.GlassLook
import helium314.keyboard.latin.ui.glass.LocalGlass
import helium314.keyboard.latin.ui.glass.glassZone
import helium314.keyboard.latin.ui.glass.rememberGlassBackdrop
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest

/*
 * Material 3 Expressive's slider in glass: the shape and the sizes of Material's Slider (Large by default, as
 * Android 16's brightness slider), its track in two parts of glass (the active one washed with the accent) around
 * a gap at the handle, an optional icon inset at the start of the active part, its stop indicator and ticks, its
 * handle a bar of glass; and Kyant0's press: the handle grows, stretches with its speed and, while pressed, is a lens
 * bending the track.
 *
 * Material 3 (Apache-2.0), Slider.kt and tokens/SliderTokens.kt (androidx-main),
 * https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Slider.kt
 * handle 4 dp wide (HandleWidth), gap of 6 dp beside the handle (ActiveHandleLeadingSpace,
 * ThumbTrackGapSize) so the track ends 8 dp from the handle's centre (thumbWidth / 2 + gap), 2 dp corners
 * towards the handle (TrackInsideCornerSize), stop indicator 4 dp at the end of the inactive part, 8 dp
 * from the end (StopIndicatorSize, drawStopIndicator: its centre at end - cornerSize), ticks 4 dp from
 * corner to end - corner (drawTrack), the track inset by half the handle (Slider layout: trackOffsetX),
 * 48 dp touch height (minimumInteractiveComponentSize), the semantics (sliderSemantics) and the steps
 * (stepsToTickFractions, snapValueToTick).
 *
 * Material Components Android (Apache-2.0), the Expressive sizes, lib/java/com/google/android/material/slider/res/values/tokens.xml
 * https://github.com/material-components/material-components-android/blob/master/lib/java/com/google/android/material/slider/res/values/tokens.xml
 * (m3_comp_slider_{xsmall,small,medium,large,xlarge}_active_track_height, _active_track_shape_leading,
 * _active_handle_height, _icon_size) and the inset icon, BaseSlider.java (calculateTrackIconBounds: shown when the
 * part is at least icon + 2 x padding wide, padding m3_slider_track_icon_padding = 10 dp, on the start side).
 * https://github.com/material-components/material-components-android/blob/master/lib/java/com/google/android/material/slider/BaseSlider.java
 * The icon drawing follows androidx's SliderWithTrackIconsSample (icon size and padding, the same width test, tinted).
 * https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/samples/src/main/java/androidx/compose/material3/samples/SliderSamples.kt
 *
 * Kyant0/AndroidLiquidGlass (Apache-2.0), LiquidSlider.kt,
 * https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidSlider.kt
 * with DampedDragAnimation.kt: the springs, the drag on the handle and the tap on the track, the stretch
 * (velocity / 10), the lens while pressed. Their drawBackdrop is replaced by our glass (ui/glass).
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.
 */

private val TouchHeight = 48.dp
private val HandleWidth = 4.dp
private val HandleGap = 6.dp
// BaseSlider.java, m3_slider_track_icon_padding.
private val IconPadding = 10.dp
private val InsideCorner = 2.dp
private val StopSize = 4.dp
// Pressed, the handle widens to a pill (Material's narrows to 2 dp, which leaves no room for a lens): ours.
private val PressedHandleWidth = 20.dp
// The recorded track reaches 20 dp beyond each end, which holds the pressed and stretched handle (held to 40 dp wide).
private val SliderReach = 20.dp
private val MaxHandleWidth = 40.dp

// The sizes of Material 3 Expressive's slider, as Material Components Android's tokens.xml gives them
// (md.comp.slider.xsmall to xlarge: active track height, active track shape leading, active handle height, icon size):
// https://github.com/material-components/material-components-android/blob/master/lib/java/com/google/android/material/slider/res/values/tokens.xml
// "icon" is 0 where tokens.xml has no icon size (below Medium). "height" is the whole slider: the handle, at least
// the 48 dp touch target (BaseSlider.java minTouchTargetSize).
enum class GlassSliderSize(val track: Dp, val corner: Dp, val handle: Dp, val icon: Dp) {
    ExtraSmall(16.dp, 8.dp, 44.dp, 0.dp),
    Small(24.dp, 8.dp, 44.dp, 0.dp),
    Medium(40.dp, 12.dp, 44.dp, 24.dp),
    Large(56.dp, 16.dp, 68.dp, 24.dp),
    ExtraLarge(96.dp, 28.dp, 108.dp, 32.dp);

    val height: Dp get() = if (handle > TouchHeight) handle else TouchHeight
}

/**
 * A slider in glass, the signature of Material 3's Slider: change its name
 * and nothing else at the call site. Without glass it is Material's.
 */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    size: GlassSliderSize = GlassSliderSize.Large
) {
    val look = LocalGlass.current
    if (look == null) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished
        )
        return
    }
    GlassSliderBody(look, value, onValueChange, modifier, valueRange, steps, onValueChangeFinished, enabled, icon, size)
}

// LiquidSlider.kt (link above), composable body: state, DampedDragAnimation, track, handle.
@Composable
private fun GlassSliderBody(
    look: GlassLook,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (() -> Unit)?,
    enabled: Boolean,
    icon: ImageVector?,
    sliderSize: GlassSliderSize
) {
    val sliderHeight = sliderSize.height
    val iconPainter = icon?.let { rememberVectorPainter(it) }
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val slop = LocalViewConfiguration.current.touchSlop
    val haptics = rememberHaptics()

    // The animation is made once; what it reads is kept current.
    val ltrNow by rememberUpdatedState(isLtr)
    val slopNow by rememberUpdatedState(slop)
    val rangeNow by rememberUpdatedState(valueRange)
    val stepsNow by rememberUpdatedState(steps)
    val valueNow by rememberUpdatedState(value.coerceIn(valueRange))
    val finishedNow by rememberUpdatedState(onValueChangeFinished)

    // The value last given to the owner, until it hands the new one back: a burst of touch events
    // between two compositions must not report the same step twice nor tick twice.
    val last = remember { floatArrayOf(Float.NaN) }
    val emit by rememberUpdatedState<(Float) -> Unit> { v ->
        val before = if (last[0].isNaN()) valueNow else last[0]
        if (v != before) {
            if (steps > 0 && stepIndex(v, valueRange, steps) != stepIndex(before, valueRange, steps)) haptics.tick()
            last[0] = v
            onValueChange(v)
        }
    }

    val interactive = enabled
    val animationScope = rememberCoroutineScope()
    val trackBackdrop = rememberGlassBackdrop()
    var didDrag by remember { mutableStateOf(false) }
    val moved = remember { FloatArray(2) }
    // The drag as a fraction of the track, before the steps snap it.
    val raw = remember { FloatArray(1) }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .heightIn(min = sliderHeight)
            .alpha(if (enabled) 1f else 0.38f)
            // Material's sliderSemantics: the range, the steps, the set progress action.
            .semantics {
                if (!enabled) disabled()
                progressBarRangeInfo = ProgressBarRangeInfo(valueNow, valueRange, steps)
                setProgress { target ->
                    val v = valueAt(fractionOf(target, valueRange), valueRange, steps)
                    if (v == valueNow) {
                        false
                    } else {
                        last[0] = Float.NaN
                        emit(v)
                        finishedNow?.invoke()
                        true
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // Material's Slider layout: the track starts half a handle in; the corner is half the track's height.
        val half = with(density) { (HandleWidth / 2).roundToPx() }
        val trackWidth = max(constraints.maxWidth - 2 * half, 1).toFloat()
        val corner = with(density) { sliderSize.corner.toPx() }
        val trackWidthNow by rememberUpdatedState(trackWidth)
        val cornerNow by rememberUpdatedState(corner)

        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = fractionOf(valueNow, rangeNow),
                valueRange = 0f..1f,
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {
                    moved[0] = 0f; moved[1] = 0f
                    didDrag = false
                    last[0] = Float.NaN
                    raw[0] = fractionOf(valueNow, rangeNow)
                },
                onDragStopped = {
                    if (didDrag) {
                        didDrag = false
                        finishedNow?.invoke()
                    }
                },
                onDrag = { _, dragAmount ->
                    // A touch is a drag when it goes sideways further than the touch slop (Compose's own
                    // detectHorizontalDragGestures), so a scroll across the slider does not move it.
                    moved[0] += abs(dragAmount.x)
                    moved[1] += abs(dragAmount.y)
                    if (!didDrag && moved[0] > slopNow && moved[0] > moved[1]) didDrag = true
                    if (didDrag) {
                        // Their onDrag: delta = range * dx / trackWidth, mirrored in a right to left layout;
                        // with steps the ticks lie between corner and end - corner, so the handle's travel is shorter.
                        val travel = if (stepsNow > 0) max(trackWidthNow - 2 * cornerNow, 1f) else trackWidthNow
                        val delta = dragAmount.x / travel
                        raw[0] = (raw[0] + if (ltrNow) delta else -delta).coerceIn(0f, 1f)
                        emit(valueAt(raw[0], rangeNow, stepsNow))
                    }
                },
                onDragCancelled = {
                    if (didDrag) {
                        didDrag = false
                        finishedNow?.invoke()
                    }
                },
                claimsDrag = { didDrag },
                consumesTouch = true
            )
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { fractionOf(valueNow, rangeNow) }
                .collectLatest { fraction ->
                    if (dampedDragAnimation.targetValue != fraction) {
                        dampedDragAnimation.updateValue(fraction)
                    }
                }
        }

        // The lens is drawn only while the handle is pressed, dragged or settling: at rest it is a bar of
        // glass, and a list of sliders scrolling costs nothing more than glass zones.
        val lensing by remember(dampedDragAnimation) { derivedStateOf { dampedDragAnimation.pressProgress > 0.001f } }

        // Their detectTapGestures on the track: a tap goes there. Over the whole height, not only the track.
        Box(
            Modifier
                .matchParentSize()
                .then(
                    if (!interactive) Modifier else Modifier.pointerInput(animationScope, trackWidth, corner, isLtr) {
                        detectTapGestures { position ->
                            val x = (if (isLtr) position.x else size.width - position.x) - half
                            val f = trackFraction(x, trackWidth, corner, stepsNow)
                            val v = valueAt(f, rangeNow, stepsNow)
                            dampedDragAnimation.animateToValue(fractionOf(v, rangeNow))
                            last[0] = Float.NaN
                            emit(v)
                            finishedNow?.invoke()
                        }
                    }
                )
        )

        Box(
            Modifier
                .glassOverflow(SliderReach, 0.dp, height = sliderHeight)
                .then(if (lensing) Modifier.glassTrackSource(trackBackdrop, look) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .padding(horizontal = SliderReach)
                    .padding(horizontal = HandleWidth / 2)
                    .fillMaxWidth()
                    .height(sliderSize.track)
            ) {
                TrackSegment(true, dampedDragAnimation, look, steps, accent, sliderSize, iconPainter)
                TrackSegment(false, dampedDragAnimation, look, steps, accent, sliderSize, null)
                if (steps > 0) {
                    val dark = look.dark
                    val scheme = MaterialTheme.colorScheme
                    val activeTick = if (dark) scheme.onSurface else scheme.onPrimaryContainer
                    Box(
                        Modifier
                            .matchParentSize()
                            .drawBehind {
                                // Material's drawTrack: a tick at each step from corner to end - corner, none at the
                                // last (the stop indicator is there), none in the gap beside the handle; active ones
                                // in the colour of text on the accent, the others in the accent.
                                val rtl = layoutDirection == LayoutDirection.Rtl
                                val c = sliderSize.corner.toPx()
                                val x = trackX(dampedDragAnimation.progress.coerceIn(0f, 1f), size.width, c, steps)
                                val gap = (HandleWidth / 2 + HandleGap).toPx()
                                for (i in 0..steps) {
                                    val cx = c + (size.width - 2 * c) * (i / (steps + 1f))
                                    if (cx >= x - gap && cx <= x + gap) continue
                                    drawCircle(
                                        color = if (cx < x - gap) activeTick else accent,
                                        radius = StopSize.toPx() / 2f,
                                        center = Offset(if (rtl) size.width - cx else cx, size.height / 2f)
                                    )
                                }
                            }
                    )
                }
            }
        }

        // The handle: Material's 4 dp wide bar (the size's handle height), in glass washed with the primary; pressed it widens and is a lens.
        GlassBead(
            dampedDragAnimation = dampedDragAnimation,
            backdrop = trackBackdrop,
            look = look,
            lensing = lensing,
            velocityDivisor = 10f,
            report = { c -> IntSize(c.maxWidth, sliderHeight.roundToPx()) },
            // Material's Slider layout: the handle's centre at trackOffsetX + the track's position of the value.
            centreX = { half + trackX(dampedDragAnimation.progress.coerceIn(0f, 1f), trackWidth, corner, steps) },
            beadSize = {
                val sx = ((dampedDragAnimation.scaleX - 1f) / 0.5f).coerceAtLeast(0f)
                Size(HandleWidth.toPx() + (PressedHandleWidth - HandleWidth).toPx() * sx, sliderSize.handle.toPx())
            },
            maxSize = DpSize(MaxHandleWidth, sliderHeight),
            shape = CircleShape,
            under = { washShape(CircleShape, accent.copy(alpha = 0.9f)) },
            lensTint = accent.copy(alpha = 0.25f),
            gesture = if (interactive) dampedDragAnimation.modifier else Modifier
        )
    }
}

// One part of Material's track, in glass: the active one from the start to the handle's gap, washed with the
// accent (look.accentTint, as ZoneSurface(accent = true)), the inactive one from the gap to the end, plain glass
// with the stop indicator. Corners: the size's corner outside, 2 dp towards the handle (Slider.kt drawTrack).
// The active one carries the inset icon at its start when it is wide enough: BaseSlider.java calculateTrackIconBounds
// https://github.com/material-components/material-components-android/blob/master/lib/java/com/google/android/material/slider/BaseSlider.java
// and androidx's SliderWithTrackIconsSample (translate, then the painter drawn with ColorFilter.tint).
@Composable
private fun TrackSegment(
    active: Boolean,
    dampedDragAnimation: DampedDragAnimation,
    look: GlassLook,
    steps: Int,
    accent: Color,
    sliderSize: GlassSliderSize,
    iconPainter: Painter?
) {
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val outer = sliderSize.corner
    val shape = if (active) {
        RoundedCornerShape(topStart = outer, topEnd = InsideCorner, bottomEnd = InsideCorner, bottomStart = outer)
    } else {
        RoundedCornerShape(topStart = InsideCorner, topEnd = outer, bottomEnd = outer, bottomStart = InsideCorner)
    }
    Box(
        Modifier
            .layout { measurable, constraints ->
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                val x = trackX(dampedDragAnimation.progress.coerceIn(0f, 1f), w.toFloat(), sliderSize.corner.toPx(), steps)
                val gap = (HandleWidth / 2 + HandleGap).toPx()
                val start = if (active) 0f else x + gap
                val end = if (active) x - gap else w.toFloat()
                val segment = (end - start).roundToInt()
                // Nothing where the handle leaves no room (at either end).
                val placeable = if (segment >= 1) measurable.measure(Constraints.fixed(segment, h)) else null
                layout(w, h) { placeable?.placeRelative(start.roundToInt(), 0) }
            }
            .glassZone(shape, look, lens = 1f)
            .drawBehind {
                if (active) {
                    // Material's active track is primary (SliderTokens.ActiveTrackColor): washed with primary as
                    // GlassSwitch's on track and GlassCheck's box (0.85), never the pale accentTint.
                    washShape(shape, accent.copy(alpha = 0.85f))
                    if (iconPainter != null && sliderSize.icon > 0.dp) {
                        // calculateTrackIconBounds: only if the part is at least icon + 2 x padding wide; the
                        // icon on the start side (the right one in a right to left layout), centred in the track.
                        val iconPx = sliderSize.icon.toPx()
                        val pad = IconPadding.toPx()
                        if (size.width >= iconPx + 2 * pad) {
                            val rtl = layoutDirection == LayoutDirection.Rtl
                            val left = if (rtl) size.width - pad - iconPx else pad
                            translate(left, (size.height - iconPx) / 2f) {
                                with(iconPainter) { draw(Size(iconPx, iconPx), colorFilter = ColorFilter.tint(onAccent)) }
                            }
                        }
                    }
                } else {
                    // drawStopIndicator: 4 dp, its centre one corner from the end, only if there is room.
                    val c = outer.toPx()
                    if (size.width > c) {
                        val rtl = layoutDirection == LayoutDirection.Rtl
                        drawCircle(accent, StopSize.toPx() / 2f, Offset(if (rtl) c else size.width - c, size.height / 2f))
                    }
                }
            }
    )
}

// Where the value at [f] (0 to 1) lies on a track [width] wide. With steps, Material draws the ticks between
// [corner] and width - corner (drawTrack), so the handle goes the same way (continuously, where Slider.kt puts
// the first and last steps at the very ends).
private fun trackX(f: Float, width: Float, corner: Float, steps: Int): Float =
    if (steps > 0) corner + (width - 2 * corner) * f else width * f

// The reverse: the fraction at [x] on the track.
private fun trackFraction(x: Float, width: Float, corner: Float, steps: Int): Float =
    if (steps > 0) ((x - corner) / max(width - 2 * corner, 1f)).coerceIn(0f, 1f) else (x / width).coerceIn(0f, 1f)

// The value at [fraction] of the range, on a step when there are steps. Material 3's Slider.kt:
// stepsToTickFractions and snapValueToTick (the nearest of steps + 2 evenly spaced ticks).
private fun valueAt(fraction: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Float {
    val f = fraction.coerceIn(0f, 1f)
    val snapped = if (steps > 0) (f * (steps + 1)).roundToInt().toFloat() / (steps + 1) else f
    return range.start + (range.endInclusive - range.start) * snapped
}

// Where [value] lies in the range, 0 to 1; an empty range has only its start.
private fun fractionOf(value: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    return if (span <= 0f) 0f else ((value - range.start) / span).coerceIn(0f, 1f)
}

// Which tick of the steps [value] is on (Material 3's Slider.kt, snapValueToTick).
private fun stepIndex(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Int =
    (fractionOf(value, range) * (steps + 1)).roundToInt()
