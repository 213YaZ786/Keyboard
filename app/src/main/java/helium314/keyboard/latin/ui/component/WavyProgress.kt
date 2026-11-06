/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package helium314.keyboard.latin.ui.component

// Material 3 Expressive's wavy progress indicators, ported from androidx material3 (Apache-2.0), which our material3 1.4.0
// lacks. Trimmed to the linear pieces and the determinate circular one, the code as it is, renamed so it never clashes with material3:
// LinearWavyProgressIndicator -> LinearWavyProgress, CircularWavyProgressIndicator -> CircularWavyProgress,
// WavyProgressIndicatorDefaults -> WavyProgressDefaults. Sources:
// - LinearWavyProgress (both overloads), CircularWavyProgress (determinate), WavyProgressDefaults, the amplitude animation specs
//   and animateIndeterminateCycle
//   https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/WavyProgressIndicator.kt
// - the drawing (modifier, node, handoff, stop indicator, drawing cache)
//   https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/LinearWavyProgressModifiers.kt
// - the indeterminate lines' keyframes and timings (linearIndeterminate*AnimationSpec, LinearAnimationDuration, delays, durations)
//   https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/ProgressIndicator.kt
// - the circular drawing (modifier, node, handoff, drawing cache, the wavy star and circle shapes)
//   https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/CircularWavyProgressModifiers.kt
// - the circular path helpers (RoundedPolygon.toPath, Morph.toPath)
//   https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/ShapeUtil.kt
// - the indeterminate circular keyframes the node still plays when progress is not finite (circularIndeterminate*AnimationSpec, constants)
//   https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/ProgressIndicator.kt
// - tokens copied as constants: LinearProgressIndicatorTokens (v0_7_0), CircularProgressIndicatorTokens, ProgressIndicatorTokens (v0_4_0) and the MotionTokens used
//   https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/CircularProgressIndicatorTokens.kt
//   https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/LinearProgressIndicatorTokens.kt
//   https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/ProgressIndicatorTokens.kt
//   https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/MotionTokens.kt
// - IncreaseVerticalSemanticsBounds, a small copy of material3's internal one
//   https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/AccessibilityUtil.kt
import androidx.annotation.FloatRange
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationScope
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VectorizedAnimationSpec
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.CacheDrawModifierNode
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Material Design determinate wavy linear progress indicator (Material 3 Expressive).
 *
 * By default there is no animation between [progress] values. You can use
 * [WavyProgressDefaults.ProgressAnimationSpec] as the default recommended [AnimationSpec] when
 * animating progress.
 *
 * @param progress the progress of this progress indicator, where 0.0 represents no progress and 1.0
 *   represents full progress. Values outside of this range are coerced into the range. Return a
 *   non-finite value (such as [Float.NaN] or [Float.POSITIVE_INFINITY]) while the progress is
 *   unknown to display an indeterminate indicator. When the progress becomes known, the
 *   indeterminate lines leave the track and the indicator then animates from no progress to the
 *   returned value.
 * @param modifier the [Modifier] to be applied to this progress indicator
 * @param color the progress indicator color
 * @param trackColor the indicator's track color, visible when the progress has not reached the area
 *   of the overall indicator yet
 * @param stroke a [Stroke] that will be used to draw this indicator
 * @param trackStroke a [Stroke] that will be used to draw the indicator's track
 * @param gapSize the gap between the track and the progress parts of the indicator
 * @param stopSize the size of the stop indicator at the end of the track
 * @param amplitude a lambda that provides an amplitude for the wave path as a function of the
 *   indicator's progress. 0.0 represents no amplitude, and 1.0 represents an amplitude that will
 *   take the full height of the progress indicator. The lambda receives [Float.NaN] while the
 *   indicator is indeterminate.
 * @param wavelength the length of a wave. While the indicator is indeterminate, the
 *   [WavyProgressDefaults.LinearIndeterminateWavelength] is used instead.
 * @param waveSpeed the speed in which the wave will move, in dp per second; by default it matches
 *   the [wavelength] to move the wave by one wave length per second.
 */
// Source: LinearWavyProgressIndicator (determinate), WavyProgressIndicator.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/WavyProgressIndicator.kt
@Composable
fun LinearWavyProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = WavyProgressDefaults.indicatorColor,
    trackColor: Color = WavyProgressDefaults.trackColor,
    stroke: Stroke = WavyProgressDefaults.linearIndicatorStroke,
    trackStroke: Stroke = WavyProgressDefaults.linearTrackStroke,
    gapSize: Dp = WavyProgressDefaults.LinearIndicatorTrackGapSize,
    stopSize: Dp = WavyProgressDefaults.LinearTrackStopIndicatorSize,
    amplitude: (progress: Float) -> Float = WavyProgressDefaults.indicatorAmplitude,
    wavelength: Dp = WavyProgressDefaults.LinearDeterminateWavelength,
    waveSpeed: Dp = wavelength, // Match to 1 wavelength per second
) {
    Spacer(
        modifier =
            modifier
                .then(IncreaseVerticalSemanticsBounds)
                .semantics(mergeDescendants = true) {
                    val currentProgress = progress()
                    progressBarRangeInfo =
                        if (!currentProgress.isFinite()) {
                            ProgressBarRangeInfo.Indeterminate
                        } else {
                            ProgressBarRangeInfo(currentProgress.fastCoerceIn(0f, 1f), 0f..1f)
                        }
                }
                .size(
                    width = WavyProgressDefaults.LinearContainerWidth,
                    height = WavyProgressDefaults.LinearContainerHeight,
                )
                .clipToBounds()
                .linearWavyProgressIndicator(
                    progress = progress,
                    color = color,
                    trackColor = trackColor,
                    stroke = stroke,
                    trackStroke = trackStroke,
                    gapSize = gapSize,
                    stopSize = stopSize,
                    amplitude = amplitude,
                    wavelength = wavelength,
                    waveSpeed = waveSpeed,
                )
    )
}

/**
 * Material Design indeterminate wavy linear progress indicator (Material 3 Expressive).
 *
 * @param modifier the [Modifier] to be applied to this progress indicator
 * @param color the progress indicator color
 * @param trackColor the indicator's track color, visible when the progress has not reached the area
 *   of the overall indicator yet
 * @param stroke a [Stroke] that will be used to draw this indicator
 * @param trackStroke a [Stroke] that will be used to draw the indicator's track
 * @param gapSize the gap between the track and the progress parts of the indicator
 * @param amplitude the wave's amplitude. 0.0 represents no amplitude, and 1.0 represents an
 *   amplitude that will take the full height of the progress indicator.
 * @param wavelength the length of a wave
 * @param waveSpeed the speed in which the wave will move, in dp per second; by default it matches
 *   the [wavelength] to move the wave by one wave length per second.
 */
// Source: LinearWavyProgressIndicator (indeterminate), WavyProgressIndicator.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/WavyProgressIndicator.kt
@Composable
fun LinearWavyProgress(
    modifier: Modifier = Modifier,
    color: Color = WavyProgressDefaults.indicatorColor,
    trackColor: Color = WavyProgressDefaults.trackColor,
    stroke: Stroke = WavyProgressDefaults.linearIndicatorStroke,
    trackStroke: Stroke = WavyProgressDefaults.linearTrackStroke,
    gapSize: Dp = WavyProgressDefaults.LinearIndicatorTrackGapSize,
    @FloatRange(from = 0.0, to = 1.0) amplitude: Float = 1f,
    wavelength: Dp = WavyProgressDefaults.LinearIndeterminateWavelength,
    waveSpeed: Dp = wavelength, // Match to 1 wavelength per second
) {
    Spacer(
        modifier =
            modifier
                .then(IncreaseVerticalSemanticsBounds)
                .progressSemantics()
                .size(
                    WavyProgressDefaults.LinearContainerWidth,
                    WavyProgressDefaults.LinearContainerHeight,
                )
                .clipToBounds()
                .linearWavyProgressIndicator(
                    progress = { Float.NaN },
                    color = color,
                    trackColor = trackColor,
                    stroke = stroke,
                    trackStroke = trackStroke,
                    gapSize = gapSize,
                    stopSize = 0.dp,
                    amplitude = { amplitude },
                    wavelength = wavelength,
                    waveSpeed = waveSpeed,
                    indeterminateWavelength = wavelength,
                    indeterminateWaveSpeed = waveSpeed,
                )
    )
}

/**
 * Material Design determinate wavy circular progress indicator (Material 3 Expressive).
 *
 * By default there is no animation between [progress] values. You can use
 * [WavyProgressDefaults.ProgressAnimationSpec] as the default recommended [AnimationSpec] when
 * animating progress. The container is [WavyProgressDefaults.CircularContainerSize] unless the
 * caller's [modifier] fixes another size first, as the thick sample does.
 *
 * @param progress the progress of this progress indicator, where 0.0 represents no progress and 1.0
 *   represents full progress. Values outside of this range are coerced into the range. Return a
 *   non-finite value (such as [Float.NaN] or [Float.POSITIVE_INFINITY]) while the progress is
 *   unknown to display an indeterminate indicator.
 * @param modifier the [Modifier] to be applied to this progress indicator
 * @param color the progress indicator color
 * @param trackColor the indicator's track color, visible when the progress has not reached the area
 *   of the overall indicator yet
 * @param stroke a [Stroke] that will be used to draw this indicator
 * @param trackStroke a [Stroke] that will be used to draw the indicator's track
 * @param gapSize the gap between the track and the progress parts of the indicator
 * @param amplitude a lambda that provides an amplitude for the wave path as a function of the
 *   indicator's progress. 0.0 represents no amplitude, and 1.0 represents a max amplitude.
 * @param wavelength the length of a wave in this circular indicator. Note that the actual
 *   wavelength may be different to ensure a continuous wave shape.
 * @param waveSpeed the speed in which the wave will move when the [amplitude] is greater than zero,
 *   in dp per second; by default it matches the [wavelength].
 */
// Source: CircularWavyProgressIndicator (determinate), WavyProgressIndicator.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/WavyProgressIndicator.kt
@Composable
fun CircularWavyProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = WavyProgressDefaults.indicatorColor,
    trackColor: Color = WavyProgressDefaults.trackColor,
    stroke: Stroke = WavyProgressDefaults.circularIndicatorStroke,
    trackStroke: Stroke = WavyProgressDefaults.circularTrackStroke,
    gapSize: Dp = WavyProgressDefaults.CircularIndicatorTrackGapSize,
    amplitude: (progress: Float) -> Float = WavyProgressDefaults.indicatorAmplitude,
    wavelength: Dp = WavyProgressDefaults.CircularWavelength,
    waveSpeed: Dp = wavelength, // Match to 1 wavelength per second
) {
    Spacer(
        modifier =
            modifier
                .size(WavyProgressDefaults.CircularContainerSize)
                .circularWavyProgressIndicator(
                    progress = progress,
                    color = color,
                    trackColor = trackColor,
                    stroke = stroke,
                    trackStroke = trackStroke,
                    gapSize = gapSize,
                    amplitude = amplitude,
                    wavelength = wavelength,
                    waveSpeed = waveSpeed,
                )
                .semantics(mergeDescendants = true) {
                    val currentProgress = progress()
                    progressBarRangeInfo =
                        if (!currentProgress.isFinite()) {
                            ProgressBarRangeInfo.Indeterminate
                        } else {
                            ProgressBarRangeInfo(currentProgress.fastCoerceIn(0f, 1f), 0f..1f)
                        }
                }
    )
}

/** Contains the default values used for wavy progress indicators (the linear and circular ones). */
// Source: WavyProgressIndicatorDefaults, WavyProgressIndicator.kt; the colours are ProgressIndicatorTokens.ActiveIndicatorColor
// (Primary) and TrackColor (SecondaryContainer), the sizes LinearProgressIndicatorTokens
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/WavyProgressIndicator.kt
object WavyProgressDefaults {

    /**
     * A default [AnimationSpec] that should be used when animating between progress in a
     * determinate progress indicator.
     */
    val ProgressAnimationSpec: AnimationSpec<Float> =
        tween(durationMillis = MotionDurationLong2, easing = MotionEasingLinear)

    /** A default active indicator [Color]. */
    val indicatorColor: Color
        @Composable get() = MaterialTheme.colorScheme.primary

    /** A default track [Color]. */
    val trackColor: Color
        @Composable get() = MaterialTheme.colorScheme.secondaryContainer

    /** A default linear progress indicator active indicator [Stroke]. */
    val linearIndicatorStroke: Stroke
        @Composable
        get() =
            Stroke(
                width = with(LocalDensity.current) { LinearActiveThickness.toPx() },
                cap = StrokeCap.Round,
            )

    /** A default linear progress indicator track [Stroke]. */
    val linearTrackStroke: Stroke
        @Composable
        get() =
            Stroke(
                width = with(LocalDensity.current) { LinearTrackThickness.toPx() },
                cap = StrokeCap.Round,
            )

    /** A default circular progress indicator active indicator [Stroke]. */
    val circularIndicatorStroke: Stroke
        @Composable
        get() =
            Stroke(
                width = with(LocalDensity.current) { CircularActiveThickness.toPx() },
                cap = StrokeCap.Round,
            )

    /** A default circular progress indicator track [Stroke]. */
    val circularTrackStroke: Stroke
        @Composable
        get() =
            Stroke(
                width = with(LocalDensity.current) { CircularTrackThickness.toPx() },
                cap = StrokeCap.Round,
            )

    /** A default circular progress indicator container size. */
    val CircularContainerSize: Dp = CircularWaveSize

    /** A default wavelength of a circular progress indicator when it's in a wavy form. */
    val CircularWavelength: Dp = CircularActiveWaveWavelength

    /**
     * A default gap size that appears in between the active indicator and the track at the circular
     * progress indicator.
     */
    val CircularIndicatorTrackGapSize: Dp = CircularTrackActiveSpace

    /** A default wavelength of a determinate linear progress indicator when it's in a wavy form. */
    val LinearDeterminateWavelength: Dp = LinearActiveWaveWavelength

    /** A default wavelength of a linear progress indicator when it's in a wavy form. */
    val LinearIndeterminateWavelength: Dp = LinearIndeterminateActiveWaveWavelength

    /** A default linear progress indicator container height. */
    val LinearContainerHeight: Dp = LinearWaveHeight

    /** A default linear progress indicator container width. */
    val LinearContainerWidth: Dp = 240.dp

    /** A default linear stop indicator size. */
    val LinearTrackStopIndicatorSize: Dp = LinearStopSize

    /**
     * A default gap size that appears in between the active indicator and the track at the linear
     * progress indicator.
     */
    val LinearIndicatorTrackGapSize: Dp = LinearTrackActiveSpace

    /** A function that returns a determinate indicator's amplitude for a given progress. */
    val indicatorAmplitude: (progress: Float) -> Float = { progress ->
        // Sets the amplitude to the max on 10%, and back to zero on 95% of the progress.
        if (progress <= 0.1f || progress >= 0.95f) {
            0f
        } else {
            1f
        }
    }
}

// LinearProgressIndicatorTokens (v0_7_0), copied as constants
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/LinearProgressIndicatorTokens.kt
private val LinearActiveThickness = 4.0.dp
private val LinearActiveWaveWavelength = 40.0.dp
private val LinearIndeterminateActiveWaveWavelength = 20.0.dp
private val LinearStopSize = 4.0.dp
private val LinearTrackActiveSpace = 4.0.dp
private val LinearTrackThickness = 4.0.dp
private val LinearWaveHeight = 10.0.dp

// CircularProgressIndicatorTokens, copied as constants
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/CircularProgressIndicatorTokens.kt
private val CircularActiveThickness = 4.0.dp
private val CircularActiveWaveWavelength = 15.0.dp
private val CircularTrackActiveSpace = 4.0.dp
private val CircularTrackThickness = 4.0.dp
private val CircularWaveSize = 48.0.dp

// MotionTokens, copied as constants
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/MotionTokens.kt
private const val MotionDurationMedium2 = 300
private const val MotionDurationLong2 = 500
private const val MotionDurationLong4 = 600
private val MotionEasingEmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val MotionEasingLinear = CubicBezierEasing(0.0f, 0.0f, 1.0f, 1.0f)
private val MotionEasingStandard = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
private val MotionEasingEmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

// Animation spec for increasing the amplitude drawing when its changing.
// Source: IncreasingAmplitudeAnimationSpec, WavyProgressIndicator.kt
private val IncreasingAmplitudeAnimationSpec: AnimationSpec<Float> =
    tween(durationMillis = MotionDurationLong2, easing = MotionEasingStandard)

// Animation spec for decreasing the amplitude drawing when its changing.
// Source: DecreasingAmplitudeAnimationSpec, WavyProgressIndicator.kt
private val DecreasingAmplitudeAnimationSpec: AnimationSpec<Float> =
    tween(durationMillis = MotionDurationLong2, easing = MotionEasingEmphasizedAccelerate)

/**
 * Advances this clock by one unit per millisecond, calling [onFrame] on every frame, until
 * [isIndeterminate] returns false. The clock follows [MotionDurationScale]. Its value modulo
 * [cycleDurationMillis] is the play time in the indeterminate cycle.
 */
// Source: animateIndeterminateCycle, WavyProgressIndicator.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/WavyProgressIndicator.kt
private suspend fun AnimationState<Float, AnimationVector1D>.animateIndeterminateCycle(
    cycleDurationMillis: Int,
    isIndeterminate: () -> Boolean,
    onFrame: AnimationScope<Float, AnimationVector1D>.() -> Unit,
) {
    var sequentialAnimation = true
    while (isIndeterminate()) {
        var stoppedByPolicy = false
        try {
            animateTo(
                targetValue = value + cycleDurationMillis,
                animationSpec =
                    infiniteRepeatable(tween(cycleDurationMillis, easing = LinearEasing)),
                sequentialAnimation = sequentialAnimation,
            ) {
                onFrame()
                if (!isIndeterminate()) cancelAnimation()
            }
        } catch (e: CancellationException) {
            // An InfiniteAnimationPolicy, such as the one in tests, stops infinite animations
            // without cancelling the coroutine.
            currentCoroutineContext().ensureActive()
            stoppedByPolicy = true
        }
        // An infinite animation also ends when animations are disabled.
        val motionDurationScale = currentCoroutineContext()[MotionDurationScale]
        snapshotFlow {
            !isIndeterminate() ||
                (!stoppedByPolicy && (motionDurationScale?.scaleFactor ?: 1f) > 0f)
        }
            .first { it }
        sequentialAnimation = false
    }
}

// Indeterminate linear indicator transition specs
// Source: ProgressIndicator.kt (LinearAnimationDuration and the delays and durations of both lines)
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/ProgressIndicator.kt

// Total duration for one linear cycle
private const val LinearAnimationDuration = 1750

// Duration of the head and tail animations for both lines
private const val FirstLineHeadDuration = 1000
private const val FirstLineTailDuration = 1000
private const val SecondLineHeadDuration = 850
private const val SecondLineTailDuration = 850

// Delay before the start of the head and tail animations for both lines
private const val FirstLineHeadDelay = 0
private const val FirstLineTailDelay = 250
private const val SecondLineHeadDelay = 650
private const val SecondLineTailDelay = 900

private val LinearIndeterminateProgressEasing = MotionEasingEmphasizedAccelerate

/** An animation spec for indeterminate linear progress indicator first line head position. */
private val linearIndeterminateFirstLineHeadAnimationSpec
    get() =
        infiniteRepeatable(
            animation =
                keyframes {
                    durationMillis = LinearAnimationDuration
                    0f at FirstLineHeadDelay using LinearIndeterminateProgressEasing
                    1f at FirstLineHeadDuration + FirstLineHeadDelay
                }
        )

/** An animation spec for indeterminate linear progress indicator first line tail position. */
private val linearIndeterminateFirstLineTailAnimationSpec
    get() =
        infiniteRepeatable(
            animation =
                keyframes {
                    durationMillis = LinearAnimationDuration
                    0f at FirstLineTailDelay using LinearIndeterminateProgressEasing
                    1f at FirstLineTailDuration + FirstLineTailDelay
                }
        )

/** An animation spec for indeterminate linear progress indicator second line head position. */
private val linearIndeterminateSecondLineHeadAnimationSpec
    get() =
        infiniteRepeatable(
            animation =
                keyframes {
                    durationMillis = LinearAnimationDuration
                    0f at SecondLineHeadDelay using LinearIndeterminateProgressEasing
                    1f at SecondLineHeadDuration + SecondLineHeadDelay
                }
        )

/** An animation spec for indeterminate linear progress indicator second line tail position. */
private val linearIndeterminateSecondLineTailAnimationSpec
    get() =
        infiniteRepeatable(
            animation =
                keyframes {
                    durationMillis = LinearAnimationDuration
                    0f at SecondLineTailDelay using LinearIndeterminateProgressEasing
                    1f at SecondLineTailDuration + SecondLineTailDelay
                }
        )

private val VerticalSemanticsBoundsPadding: Dp = 10.dp

/**
 * Increases the semantics bounds vertically by [VerticalSemanticsBoundsPadding] in order to meet
 * the TalkBack box minimum size while preserving the visual appearance.
 */
// Source: IncreaseVerticalSemanticsBounds, material3 internal/AccessibilityUtil.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/AccessibilityUtil.kt
private val IncreaseVerticalSemanticsBounds: Modifier =
    Modifier.layout { measurable, constraints ->
            val paddingPx = VerticalSemanticsBoundsPadding.roundToPx()
            // We need to add vertical padding to the semantics bounds in order to meet
            // screenreader green box minimum size, but we also want to
            // preserve a visual appearance and layout size below that minimum
            // in order to maintain backwards compatibility. This custom
            // layout effectively implements "negative padding".
            val newConstraint = constraints.offset(0, paddingPx * 2)
            val placeable = measurable.measure(newConstraint)

            // But when actually placing the placeable, create the layout without additional
            // space. Place the placeable where it would've been without any extra padding.
            val height = placeable.height - paddingPx * 2
            val width = placeable.width
            layout(width, height) { placeable.place(0, -paddingPx) }
        }
        .semantics(mergeDescendants = true) {}
        .padding(vertical = VerticalSemanticsBoundsPadding)

/**
 * A [Modifier] that draws a wavy linear progress indicator.
 *
 * @param progress a lambda that returns the progress of the indicator (e.g. 0 to 1), or a
 *   non-finite value for an indeterminate indicator.
 * @param color the color of the indicator.
 * @param trackColor the color of the track.
 * @param stroke the stroke of the indicator.
 * @param trackStroke the stroke of the track.
 * @param gapSize the gap size between the indicator and the track.
 * @param stopSize the size of the stop indicator.
 * @param amplitude a lambda that returns the amplitude of the indicator as a function of the
 *   progress. The value here represents a fraction of the height of the indicator (e.g. 0 to 1).
 * @param wavelength the wavelength of the determinate indicator.
 * @param waveSpeed the speed of the wave of the determinate indicator.
 * @param indeterminateWavelength the wavelength of the indeterminate indicator.
 * @param indeterminateWaveSpeed the speed of the wave of the indeterminate indicator.
 */
// Source: Modifier.linearWavyProgressIndicator, internal/LinearWavyProgressModifiers.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/LinearWavyProgressModifiers.kt
private fun Modifier.linearWavyProgressIndicator(
    progress: () -> Float,
    color: Color,
    trackColor: Color,
    stroke: Stroke,
    trackStroke: Stroke,
    gapSize: Dp,
    stopSize: Dp,
    amplitude: (progress: Float) -> Float,
    wavelength: Dp,
    waveSpeed: Dp,
    indeterminateWavelength: Dp = WavyProgressDefaults.LinearIndeterminateWavelength,
    indeterminateWaveSpeed: Dp = indeterminateWavelength,
): Modifier =
    this.then(
        LinearWavyProgressElement(
            progress = progress,
            amplitude = amplitude,
            color = color,
            trackColor = trackColor,
            stroke = stroke,
            trackStroke = trackStroke,
            gapSize = gapSize,
            stopSize = stopSize,
            wavelength = wavelength,
            waveSpeed = waveSpeed,
            indeterminateWavelength = indeterminateWavelength,
            indeterminateWaveSpeed = indeterminateWaveSpeed,
        )
    )

// Source: LinearWavyProgressElement, internal/LinearWavyProgressModifiers.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/LinearWavyProgressModifiers.kt
@Suppress("ModifierNodeInspectableProperties")
private class LinearWavyProgressElement(
    val progress: () -> Float,
    val amplitude: (progress: Float) -> Float,
    val color: Color,
    val trackColor: Color,
    val stroke: Stroke,
    val trackStroke: Stroke,
    val gapSize: Dp,
    val stopSize: Dp,
    val wavelength: Dp,
    val waveSpeed: Dp,
    val indeterminateWavelength: Dp,
    val indeterminateWaveSpeed: Dp,
) : ModifierNodeElement<LinearWavyProgressNode>() {

    override fun create(): LinearWavyProgressNode =
        LinearWavyProgressNode(
            progress = progress,
            amplitude = amplitude,
            colorParameter = color,
            trackColorParameter = trackColor,
            strokeParameter = stroke,
            trackStrokeParameter = trackStroke,
            gapSizeParameter = gapSize,
            stopSizeParameter = stopSize,
            wavelengthParameter = wavelength,
            waveSpeedParameter = waveSpeed,
            indeterminateWavelengthParameter = indeterminateWavelength,
            indeterminateWaveSpeedParameter = indeterminateWaveSpeed,
        )

    override fun update(node: LinearWavyProgressNode) {
        node.color = color
        node.trackColor = trackColor
        node.stroke = stroke
        node.trackStroke = trackStroke
        node.gapSize = gapSize
        node.stopSize = stopSize
        node.wavelength = wavelength
        node.waveSpeed = waveSpeed
        node.indeterminateWavelength = indeterminateWavelength
        node.indeterminateWaveSpeed = indeterminateWaveSpeed
        if (node.progress !== progress || node.amplitude !== amplitude) {
            node.progress = progress
            node.amplitude = amplitude
            node.invalidateDrawCache()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "linearWavyProgressIndicator"
        properties["color"] = color
        properties["trackColor"] = trackColor
        properties["stroke"] = stroke
        properties["trackStroke"] = trackStroke
        properties["gapSize"] = gapSize
        properties["stopSize"] = stopSize
        properties["wavelength"] = wavelength
        properties["waveSpeed"] = waveSpeed
        properties["indeterminateWavelength"] = indeterminateWavelength
        properties["indeterminateWaveSpeed"] = indeterminateWaveSpeed
        // Excluding the amplitude and progress lambdas
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LinearWavyProgressElement) return false

        if (progress !== other.progress) return false
        if (amplitude !== other.amplitude) return false
        if (color != other.color) return false
        if (trackColor != other.trackColor) return false
        if (stroke != other.stroke) return false
        if (trackStroke != other.trackStroke) return false
        if (gapSize != other.gapSize) return false
        if (stopSize != other.stopSize) return false
        if (wavelength != other.wavelength) return false
        if (waveSpeed != other.waveSpeed) return false
        if (indeterminateWavelength != other.indeterminateWavelength) return false
        if (indeterminateWaveSpeed != other.indeterminateWaveSpeed) return false

        return true
    }

    override fun hashCode(): Int {
        var result = progress.hashCode()
        result = 31 * result + amplitude.hashCode()
        result = 31 * result + color.hashCode()
        result = 31 * result + trackColor.hashCode()
        result = 31 * result + stroke.hashCode()
        result = 31 * result + trackStroke.hashCode()
        result = 31 * result + gapSize.hashCode()
        result = 31 * result + stopSize.hashCode()
        result = 31 * result + wavelength.hashCode()
        result = 31 * result + waveSpeed.hashCode()
        result = 31 * result + indeterminateWavelength.hashCode()
        result = 31 * result + indeterminateWaveSpeed.hashCode()
        return result
    }
}

// Source: LinearWavyProgressNode, internal/LinearWavyProgressModifiers.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/LinearWavyProgressModifiers.kt
private class LinearWavyProgressNode(
    var progress: () -> Float,
    var amplitude: (progress: Float) -> Float,
    colorParameter: Color,
    trackColorParameter: Color,
    strokeParameter: Stroke,
    trackStrokeParameter: Stroke,
    gapSizeParameter: Dp,
    stopSizeParameter: Dp,
    wavelengthParameter: Dp,
    waveSpeedParameter: Dp,
    indeterminateWavelengthParameter: Dp,
    indeterminateWaveSpeedParameter: Dp,
) : DelegatingNode() {

    var color: Color = colorParameter
        set(value) {
            if (field != value) {
                field = value
                cacheDrawNode.invalidateDraw()
            }
        }

    var trackColor: Color = trackColorParameter
        set(value) {
            if (field != value) {
                field = value
                cacheDrawNode.invalidateDraw()
            }
        }

    var stroke: Stroke = strokeParameter
        set(value) {
            if (field != value) {
                field = value
                invalidateDrawCache()
            }
        }

    var trackStroke: Stroke = trackStrokeParameter
        set(value) {
            if (field != value) {
                field = value
                invalidateDrawCache()
            }
        }

    var gapSize: Dp = gapSizeParameter
        set(value) {
            if (field != value) {
                field = value
                invalidateDrawCache()
            }
        }

    var stopSize: Dp = stopSizeParameter
        set(value) {
            if (field != value) {
                field = value
                cacheDrawNode.invalidateDraw()
            }
        }

    var wavelength: Dp = wavelengthParameter
        set(value) {
            if (field != value) {
                field = value
                if (!indeterminate) updateOffsetAnimation() // Restart animation
                invalidateDrawCache()
            }
        }

    var waveSpeed: Dp = waveSpeedParameter
        set(value) {
            if (field != value) {
                field = value
                if (!indeterminate) updateOffsetAnimation() // Restart animation
                invalidateDrawCache()
            }
        }

    var indeterminateWavelength: Dp = indeterminateWavelengthParameter
        set(value) {
            if (field != value) {
                field = value
                if (indeterminate) updateOffsetAnimation() // Restart animation
                invalidateDrawCache()
            }
        }

    var indeterminateWaveSpeed: Dp = indeterminateWaveSpeedParameter
        set(value) {
            if (field != value) {
                field = value
                if (indeterminate) updateOffsetAnimation() // Restart animation
                invalidateDrawCache()
            }
        }

    private var indeterminate = false
    private var indeterminateJob: Job? = null
    private var cyclePlayTimeMillis = 0f
    private var drawSecondLine = true
    private var handoffFraction = 1f

    private val waveOffset = mutableFloatStateOf(0f)
    private var offsetAnimationJob: Job? = null

    private var amplitudeAnimatable: Animatable<Float, AnimationVector1D>? = null
    private var amplitudeAnimationJob: Job? = null
    private var currentTargetAmplitude = -1f

    private val progressDrawingCache = LinearProgressDrawingCache()
    private val determinateFractions = FloatArray(2)
    private val indeterminateFractions = FloatArray(4)
    private val firstLineHead =
        linearIndeterminateFirstLineHeadAnimationSpec.vectorize(Float.VectorConverter)
    private val firstLineTail =
        linearIndeterminateFirstLineTailAnimationSpec.vectorize(Float.VectorConverter)
    private val secondLineHead =
        linearIndeterminateSecondLineHeadAnimationSpec.vectorize(Float.VectorConverter)
    private val secondLineTail =
        linearIndeterminateSecondLineTailAnimationSpec.vectorize(Float.VectorConverter)

    private val activeWavelength: Dp
        get() = if (indeterminate) indeterminateWavelength else wavelength

    private val activeWaveSpeed: Dp
        get() = if (indeterminate) indeterminateWaveSpeed else waveSpeed

    override fun onAttach() {
        if (!progress().isFinite()) {
            startIndeterminateAnimation()
        } else {
            // Potentially start the wave offset animation.
            updateOffsetAnimation()
        }
        // The draw cache outlives a detach, so rebuild it to restart what onDetach() reset.
        invalidateDrawCache()
    }

    override fun onDetach() {
        amplitudeAnimatable = null
        currentTargetAmplitude = -1f
        indeterminateJob = null
        indeterminate = false
        handoffFraction = 1f
    }

    fun invalidateDrawCache() {
        cacheDrawNode.invalidateDrawCache()
    }

    /** Updates the wave offset animation, potentially starting it if it is not already running. */
    private fun updateOffsetAnimation() {
        offsetAnimationJob?.cancel()
        offsetAnimationJob = null

        if (!isAttached) return

        val currentWavelength = activeWavelength
        val currentWaveSpeed = activeWaveSpeed
        if (currentWaveSpeed > 0.dp && currentWavelength > 0.dp) {
            val durationMillis =
                ((currentWavelength / currentWaveSpeed) * 1000)
                    .fastRoundToInt()
                    .coerceAtLeast(MinAnimationDuration)
            offsetAnimationJob = coroutineScope.launch {
                // Start from current offset
                val startValue = waveOffset.floatValue
                val offsetAnimatable = Animatable(startValue)
                val endValue = startValue + 1f
                offsetAnimatable.updateBounds(startValue, endValue)
                offsetAnimatable.animateTo(
                    targetValue = endValue,
                    animationSpec =
                        infiniteRepeatable(
                            animation = tween(durationMillis, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                ) {
                    waveOffset.floatValue = value % 1f
                }
            }
        } else {
            // No speed or wavelength, so we ensure that the offset is snapped to 0
            waveOffset.floatValue = 0f
        }
    }

    /**
     * Updates an amplitude change animation, potentially starting it if it is not already running.
     */
    private fun updateAmplitudeAnimation(targetAmplitude: Float) {
        val currentAmplitudeAnimatable =
            amplitudeAnimatable
                ?: Animatable(initialValue = targetAmplitude).also {
                    amplitudeAnimatable = it
                    currentTargetAmplitude = targetAmplitude
                }

        if (isAttached && currentTargetAmplitude != targetAmplitude) {
            currentTargetAmplitude = targetAmplitude
            amplitudeAnimationJob?.cancel()
            amplitudeAnimationJob = coroutineScope.launch {
                currentAmplitudeAnimatable.animateTo(
                    targetValue = targetAmplitude,
                    animationSpec =
                        if (currentAmplitudeAnimatable.value < targetAmplitude) {
                            IncreasingAmplitudeAnimationSpec
                        } else {
                            DecreasingAmplitudeAnimationSpec
                        },
                )
            }
        }
    }

    private fun determinateProgress(): Float {
        val currentProgress = progress()
        return if (!currentProgress.isFinite()) 0f else currentProgress.fastCoerceIn(0f, 1f)
    }

    private fun targetAmplitude(): Float {
        val value = amplitude(if (indeterminate) Float.NaN else determinateProgress())
        return if (value.isNaN()) 1f else value.fastCoerceIn(0f, 1f)
    }

    private fun startIndeterminateAnimation() {
        indeterminate = true
        cyclePlayTimeMillis = 0f
        drawSecondLine = true
        handoffFraction = 1f
        updateOffsetAnimation()
        indeterminateJob?.cancel()
        indeterminateJob = coroutineScope.launch {
            val clock = AnimationState(0f)
            val onFrame: AnimationScope<Float, AnimationVector1D>.() -> Unit = {
                val playTimeMillis = value % LinearAnimationDuration
                if (playTimeMillis < cyclePlayTimeMillis) drawSecondLine = true
                cyclePlayTimeMillis = playTimeMillis
                cacheDrawNode.invalidateDraw()
            }
            do {
                clock.animateIndeterminateCycle(
                    cycleDurationMillis = LinearAnimationDuration,
                    isIndeterminate = { !progress().isFinite() },
                    onFrame = onFrame,
                )
                drawSecondLine = drawSecondLine && cyclePlayTimeMillis > SecondLineHeadDelay
                val exitDistanceMillis =
                    (if (drawSecondLine) LinearAnimationDuration else FirstLineExitMillis) -
                        cyclePlayTimeMillis
                if (exitDistanceMillis > 0f) {
                    clock.animateTo(
                        targetValue = clock.value + exitDistanceMillis,
                        animationSpec = handoffExitAnimationSpec(exitDistanceMillis),
                        sequentialAnimation = true,
                    ) {
                        onFrame()
                        if (!progress().isFinite()) cancelAnimation()
                    }
                }
            } while (!progress().isFinite())

            indeterminate = false
            cyclePlayTimeMillis = 0f
            handoffFraction = 0f
            val determinateAmplitude = targetAmplitude()
            currentTargetAmplitude = determinateAmplitude
            amplitudeAnimationJob?.cancel()
            amplitudeAnimatable?.snapTo(determinateAmplitude)
            updateOffsetAnimation()
            cacheDrawNode.invalidateDrawCache()
            Animatable(0f).animateTo(
                targetValue = 1f,
                animationSpec = WavyProgressDefaults.ProgressAnimationSpec,
            ) {
                handoffFraction = value
                cacheDrawNode.invalidateDraw()
            }
        }
    }

    private fun progressFractions(): FloatArray =
        if (indeterminate) {
            indeterminateFractions[0] = firstLineTail.valueAt(cyclePlayTimeMillis)
            indeterminateFractions[1] = firstLineHead.valueAt(cyclePlayTimeMillis)
            indeterminateFractions[2] =
                if (drawSecondLine) secondLineTail.valueAt(cyclePlayTimeMillis) else 0f
            indeterminateFractions[3] =
                if (drawSecondLine) secondLineHead.valueAt(cyclePlayTimeMillis) else 0f
            indeterminateFractions
        } else {
            determinateFractions[1] = determinateProgress() * handoffFraction
            determinateFractions
        }

    private val cacheDrawNode: CacheDrawModifierNode =
        delegate(
            CacheDrawModifierNode {
                val currentProgress = progress()
                if (!currentProgress.isFinite() && !indeterminate) {
                    startIndeterminateAnimation()
                }

                updateAmplitudeAnimation(targetAmplitude())

                onDrawWithContent {
                    // Read from the states here and update the LinearProgressDrawingCache inside
                    // the onDrawWithContent to avoid voiding the cache at this
                    // CacheDrawModifierNode.
                    val currentAmplitude = amplitudeAnimatable?.value ?: 0f
                    val fractions = progressFractions()
                    progressDrawingCache.updatePaths(
                        size = size,
                        wavelength = activeWavelength.toPx(),
                        progressFractions = fractions,
                        amplitude = currentAmplitude,
                        waveOffset = if (currentAmplitude > 0f) waveOffset.floatValue else 0f,
                        gapSize = gapSize.toPx(),
                        stroke = stroke,
                        trackStroke = trackStroke,
                    )

                    // Draw
                    with(progressDrawingCache) {
                        rotate(if (layoutDirection == LayoutDirection.Ltr) 0f else 180f) {
                            // Draw track
                            drawPath(
                                path = trackPathToDraw,
                                color = trackColor,
                                style = trackStroke,
                            )

                            // Draw the progress
                            val progressPaths = progressPathsToDraw
                            if (progressPaths != null) {
                                for (i in progressPaths.indices) {
                                    drawPath(path = progressPaths[i], color = color, style = stroke)
                                }
                            }

                            if (!indeterminate) {
                                drawStopIndicator(
                                    progressEnd = fractions[1],
                                    progressIndicatorSize = size,
                                    maxStopIndicatorSize = stopSize * handoffFraction,
                                    horizontalInsets = currentStrokeCapWidth,
                                    trackStroke = trackStroke,
                                    color = color,
                                )
                            }
                        }
                    }
                }
            }
        )
}

private const val FirstLineExitMillis = FirstLineTailDelay + FirstLineTailDuration
private const val HandoffMaxPlaybackSpeed = 2.5f
private const val HandoffPlaybackRampMillis = 150f
private const val HandoffPlaybackAcceleration =
    (HandoffMaxPlaybackSpeed - 1f) / HandoffPlaybackRampMillis
private const val HandoffPlaybackRampDistanceMillis =
    (1f + HandoffMaxPlaybackSpeed) / 2f * HandoffPlaybackRampMillis

private val ZeroVector = AnimationVector1D(0f)
private val OneVector = AnimationVector1D(1f)

// Keyframes truncate the play time to whole milliseconds, so round away the float error first.
// Source: valueAt, internal/LinearWavyProgressModifiers.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/LinearWavyProgressModifiers.kt
private fun VectorizedAnimationSpec<AnimationVector1D>.valueAt(playTimeMillis: Float): Float =
    getValueFromNanos(
            playTimeMillis.fastRoundToInt() * 1_000_000L,
            ZeroVector,
            OneVector,
            ZeroVector,
        )
        .value

/**
 * Plays [distanceMillis] of the indeterminate cycle at a speed that ramps up linearly from 1x to
 * [HandoffMaxPlaybackSpeed] over [HandoffPlaybackRampMillis].
 */
// Source: handoffExitAnimationSpec, internal/LinearWavyProgressModifiers.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/LinearWavyProgressModifiers.kt
private fun handoffExitAnimationSpec(distanceMillis: Float): AnimationSpec<Float> {
    val durationMillis =
        if (distanceMillis <= HandoffPlaybackRampDistanceMillis) {
            (sqrt(1f + 2f * HandoffPlaybackAcceleration * distanceMillis) - 1f) /
                HandoffPlaybackAcceleration
        } else {
            HandoffPlaybackRampMillis +
                (distanceMillis - HandoffPlaybackRampDistanceMillis) / HandoffMaxPlaybackSpeed
        }
    return tween(
        durationMillis = durationMillis.fastRoundToInt().coerceAtLeast(1),
        easing = { fraction ->
            handoffExitDistanceMillis(fraction * durationMillis) / distanceMillis
        },
    )
}

/**
 * The distance in the indeterminate cycle that [handoffExitAnimationSpec] covers in [timeMillis].
 */
// Source: handoffExitDistanceMillis, internal/LinearWavyProgressModifiers.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/LinearWavyProgressModifiers.kt
private fun handoffExitDistanceMillis(timeMillis: Float): Float =
    if (timeMillis <= HandoffPlaybackRampMillis) {
        timeMillis + HandoffPlaybackAcceleration / 2f * timeMillis * timeMillis
    } else {
        HandoffPlaybackRampDistanceMillis +
            HandoffMaxPlaybackSpeed * (timeMillis - HandoffPlaybackRampMillis)
    }

/**
 * Draws an indicator at the end of the track. The size of the dot will remain constant until the
 * progress bar nears the end of the track and then it shrinks until its gone.
 */
// Source: drawStopIndicator, internal/LinearWavyProgressModifiers.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/LinearWavyProgressModifiers.kt
private fun DrawScope.drawStopIndicator(
    progressEnd: Float,
    progressIndicatorSize: Size,
    maxStopIndicatorSize: Dp,
    horizontalInsets: Float,
    trackStroke: Stroke,
    color: Color,
) {
    var stopIndicatorSize = min(trackStroke.width, maxStopIndicatorSize.toPx())
    // This will add an additional offset to the indicator's position in case the height of the
    // progress bar is greater than the height of the indicator.
    val indicatorXOffset =
        if (stopIndicatorSize == trackStroke.width) {
            0f
        } else {
            trackStroke.width / 4f
        }

    var indicatorX = progressIndicatorSize.width - stopIndicatorSize - indicatorXOffset
    val progressX = progressIndicatorSize.width * progressEnd + horizontalInsets
    if (indicatorX <= progressX) {
        stopIndicatorSize = max(0f, stopIndicatorSize - (progressX - indicatorX))
        indicatorX = progressX
    }

    if (stopIndicatorSize > 0) {
        if (trackStroke.cap == StrokeCap.Round) {
            drawCircle(
                color = color,
                radius = stopIndicatorSize / 2f,
                center =
                    Offset(
                        x = indicatorX + stopIndicatorSize / 2f,
                        y = progressIndicatorSize.height / 2f,
                    ),
            )
        } else {
            drawRect(
                color = color,
                topLeft =
                    Offset(
                        x = indicatorX,
                        y = (progressIndicatorSize.height - stopIndicatorSize) / 2f,
                    ),
                size = Size(width = stopIndicatorSize, height = stopIndicatorSize),
            )
        }
    }
}

/**
 * A drawing cache of [Path]s and [PathMeasure] to be used when drawing linear progress indicators.
 */
// Source: LinearProgressDrawingCache, internal/LinearWavyProgressModifiers.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/LinearWavyProgressModifiers.kt
private class LinearProgressDrawingCache {
    private var currentWavelength = -1f
    private var currentAmplitude = -1f
    private var currentSize: Size = Size.Unspecified
    private var currentProgressFractions: FloatArray? = null
    private var currentIndicatorTrackGapSize = 0f
    private var currentWaveOffset = -1f
    private var currentStroke = Stroke()
    private var currentTrackStroke = currentStroke

    // This scale value is used to grab segments from the pathMeasure in the correct length.
    // It holds a value that is the result of dividing the PathMeasure length by the actual Path
    // width (in pixels) that it's holding. When the amplitude is zero and the line is flat, the
    // scale would be 1.0. However, when the amplitude is greater than zero, the path is wavy and
    // its measured length in the PathMeasure would be longer than its measured width on screen, so
    // the scale would be greater than 1.0.
    private var progressPathScale = 1f

    /**
     * A [Path] that represents the progress indicator when it's in a complete state. A drawing path
     * can be computed from it and cached in this class with the use of [pathMeasure].
     */
    val fullProgressPath: Path = Path()

    /** A [PathMeasure] that will be used when computing a segment of a progress to be drawn */
    val pathMeasure: PathMeasure = PathMeasure()

    /** A [Path] that represents the track and will be used to draw it */
    val trackPathToDraw: Path = Path()

    /**
     * a [Path] that represents the current progress and will be used to draw it. This path is
     * derived from the [fullProgressPath] and should be computed and cached here using the
     * [pathMeasure].
     */
    var progressPathsToDraw: Array<Path>? = null

    /** The current stroke Cap width. */
    var currentStrokeCapWidth = 0f

    /**
     * Creates or updates the progress path, and caches it to avoid redundant updates before
     * updating the draw paths according to the progress.
     *
     * @param size the dimensions of the current drawing environment that this path is updated for
     * @param wavelength the length of a wave in pixels. This wavelength will only be applied when
     *   the [amplitude] is greater than zero in order to draw a wavy progress indicator.
     * @param progressFractions an array that holds the progress information for one or more
     *   progress segments that should be rendered on the indicator. Each value is the array
     *   represents a fractional progress location between 0.0 to 1.0, and a pair of values
     *   represent the start and end of a progress segment.
     * @param amplitude the amplitude of a wave in pixels. 0.0 represents no amplitude, and 1.0
     *   represents an amplitude that will take the full height of the progress indicator
     * @param waveOffset the offset that the progress bar will be created at
     * @param gapSize the gap size in pixels between the progress indicator and the track. Note that
     *   the gap will be taken out of the track path and not the progress path.
     * @param stroke the [Stroke] that will be used to plot progress's path
     * @param trackStroke the [Stroke] that will be used to plot the track's path
     */
    fun updatePaths(
        size: Size,
        @FloatRange(from = 0.0) wavelength: Float = 0f,
        progressFractions: FloatArray,
        @FloatRange(from = 0.0, to = 1.0) amplitude: Float,
        @FloatRange(from = 0.0, to = 1.0) waveOffset: Float,
        @FloatRange(from = 0.0) gapSize: Float,
        stroke: Stroke,
        trackStroke: Stroke,
    ) {
        val sizeChanged = currentProgressFractions?.size != progressFractions.size
        if (sizeChanged) {
            // Create a FloatArray to match the size of the given progressFractions array.
            // We will later call copyInto to copy the incoming values into this cache.
            currentProgressFractions = FloatArray(progressFractions.size)
            progressPathsToDraw = Array(progressFractions.size / 2) { Path() }
        }
        val pathsUpdates =
            updateFullPaths(size, wavelength, amplitude, gapSize, stroke, trackStroke)
        updateDrawPaths(
            forceUpdate = pathsUpdates || sizeChanged,
            progressFractions = progressFractions,
            amplitude = amplitude,
            waveOffset = waveOffset,
        )
    }

    /**
     * Creates or updates the progress path, and caches it to avoid redundant updates. The created
     * path represents the progress indicator when it's in a complete state.
     *
     * Call this function before calling [updateDrawPaths], which will cut segments of the full path
     * for drawing using the internal [pathMeasure] that this function updates.
     *
     * @return true if the full paths were updated, or false otherwise
     */
    private fun updateFullPaths(
        size: Size,
        @FloatRange(from = 0.0) wavelength: Float,
        @FloatRange(from = 0.0, to = 1.0) amplitude: Float,
        @FloatRange(from = 0.0) gapSize: Float,
        stroke: Stroke,
        trackStroke: Stroke,
    ): Boolean {
        if (
            currentSize == size &&
                currentWavelength == wavelength &&
                currentStroke == stroke &&
                currentTrackStroke == trackStroke &&
                currentIndicatorTrackGapSize == gapSize &&
                // Check if an amplitude change should trigger a full path update. If the amplitude
                // is turning to or from zero, this will trigger an update.
                ((currentAmplitude != 0f && amplitude != 0f) ||
                    currentAmplitude == 0f && amplitude == 0f)
        ) {
            // No update required
            return false
        }

        val height = size.height
        val width = size.width

        // Update the Stroke cap width to take into consideration when drawing the Path.
        currentStrokeCapWidth =
            if (
                (stroke.cap == StrokeCap.Butt && trackStroke.cap == StrokeCap.Butt) ||
                    height > width
            ) {
                0f
            } else {
                max(stroke.width / 2, trackStroke.width / 2)
            }

        // There are changes that should update the full path.
        fullProgressPath.rewind()
        fullProgressPath.moveTo(0f, 0f)

        if (amplitude == 0f) {
            // Just a line in this case, so we can optimize with a simple lineTo call.
            fullProgressPath.lineTo(width, 0f)
        } else {
            val halfWavelengthPx = wavelength / 2f
            var anchorX = halfWavelengthPx
            val anchorY = 0f
            var controlX = halfWavelengthPx / 2f

            // We set the amplitude to the max available height to create a sine-like path that will
            // later be Y-scaled on draw.
            // Note that with quadratic plotting, the height of the control point, when
            // perpendicular to the center point between the anchors, will plot a wave that peaks at
            // half the height.
            // We offset this height with the progress stroke's width to avoid cropping the drawing
            // later.
            var controlY = height - stroke.width

            // Plot a path that holds a couple of extra waves. This can later be used to create a
            // progressPathToDraw with a wave offset value to simulate a wave movement.
            // Note that we add more than one wave-length to support cases where the wavelength is
            // relatively large and may end up in cases where a single extra wavelength is not
            // sufficient for the wave's motion drawing.
            val widthWithExtraPhase = width + wavelength * 2
            var wavesCount = 0
            while (anchorX <= widthWithExtraPhase) {
                fullProgressPath.quadraticTo(controlX, controlY, anchorX, anchorY)
                anchorX += halfWavelengthPx
                controlX += halfWavelengthPx
                controlY *= -1f
                wavesCount++
            }
        }

        fullProgressPath.translate(Offset(x = 0f, y = height / 2f))

        // Update the PathMeasure with the full path
        pathMeasure.setPath(path = fullProgressPath, forceClosed = false)

        // Calculate the progressPathScale by dividing the length of the path that the PathMeasure
        // holds by its actual width in pixels. We will use this scale value later when grabbing
        // segments from the pathMeasure.
        val fullPathLength = pathMeasure.length
        progressPathScale = fullPathLength / (fullProgressPath.getBounds().width + 0.00000001f)

        // Cache the full path attributes (note that the amplitude is intentionally not cached here,
        // and will be cached on the updateDrawPaths call)
        currentSize = size
        currentWavelength = wavelength
        currentStroke = stroke
        currentTrackStroke = trackStroke
        currentIndicatorTrackGapSize = gapSize
        return true
    }

    /**
     * Updates and caches the draw paths by to the progress, amplitude, and wave offset.
     *
     * It's important to call this function only _after_ a call for [updateFullPaths] was made.
     *
     * @param forceUpdate for an update to the drawing paths. This flag will be set to true when the
     *   [updateFullPaths] returns true to indicate that the base paths were updated.
     */
    private fun updateDrawPaths(
        forceUpdate: Boolean,
        progressFractions: FloatArray,
        @FloatRange(from = 0.0, to = 1.0) amplitude: Float,
        @FloatRange(from = 0.0, to = 1.0) waveOffset: Float,
    ) {
        require(currentSize != Size.Unspecified) {
            "updateDrawPaths was called before updateFullPaths"
        }
        require(progressPathsToDraw!!.size == progressFractions.size / 2) {
            "the given progress fraction pairs do not match the expected number of progress paths" +
                " to draw. updateDrawPaths called with ${progressFractions.size / 2} pairs, while" +
                " there are ${progressPathsToDraw!!.size} expected progress paths."
        }
        if (
            !forceUpdate &&
                currentProgressFractions.contentEquals(progressFractions) &&
                currentAmplitude == amplitude &&
                currentWaveOffset == waveOffset
        ) {
            // No update required
            return
        }
        val width = currentSize.width
        val halfHeight = currentSize.height / 2f

        var adjustedTrackGapSize = currentIndicatorTrackGapSize

        // The path will only be visible if the Cap can be drawn, so this flag will indicate when
        // that happens to help us adjust the gap between the active indicator and the track.
        var activeIndicatorVisible = false

        // For each of the progress paths, apply a segment from the PathMeasure that was previously
        // created for the entire width of the progress bar. Also, draw the track parts in the gaps
        // between the progress parts.
        var nextEndTrackOffset = width - currentStrokeCapWidth
        trackPathToDraw.rewind()
        trackPathToDraw.moveTo(x = nextEndTrackOffset, y = halfHeight)
        for (i in progressPathsToDraw!!.indices) {
            progressPathsToDraw!![i].rewind()

            val startProgressFraction = progressFractions[i * 2]
            val endProgressFraction = progressFractions[i * 2 + 1]

            val barTail = startProgressFraction * width
            val barHead = endProgressFraction * width

            if (i == 0) {
                // Potentially shorten the gap and insets when the progress bar just enters the
                // track.
                // When rounded caps are applied, we need enough space to draw the initial path
                // (i.e. circle), so by only adjusting the gap size when the
                // barHead >= currentStrokeCapWidth we ensure that the track is not being shortened
                // in this initial progress phase.
                adjustedTrackGapSize =
                    if (barHead < currentStrokeCapWidth) {
                        0f // barHead
                    } else {
                        min(
                            barHead - currentStrokeCapWidth,
                            currentIndicatorTrackGapSize, /*+ currentStrokeCapWidth * 2*/
                        )
                    }
                activeIndicatorVisible = barHead >= currentStrokeCapWidth
            }
            // Coerce the bar's head and tail to ensure we leave room for the drawing of the
            // stroke's caps.
            val adjustedBarHead =
                barHead.fastCoerceIn(currentStrokeCapWidth, width - currentStrokeCapWidth)
            val adjustedBarTail =
                barTail.fastCoerceIn(currentStrokeCapWidth, width - currentStrokeCapWidth)

            // Update the progressPathToDraw
            if (abs(endProgressFraction - startProgressFraction) > 0) {
                // For flat lines (i.e. amplitude == 0), there is no need to offset the wave.
                val waveShift =
                    if (amplitude != 0f) {
                        waveOffset * currentWavelength
                    } else {
                        0f
                    }
                pathMeasure.getSegment(
                    startDistance = (adjustedBarTail + waveShift) * progressPathScale,
                    stopDistance = (adjustedBarHead + waveShift) * progressPathScale,
                    destination = progressPathsToDraw!![i],
                )

                // Translate and scale the draw path by the wave shift and the amplitude.
                progressPathsToDraw!![i].transform(
                    Matrix().apply {
                        translate(
                            x = if (waveShift > 0f) -waveShift else 0f,
                            y = (1f - amplitude) * halfHeight,
                        )
                        // The progressPathToDraw is a segment of the full progress path, which is
                        // always in the maximum possible amplitude. This scaling will flatten the
                        // wave to the given amplitude percentage.
                        if (amplitude != 1f) {
                            scale(y = amplitude)
                        }
                    }
                )
            }

            // While we draw the progress parts from left to right, we also draw the track parts
            // from right to left and update the nextEndTrackOffset on every pass.
            // Before that, we calculate the spacing between the active indicator and the track to
            // adjust it if needed when the progress is small.
            val adaptiveTrackSpacing =
                if (activeIndicatorVisible) {
                    adjustedTrackGapSize + currentStrokeCapWidth * 2
                } else {
                    adjustedTrackGapSize
                }
            if (nextEndTrackOffset > adjustedBarHead + adaptiveTrackSpacing) {
                trackPathToDraw.lineTo(
                    x = max(currentStrokeCapWidth, adjustedBarHead + adaptiveTrackSpacing),
                    y = halfHeight,
                )
            }

            if (barHead > barTail) {
                // Update the nextEndTrackOffset and move the path to prepare for the next draw.
                nextEndTrackOffset =
                    max(currentStrokeCapWidth, adjustedBarTail - adaptiveTrackSpacing)
                trackPathToDraw.moveTo(x = nextEndTrackOffset, y = halfHeight)
            }
        }

        // Final track drawing, if needed. This will fill any track gaps to the left of the
        // progress.
        if (nextEndTrackOffset > currentStrokeCapWidth) {
            trackPathToDraw.lineTo(x = currentStrokeCapWidth, y = halfHeight)
        }

        // Cache
        progressFractions.copyInto(currentProgressFractions!!)
        currentAmplitude = amplitude
        currentWaveOffset = waveOffset
    }
}

// Minimum animation duration to ensure we don't overwhelm the CPU.
private const val MinAnimationDuration = 50

// Indeterminate circular indicator transition specs, played by the circular node while progress is not finite
// Source: ProgressIndicator.kt (circularIndeterminateRotationAnimationSpec, circularIndeterminateProgressAnimationSpec, constants)
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/ProgressIndicator.kt

// The indeterminate circular indicator easing constants for its motion
private val CircularProgressEasing = MotionEasingStandard
private const val CircularIndeterminateMinProgress = 0.1f
private const val CircularIndeterminateMaxProgress = 0.87f

private const val CircularAnimationProgressDuration = 6000
private const val CircularAnimationAdditionalRotationDelay = 1500
private const val CircularAnimationAdditionalRotationDuration = 300
private const val CircularAdditionalRotationDegreesTarget = 360f
private const val CircularGlobalRotationDegreesTarget = 1080f

/**
 * An animation spec for indeterminate circular progress indicators that infinitely rotates a 360
 * degrees.
 */
private val circularIndeterminateRotationAnimationSpec
    get() =
        infiniteRepeatable(
            animation =
                keyframes {
                    durationMillis = CircularAnimationProgressDuration // 6000ms
                    90f at
                        CircularAnimationAdditionalRotationDuration using
                        MotionEasingEmphasizedDecelerate // 300ms
                    90f at CircularAnimationAdditionalRotationDelay // hold till 1500ms
                    180f at
                        CircularAnimationAdditionalRotationDuration +
                            CircularAnimationAdditionalRotationDelay // 1800ms
                    180f at CircularAnimationAdditionalRotationDelay * 2 // hold till 3000ms
                    270f at
                        CircularAnimationAdditionalRotationDuration +
                            CircularAnimationAdditionalRotationDelay * 2 // 3300ms
                    270f at CircularAnimationAdditionalRotationDelay * 3 // hold till 4500ms
                    360f at
                        CircularAnimationAdditionalRotationDuration +
                            CircularAnimationAdditionalRotationDelay * 3 // 4800ms
                    360f at CircularAnimationProgressDuration // hold till 6000ms
                }
        )

/** An animation spec for indeterminate circular progress indicators progress motion. */
private val circularIndeterminateProgressAnimationSpec
    get() =
        infiniteRepeatable(
            animation =
                keyframes {
                    durationMillis = CircularAnimationProgressDuration // 6000ms
                    CircularIndeterminateMaxProgress at
                        CircularAnimationProgressDuration / 2 using
                        CircularProgressEasing // 3000ms
                    CircularIndeterminateMinProgress at CircularAnimationProgressDuration
                }
        )

/**
 * A [Modifier] that draws a wavy circular progress indicator.
 *
 * Note: Apply a semantics modifiers (e.g., `Modifier.semantics { ... }` or custom
 * `Modifier.progressSemantics()`) separately.
 *
 * @param progress Lambda returning the current progress (0.0 to 1.0), or a non-finite value for an
 *   indeterminate indicator.
 * @param color The color of the progress indicator wave.
 * @param trackColor The color of the track background.
 * @param stroke The [Stroke] style for the progress wave.
 * @param trackStroke The [Stroke] style for the track.
 * @param gapSize The gap between the track and the progress wave.
 * @param amplitude Lambda providing the wave amplitude (0.0 to 1.0) based on progress.
 * @param wavelength The preferred wavelength of the wave.
 * @param waveSpeed The speed of the wave's motion (Dp per second).
 */
// Source: Modifier.circularWavyProgressIndicator, internal/CircularWavyProgressModifiers.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/CircularWavyProgressModifiers.kt
private fun Modifier.circularWavyProgressIndicator(
    progress: () -> Float,
    color: Color,
    trackColor: Color,
    stroke: Stroke,
    trackStroke: Stroke,
    gapSize: Dp,
    amplitude: (progress: Float) -> Float,
    wavelength: Dp,
    waveSpeed: Dp,
): Modifier =
    this.then(
        CircularWavyProgressElement(
            progress = progress,
            color = color,
            trackColor = trackColor,
            stroke = stroke,
            trackStroke = trackStroke,
            gapSize = gapSize,
            amplitude = amplitude,
            wavelength = wavelength,
            waveSpeed = waveSpeed,
        )
    )

// Source: CircularWavyProgressElement, internal/CircularWavyProgressModifiers.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/CircularWavyProgressModifiers.kt
@Suppress("ModifierNodeInspectableProperties")
private class CircularWavyProgressElement(
    val progress: () -> Float,
    val color: Color,
    val trackColor: Color,
    val stroke: Stroke,
    val trackStroke: Stroke,
    val gapSize: Dp,
    val amplitude: (progress: Float) -> Float,
    val wavelength: Dp,
    val waveSpeed: Dp,
) : ModifierNodeElement<CircularWavyProgressNode>() {

    override fun create(): CircularWavyProgressNode =
        CircularWavyProgressNode(
            progress = progress,
            amplitude = amplitude,
            colorParameter = color,
            trackColorParameter = trackColor,
            strokeParameter = stroke,
            trackStrokeParameter = trackStroke,
            gapSizeParameter = gapSize,
            wavelengthParameter = wavelength,
            waveSpeedParameter = waveSpeed,
        )

    override fun update(node: CircularWavyProgressNode) {
        node.color = color
        node.trackColor = trackColor
        node.stroke = stroke
        node.trackStroke = trackStroke
        node.gapSize = gapSize
        node.wavelength = wavelength
        node.waveSpeed = waveSpeed
        if (node.progress !== progress || node.amplitude !== amplitude) {
            node.progress = progress
            node.amplitude = amplitude
            node.invalidateDrawCache()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "circularWavyProgressIndicator"
        properties["color"] = color
        properties["trackColor"] = trackColor
        properties["stroke"] = stroke
        properties["trackStroke"] = trackStroke
        properties["gapSize"] = gapSize
        properties["wavelength"] = wavelength
        properties["waveSpeed"] = waveSpeed
        // Excluding the amplitude and progress lambdas
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CircularWavyProgressElement) return false

        if (progress !== other.progress) return false
        if (amplitude !== other.amplitude) return false
        if (color != other.color) return false
        if (trackColor != other.trackColor) return false
        if (stroke != other.stroke) return false
        if (trackStroke != other.trackStroke) return false
        if (gapSize != other.gapSize) return false
        if (wavelength != other.wavelength) return false
        if (waveSpeed != other.waveSpeed) return false
        return true
    }

    override fun hashCode(): Int {
        var result = progress.hashCode()
        result = 31 * result + amplitude.hashCode()
        result = 31 * result + color.hashCode()
        result = 31 * result + trackColor.hashCode()
        result = 31 * result + stroke.hashCode()
        result = 31 * result + trackStroke.hashCode()
        result = 31 * result + gapSize.hashCode()
        result = 31 * result + wavelength.hashCode()
        result = 31 * result + waveSpeed.hashCode()
        return result
    }
}

// Source: CircularWavyProgressNode, internal/CircularWavyProgressModifiers.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/CircularWavyProgressModifiers.kt
private class CircularWavyProgressNode(
    var progress: () -> Float,
    var amplitude: (progress: Float) -> Float,
    colorParameter: Color,
    trackColorParameter: Color,
    strokeParameter: Stroke,
    trackStrokeParameter: Stroke,
    gapSizeParameter: Dp,
    wavelengthParameter: Dp,
    waveSpeedParameter: Dp,
) : DelegatingNode() {

    var color: Color = colorParameter
        set(value) {
            if (field != value) {
                field = value
                cacheDrawNode.invalidateDraw()
            }
        }

    var trackColor: Color = trackColorParameter
        set(value) {
            if (field != value) {
                field = value
                cacheDrawNode.invalidateDraw()
            }
        }

    var stroke: Stroke = strokeParameter
        set(value) {
            if (field != value) {
                field = value
                invalidateDrawCache()
            }
        }

    var trackStroke: Stroke = trackStrokeParameter
        set(value) {
            if (field != value) {
                field = value
                invalidateDrawCache()
            }
        }

    var gapSize: Dp = gapSizeParameter
        set(value) {
            if (field != value) {
                field = value
                invalidateDrawCache()
            }
        }

    var wavelength: Dp = wavelengthParameter
        set(value) {
            if (field != value) {
                field = value
                // Wavelength affects the offset animation
                startOffsetAnimation()
            }
        }

    var waveSpeed: Dp = waveSpeedParameter
        set(value) {
            if (field != value) {
                field = value
                // Speed affects the offset animation
                startOffsetAnimation()
            }
        }

    private var indeterminate = false
    private var indeterminateJob: Job? = null
    private var cyclePlayTimeMillis = 0f
    private var handoffStartSweep = 0f
    private var handoffStartRotation = 0f
    private var handoffFraction = 1f

    private val circularShapes = CircularShapes()
    private val progressDrawingCache = CircularProgressDrawingCache()
    private val additionalRotation =
        circularIndeterminateRotationAnimationSpec.vectorize(Float.VectorConverter)
    private val sweep = circularIndeterminateProgressAnimationSpec.vectorize(Float.VectorConverter)

    // Amplitude animation
    private val amplitudeState = mutableFloatStateOf(0f)
    private var amplitudeAnimatable: Animatable<Float, AnimationVector1D>? = null
    private var amplitudeAnimationJob: Job? = null
    private var currentTargetAmplitude = -1f

    // Offset animation
    private val waveOffsetState = mutableFloatStateOf(0f)
    private var offsetAnimatable: Animatable<Float, AnimationVector1D>? = null
    private var offsetAnimationJob: Job? = null

    // A vertex count used to calculate the speed of the wave offset animation. The animation will
    // only run when this number is greater than zero.
    private var vertexCountForCurrentAnimation = -1

    override fun onAttach() {
        if (!progress().isFinite()) {
            startIndeterminateAnimation()
        }
        // The draw cache outlives a detach, so rebuild it to restart what onDetach() reset. The
        // rebuild resolves the circularShapes.currentVertexCount and triggers the
        // startOffsetAnimation() to start the animation at the right speed.
        invalidateDrawCache()
    }

    override fun onDetach() {
        amplitudeAnimatable = null
        currentTargetAmplitude = -1f
        vertexCountForCurrentAnimation = -1
        indeterminateJob = null
        indeterminate = false
        handoffFraction = 1f
    }

    fun invalidateDrawCache() {
        cacheDrawNode.invalidateDrawCache()
    }

    /** A function that returns a track [Path] for a given progress. */
    private fun trackPathProvider(
        amplitude: Float,
        wavelength: Float,
        strokeWidth: Float,
        size: Size,
        path: Path,
    ): Path? = circularShapes.getTrackPath(path = path)

    /** A function that returns a progress [Path] for a given progress. */
    private fun progressPathProvider(
        amplitude: Float,
        wavelength: Float,
        strokeWidth: Float,
        size: Size,
        supportMotion: Boolean,
        path: Path,
    ): Path =
        circularShapes.getProgressPath(
            amplitude = amplitude,
            path = path,
            repeatPath = supportMotion,
        )

    /**
     * Starts the wave offset animation.
     *
     * The animation will only start if the amplitude is greater than zero and the
     * [vertexCountForCurrentAnimation], the [waveSpeed], and the [wavelength] are greater than
     * zero.
     */
    private fun startOffsetAnimation() {
        // Cancel previous job if running
        stopOffsetAnimation()

        // Don't animate if not attached or not drawing a wave (e.g. zero amplitude).
        if (
            !isAttached ||
                !coroutineScope.isActive ||
                (amplitudeState.floatValue == 0f && currentTargetAmplitude <= 0f)
        ) {
            return
        }

        // Start the animation only if we have a valid vertex count and positive waveSpeed and
        // wavelength.
        if (waveSpeed > 0.dp && wavelength > 0.dp && vertexCountForCurrentAnimation > 0) {
            // Use the current vertex count from shapes if needed for duration precision.
            val durationMillis =
                ((wavelength / waveSpeed) * 1000 * vertexCountForCurrentAnimation)
                    .fastRoundToInt()
                    .coerceAtLeast(MinAnimationDuration)

            // Re-create animatable to ensure it picks up the latest offset state
            val startOffset = waveOffsetState.floatValue
            offsetAnimatable = Animatable(startOffset)
            offsetAnimationJob = coroutineScope.launch {
                val anim = offsetAnimatable ?: return@launch // Safety check
                // Update bounds relative to the current offset
                anim.updateBounds(startOffset, startOffset + 1f)
                anim.animateTo(
                    targetValue = startOffset + 1f,
                    animationSpec =
                        infiniteRepeatable(
                            animation = tween(durationMillis, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                ) {
                    // Update the state on each frame
                    waveOffsetState.floatValue = value % 1f
                }
            }
        } else {
            // No speed or wavelength, snap offset to 0
            waveOffsetState.floatValue = 0f
        }
    }

    private fun stopOffsetAnimation() {
        offsetAnimationJob?.cancel()
        offsetAnimationJob = null
        offsetAnimatable = null
    }

    private fun updateAmplitudeAnimation(targetAmplitude: Float) {
        val currentAmplitudeAnimatable =
            amplitudeAnimatable
                ?: Animatable(initialValue = targetAmplitude).also {
                    amplitudeAnimatable = it
                    amplitudeState.floatValue = targetAmplitude
                    currentTargetAmplitude = targetAmplitude
                }

        if (!isAttached || currentTargetAmplitude == targetAmplitude) return
        currentTargetAmplitude = targetAmplitude
        amplitudeAnimationJob?.cancel()
        amplitudeAnimationJob = coroutineScope.launch {
            currentAmplitudeAnimatable.animateTo(
                targetValue = targetAmplitude,
                animationSpec =
                    if (currentAmplitudeAnimatable.value < targetAmplitude) {
                        IncreasingAmplitudeAnimationSpec
                    } else {
                        DecreasingAmplitudeAnimationSpec
                    },
            ) {
                amplitudeState.floatValue = value
            }

            // Check if we need to stop the animation if the target amplitude is 0
            // at the end of the animation.
            if (targetAmplitude == 0f) {
                stopOffsetAnimation()
            }
        }
    }

    private fun determinateProgress(): Float {
        val currentProgress = progress()
        return if (!currentProgress.isFinite()) 0f else currentProgress.fastCoerceIn(0f, 1f)
    }

    private fun targetAmplitude(): Float {
        val value = amplitude(if (indeterminate) Float.NaN else determinateProgress())
        return if (value.isNaN()) 1f else value.fastCoerceIn(0f, 1f)
    }

    private fun startIndeterminateAnimation() {
        indeterminate = true
        cyclePlayTimeMillis = 0f
        handoffFraction = 1f
        indeterminateJob?.cancel()
        indeterminateJob = coroutineScope.launch {
            AnimationState(0f).animateIndeterminateCycle(
                cycleDurationMillis = CircularAnimationProgressDuration,
                isIndeterminate = { !progress().isFinite() },
            ) {
                cyclePlayTimeMillis = value % CircularAnimationProgressDuration
                cacheDrawNode.invalidateDraw()
            }

            handoffStartSweep = indeterminateSweep()
            val currentRotation = indeterminateRotation() % 360f
            handoffStartRotation = if (currentRotation == 0f) 360f else currentRotation
            val remainingRotationFraction = (360f - handoffStartRotation) / 360f
            indeterminate = false
            cyclePlayTimeMillis = 0f
            handoffFraction = 0f
            cacheDrawNode.invalidateDrawCache()
            Animatable(0f).animateTo(
                targetValue = 1f,
                animationSpec =
                    tween(
                        durationMillis =
                            lerp(
                                    MinHandoffDurationMillis,
                                    MaxHandoffDurationMillis,
                                    remainingRotationFraction,
                                )
                                .fastRoundToInt(),
                        easing = MotionEasingEmphasizedDecelerate,
                    ),
            ) {
                handoffFraction = value
                cacheDrawNode.invalidateDraw()
            }
        }
    }

    private fun indeterminateSweep(): Float =
        sweep.valueAt(cyclePlayTimeMillis, MinProgressVector, MinProgressVector)

    private fun indeterminateRotation(): Float =
        CircularGlobalRotationDegreesTarget * cyclePlayTimeMillis /
            CircularAnimationProgressDuration +
            additionalRotation.valueAt(cyclePlayTimeMillis, ZeroVector, AdditionalRotationTarget) +
            CircularIndeterminateBaseRotation

    private val cacheDrawNode =
        delegate(
            CacheDrawModifierNode {
                val currentProgress = progress()
                if (!currentProgress.isFinite() && !indeterminate) {
                    startIndeterminateAnimation()
                }

                val currentGapPx = gapSize.toPx()
                val currentWavelengthPx = wavelength.toPx()
                val enableMotion = waveSpeed > 0.dp

                val targetAmplitude = targetAmplitude()
                updateAmplitudeAnimation(targetAmplitude)

                // Pass stroke width for potentially calculating polygon radius accurately. A Morph
                // is required whenever the amplitude is, or animates through, a value between 0
                // and 1.
                circularShapes.update(
                    size = size,
                    wavelength = currentWavelengthPx,
                    strokeWidth = stroke.width,
                    requiresMorph =
                        amplitudeAnimationJob != null ||
                            (targetAmplitude > 0f && targetAmplitude < 1f),
                )

                // Update the vertexCountForCurrentAnimation after the circularShapes.update().
                if (vertexCountForCurrentAnimation != circularShapes.currentVertexCount.intValue) {
                    vertexCountForCurrentAnimation =
                        circularShapes.currentVertexCount.intValue.coerceAtLeast(
                            MinCircularVertexCount
                        )
                }

                // Start the offset animation when the amplitude changes to a positive number. The
                // animation will end when the amplitudeAnimatable finishes and reached
                // zero amplitude again.
                if (
                    targetAmplitude > 0 &&
                        (offsetAnimationJob == null || offsetAnimationJob?.isCompleted == true)
                ) {
                    startOffsetAnimation()
                }

                onDrawWithContent {
                    // Read from the state here and update the CircularProgressDrawingCache inside
                    // the onDrawWithContent to avoid voiding the cache at this
                    // CacheDrawModifierNode.
                    val animatedAmplitudeValue = amplitudeState.floatValue
                    val endProgress: Float
                    val rotation: Float
                    if (indeterminate) {
                        endProgress = indeterminateSweep()
                        rotation = indeterminateRotation()
                    } else if (handoffFraction < 1f) {
                        endProgress =
                            lerp(handoffStartSweep, determinateProgress(), handoffFraction)
                        rotation = lerp(handoffStartRotation, 360f, handoffFraction) % 360f
                    } else {
                        endProgress = determinateProgress()
                        rotation = 0f
                    }

                    // Update the drawing cache
                    progressDrawingCache.updatePaths(
                        size = size,
                        progressPathProvider = ::progressPathProvider,
                        trackPathProvider = ::trackPathProvider,
                        enableProgressMotion = enableMotion,
                        startProgress = 0f,
                        endProgress = endProgress,
                        amplitude = animatedAmplitudeValue,
                        waveOffset =
                            if (animatedAmplitudeValue > 0f && enableMotion) {
                                waveOffsetState.floatValue
                            } else {
                                0f
                            },
                        wavelength = currentWavelengthPx,
                        gapSize = currentGapPx,
                        stroke = stroke,
                        trackStroke = trackStroke,
                    )

                    if (rotation == 0f) {
                        drawCircularIndicator(
                            color = color,
                            trackColor = trackColor,
                            stroke = stroke,
                            trackStroke = trackStroke,
                            drawingCache = progressDrawingCache,
                        )
                    } else {
                        withTransform(transformBlock = { rotate(degrees = rotation) }) {
                            drawCircularIndicator(
                                color = color,
                                trackColor = trackColor,
                                stroke = stroke,
                                trackStroke = trackStroke,
                                drawingCache = progressDrawingCache,
                            )
                        }
                    }
                }
            }
        )
}

private const val CircularIndeterminateBaseRotation = 90f
private val MinHandoffDurationMillis = MotionDurationMedium2.toFloat()
private val MaxHandoffDurationMillis = MotionDurationLong4.toFloat()

private val AdditionalRotationTarget = AnimationVector1D(CircularAdditionalRotationDegreesTarget)
private val MinProgressVector = AnimationVector1D(CircularIndeterminateMinProgress)

// Keyframes truncate the play time to whole milliseconds, so round away the float error first.
// Source: valueAt, internal/CircularWavyProgressModifiers.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/CircularWavyProgressModifiers.kt
private fun VectorizedAnimationSpec<AnimationVector1D>.valueAt(
    playTimeMillis: Float,
    initialValue: AnimationVector1D,
    targetValue: AnimationVector1D,
): Float =
    getValueFromNanos(
            playTimeMillis.fastRoundToInt() * 1_000_000L,
            initialValue,
            targetValue,
            ZeroVector,
        )
        .value

/** Draws the track and the progress of a circular progress indicator. */
// Source: drawCircularIndicator, internal/CircularWavyProgressModifiers.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/CircularWavyProgressModifiers.kt
private fun DrawScope.drawCircularIndicator(
    color: Color,
    trackColor: Color,
    stroke: Stroke,
    trackStroke: Stroke,
    drawingCache: CircularProgressDrawingCache,
) {
    // Draw the track
    if (trackColor != Color.Transparent && trackColor != Color.Unspecified) {
        drawPath(path = drawingCache.trackPathToDraw, color = trackColor, style = trackStroke)
    }

    // Draw the progress
    if (color != Color.Transparent && color != Color.Unspecified) {
        drawPath(path = drawingCache.progressPathToDraw, color = color, style = stroke)
    }
}

/**
 * A drawing cache of [Path]s and [PathMeasure] to be used when drawing circular progress
 * indicators.
 */
// Source: CircularProgressDrawingCache, internal/CircularWavyProgressModifiers.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/CircularWavyProgressModifiers.kt
private class CircularProgressDrawingCache {
    private var currentAmplitude = -1f
    private var currentWavelength = -1f
    private var currentSize: Size = Size.Unspecified

    /** Zero to one value that represents the progress start position. */
    private var currentStartProgress = 0f

    /** Zero to one value that represents the progress end position. */
    private var currentEndProgress = 0f
    private var currentIndicatorTrackGapSize = 0f
    private var currentWaveOffset = -1f
    private var currentStroke = Stroke()
    private var currentTrackStroke = currentStroke

    private var progressPathLength = 0f
    private var trackPathLength = 0f
    private var currentProgressMotionEnabled = false

    // Reusable Matrices for processing paths
    private val scaleMatrix = Matrix()
    private val transformMatrix = Matrix()

    /**
     * A [Path] that represents the progress indicator when it's in a complete state. A drawing path
     * can be computed from it and cached in this class with the use of [progressPathMeasure].
     */
    val fullProgressPath: Path = Path()

    /**
     * A [Path] that represents the progress indicator's track when it's in a complete state. A
     * drawing path can be computed from it and cached in this class with the use of
     * [trackPathMeasure].
     */
    val fullTrackPath: Path = Path()

    /** a [Path] that represents the current progress and will be used to draw it. */
    val progressPathToDraw: Path = Path()

    /** A [Path] that represents the track and will be used to draw it */
    val trackPathToDraw: Path = Path()

    /** A [PathMeasure] that will be used when computing a segment of a progress to be drawn */
    val progressPathMeasure: PathMeasure = PathMeasure()

    /** A [PathMeasure] that will be used when computing a segment of a track to be drawn */
    val trackPathMeasure: PathMeasure = PathMeasure()

    /** The current stroke Cap width. */
    var currentStrokeCapWidth = 0f

    /**
     * Creates or updates the progress and track paths, and caches them to avoid redundant updates
     * before updating the draw paths according to the progress.
     *
     * @param size the dimensions of the current drawing environment that this path is updated for
     * @param progressPathProvider a function that returns a progress [Path] for a given amplitude
     *   and other parameters that may affect the shape. The function is provided with a [Path]
     *   object to avoid having to create a new [Path] object.
     * @param trackPathProvider a function that returns an optional trackPath [Path] for a given
     *   amplitude and other parameters that may affect the shape. The function is provided with a
     *   [Path] object to avoid having to create a new [Path] object.
     * @param enableProgressMotion indicates if a progress motion should be enabled for the provided
     *   progress by offsetting the wave's drawing. When enabled, the calls to the
     *   [progressPathProvider] will be made with a `supportMotion = true`, and the generated [Path]
     *   will be repeated to allow drawing it while shifting the start and stop points and rotating
     *   it to simulate a motion.
     * @param startProgress a fractional start progress value between 0.0 to 1.0
     * @param endProgress a fractional start progress value between 0.0 to 1.0
     * @param amplitude the amplitude of a wave in pixels. 0.0 represents no amplitude, and 1.0
     *   represents an amplitude that will take the full height of the progress indicator.
     * @param waveOffset the offset that the progress indicator will be created at. This value will
     *   only bee applied to the progress path in case [enableProgressMotion] is true.
     * @param wavelength the length of a wave in the rendered circular shape. This actual length may
     *   end up differently to ensure a continuous wave shape.
     * @param gapSize the gap size in pixels between the progress indicator and the track. Note that
     *   the gap will be taken out of the track path and not the progress path.
     * @param stroke the [Stroke] that will be used to plot the paths
     * @param trackStroke a [Stroke] that will be used to draw the indicator's track. By default,
     *   the track's stroke matches the provided [stroke], so use this parameter to specify a
     *   different stroke if required.
     */
    fun updatePaths(
        size: Size,
        progressPathProvider:
            (
                amplitude: Float,
                wavelength: Float,
                strokeWidth: Float,
                size: Size,
                supportsMotion: Boolean,
                path: Path,
            ) -> Path,
        trackPathProvider:
            (
                amplitude: Float,
                wavelength: Float,
                strokeWidth: Float,
                size: Size,
                path: Path,
            ) -> Path?,
        enableProgressMotion: Boolean,
        @FloatRange(from = 0.0, to = 1.0) startProgress: Float,
        @FloatRange(from = 0.0, to = 1.0) endProgress: Float,
        @FloatRange(from = 0.0, to = 1.0) amplitude: Float,
        @FloatRange(from = 0.0, to = 1.0) waveOffset: Float,
        @FloatRange(from = 0.0, fromInclusive = false) wavelength: Float,
        @FloatRange(from = 0.0) gapSize: Float,
        stroke: Stroke,
        trackStroke: Stroke,
    ) {
        val pathsUpdates =
            updateFullPaths(
                size = size,
                progressPathProvider = progressPathProvider,
                trackPathProvider = trackPathProvider,
                enableProgressMotion = enableProgressMotion,
                amplitude = amplitude,
                wavelength = wavelength,
                gapSize = gapSize,
                stroke = stroke,
                trackStroke = trackStroke,
            )
        updateDrawPaths(
            forceUpdate = pathsUpdates,
            startProgress = startProgress,
            endProgress = endProgress,
            waveOffset = waveOffset,
        )
    }

    /**
     * Updates the progress and track paths, and caches them to avoid redundant updates.
     *
     * Call this function before calling [updateDrawPaths], which will cut segments of the full
     * paths for drawing using the internal [progressPathMeasure] and [trackPathMeasure] that this
     * function updates.
     *
     * @return true if the full paths were updated, or false otherwise
     * @see updateDrawPaths
     * @see updatePaths
     */
    private fun updateFullPaths(
        size: Size,
        progressPathProvider:
            (
                amplitude: Float,
                wavelength: Float,
                strokeWidth: Float,
                size: Size,
                supportsMotion: Boolean,
                path: Path,
            ) -> Path,
        trackPathProvider:
            (
                amplitude: Float,
                wavelength: Float,
                strokeWidth: Float,
                size: Size,
                path: Path,
            ) -> Path?,
        enableProgressMotion: Boolean,
        @FloatRange(from = 0.0, to = 1.0) amplitude: Float,
        @FloatRange(from = 0.0, fromInclusive = false) wavelength: Float,
        @FloatRange(from = 0.0) gapSize: Float,
        stroke: Stroke,
        trackStroke: Stroke,
    ): Boolean {
        if (
            currentSize == size &&
                currentAmplitude == amplitude &&
                currentWavelength == wavelength &&
                currentStroke == stroke &&
                currentTrackStroke == trackStroke &&
                currentIndicatorTrackGapSize == gapSize &&
                currentProgressMotionEnabled == enableProgressMotion
        ) {
            // No update required
            return false
        }

        val height = size.height
        val width = size.width

        // Update the Stroke cap width to take into consideration when drawing the Path.
        currentStrokeCapWidth =
            if (
                (stroke.cap == StrokeCap.Butt && trackStroke.cap == StrokeCap.Butt) ||
                    height > width
            ) {
                0f
            } else {
                max(stroke.width / 2, trackStroke.width / 2)
            }

        scaleMatrix.reset()
        scaleMatrix.apply { scale(x = width - stroke.width, y = height - stroke.width) }

        fullProgressPath.rewind()

        // Note that we pass in the enableProgressMotion when generating the Path. This may
        // generate a path that is double in length to support offsetting the drawing, so we make
        // sure to adjust for it when storing the progressPathLength.
        progressPathProvider(
            amplitude,
            wavelength,
            stroke.width,
            size,
            enableProgressMotion,
            fullProgressPath,
        )
        processPath(fullProgressPath, size, scaleMatrix)
        progressPathMeasure.setPath(path = fullProgressPath, forceClosed = true)
        progressPathLength =
            if (enableProgressMotion) {
                progressPathMeasure.length / 2
            } else {
                progressPathMeasure.length
            }

        fullTrackPath.rewind()
        val trackPathForAmplitude =
            trackPathProvider(amplitude, wavelength, stroke.width, size, fullTrackPath)
        if (trackPathForAmplitude != null) {
            processPath(fullTrackPath, size, scaleMatrix)
            trackPathMeasure.setPath(path = fullTrackPath, forceClosed = true)
            trackPathLength = trackPathMeasure.length
        } else {
            trackPathLength = 0f
        }

        // Cache the full path attributes
        currentSize = size
        currentAmplitude = amplitude
        currentWavelength = wavelength
        currentStroke = stroke
        currentTrackStroke = trackStroke
        currentIndicatorTrackGapSize = gapSize
        currentProgressMotionEnabled = enableProgressMotion

        return true
    }

    /** Process a given path by scaling it and then centering it inside a given size. */
    private fun processPath(path: Path, size: Size, scaleMatrix: Matrix) {
        path.transform(scaleMatrix)
        val progressPathBounds = path.getBounds()
        // Translate the path to align its center with the available size center.
        path.translate(size.center - progressPathBounds.center)
    }

    /**
     * Updates and caches the draw paths by to the progress and wave offset.
     *
     * It's important to call this function only _after_ a call for [updateFullPaths] was made.
     *
     * @param forceUpdate for an update to the drawing paths. This flag will be set to true when the
     *   [updateFullPaths] returns true to indicate that the base paths were updated.
     * @param startProgress a fractional start progress value between 0.0 to 1.0
     * @param endProgress a fractional start progress value between 0.0 to 1.0
     * @param waveOffset the offset that the progress indicator will be rendered at
     * @see updateFullPaths
     * @see updatePaths
     */
    private fun updateDrawPaths(
        forceUpdate: Boolean,
        @FloatRange(from = 0.0, to = 1.0) startProgress: Float,
        @FloatRange(from = 0.0, to = 1.0) endProgress: Float,
        @FloatRange(from = 0.0, to = 1.0) waveOffset: Float,
    ) {
        require(currentSize != Size.Unspecified) {
            "updateDrawPaths was called before updateFullPaths"
        }
        if (
            !forceUpdate &&
                currentStartProgress == startProgress &&
                currentEndProgress == endProgress &&
                currentWaveOffset == waveOffset
        ) {
            // No update required
            return
        }

        trackPathToDraw.rewind()
        progressPathToDraw.rewind()

        val pStart = startProgress * progressPathLength
        val pStop = endProgress * progressPathLength

        val trackGapSize = min(pStop, currentIndicatorTrackGapSize)
        val horizontalInsets = min(pStop, currentStrokeCapWidth)
        val trackSpacing = horizontalInsets * 2 + trackGapSize

        // Handle offsetting the path when motion is enabled. We assume that the provided path when
        // motion is enabled was repeated to allow us to call getSegment with offsets and then
        // rotate the progressPathToDraw in order to create a shifted path as the progress moves.
        if (currentProgressMotionEnabled) {
            val coercedWaveOffset = waveOffset.fastCoerceIn(0f, 1f)
            val startStopShift = coercedWaveOffset * progressPathLength

            progressPathMeasure.getSegment(
                startDistance = pStart + startStopShift,
                stopDistance = pStop + startStopShift,
                destination = progressPathToDraw,
            )

            val offsetAngle = coercedWaveOffset * 360 % 360
            if (offsetAngle != 0f) {
                val fullProgressBounds = fullProgressPath.getBounds()
                // Rotate the progress path to adjust for the shift.
                progressPathToDraw.translate(
                    Offset(-fullProgressBounds.center.x, -fullProgressBounds.center.y)
                )
                transformMatrix.reset()
                progressPathToDraw.transform(
                    transformMatrix.apply { rotateZ(degrees = -offsetAngle) }
                )
                // Translate the path to align its center with the available size center.
                progressPathToDraw.translate(
                    Offset(fullProgressBounds.center.x, fullProgressBounds.center.y)
                )
            }
        } else {
            // No motion, so just grab the segment for the start and stop.
            progressPathMeasure.getSegment(
                startDistance = pStart,
                stopDistance = pStop,
                destination = progressPathToDraw,
            )
        }
        if (trackPathLength > 0) {
            val tStart = endProgress * trackPathLength + trackSpacing
            val tStop = trackPathLength - trackSpacing
            trackPathMeasure.getSegment(
                startDistance = tStart,
                stopDistance = tStop,
                destination = trackPathToDraw,
            )
        }

        // Cache
        currentStartProgress = startProgress
        currentEndProgress = endProgress
        currentWaveOffset = waveOffset
    }
}

/**
 * A class that returns and caches the [RoundedPolygon]s and the [Morph] that are displayed by
 * circular progress indicators.
 */
// Source: CircularShapes, internal/CircularWavyProgressModifiers.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/CircularWavyProgressModifiers.kt
private class CircularShapes {
    private var currentSize: Size? = null
    private var currentWavelength: Float = -1f

    /** A normalized [RoundedPolygon] for the circular indicator track. */
    private var trackPolygon: RoundedPolygon? = null

    /** A normalized [RoundedPolygon] for the circular indicator progress. */
    private var activeIndicatorPolygon: RoundedPolygon? = null

    /**
     * A [Morph] for the circular indicator progress track. This morph will transition from a circle
     * to a wavy star shape.
     *
     * Note that the [RoundedPolygon]s that this [Morph] is constructed with are normalized, which
     * allows us to process the generated paths more efficiently by just scaling them to the right
     * size.
     */
    private var activeIndicatorMorph: Morph? = null

    /** Holds the current vertex count as a state. */
    val currentVertexCount = mutableIntStateOf(-1)

    /**
     * Updates the shapes according to the size of the circular loader and its wave's wavelength and
     * strokeWidth.
     *
     * In case the given parameters are different from the current ones, the shapes will be updated.
     *
     * @param size the dimensions of the current drawing environment that the shape are updated for
     * @param wavelength the length of a wave in the rendered circular shape in pixels. This actual
     *   length may end up differently to ensure a continuous wave shape.
     * @param strokeWidth the stroke's width in pixels
     * @param requiresMorph indicate that a [Morph] should be created for the active indicator if it
     *   was not already. The flag is useful to effectively only ask for the creation of the [Morph]
     *   when needed, for example, when an amplitude is set for the first time to a value that is
     *   between 0 and 1.
     */
    fun update(
        size: Size,
        @FloatRange(from = 0.0, fromInclusive = false) wavelength: Float,
        @FloatRange(from = 0.0, fromInclusive = false) strokeWidth: Float,
        requiresMorph: Boolean,
    ) {
        require(wavelength > 0f) { "Wavelength should be greater than zero" }
        if (size == currentSize && wavelength == currentWavelength) {
            if (requiresMorph && activeIndicatorMorph == null) {
                // This is the first time a requiredMorph is set, so we need to create a new Morph.
                // At this point, the trackPolygon and activeIndicatorPolygon are already set.
                activeIndicatorMorph = Morph(start = trackPolygon!!, end = activeIndicatorPolygon!!)
            }
            return
        }
        // Compute the number of edges as a factor of the circle size that the morph will be
        // rendered in and its proposed wavelength (2πr / wavelength), where the radius takes into
        // account the stroke's width.
        val r = size.minDimension / 2 - strokeWidth / 2
        val numVertices = max(MinCircularVertexCount, (2 * PI * r / wavelength).fastRoundToInt())

        if (numVertices != currentVertexCount.intValue) {
            // Note that we match the vertices number at the track's polygon. This will result in a
            // smoother morphing between the active indicator and the track.
            trackPolygon = RoundedPolygon.circle(numVertices = numVertices).normalized()
            activeIndicatorPolygon =
                RoundedPolygon.star(
                        numVerticesPerRadius = numVertices,
                        innerRadius = 0.75f,
                        rounding = CornerRounding(radius = 0.35f, smoothing = 0.4f),
                        innerRounding = CornerRounding(radius = 0.5f),
                    )
                    .normalized()
            if (requiresMorph) {
                activeIndicatorMorph = Morph(start = trackPolygon!!, end = activeIndicatorPolygon!!)
            }
        }

        currentSize = size
        currentWavelength = wavelength
        currentVertexCount.intValue = numVertices
    }

    /** Returns the path for the track polygon. */
    fun getTrackPath(path: Path): Path {
        trackPolygon?.toPath(path = path)
        return path
    }

    /**
     * Returns the path for the active indicator polygon (i.e. the progress indication path). In
     * case a [Morph] was previously created at the [update], the [Morph] will be used to generate
     * the [Path]. Otherwise, the active indicator polygon will be returned when the amplitude is 1,
     * and the track polygon otherwise.
     */
    fun getProgressPath(
        @FloatRange(from = 0.0, to = 1.0) amplitude: Float,
        path: Path,
        repeatPath: Boolean,
        rotationPivotX: Float = 0.5f,
        rotationPivotY: Float = 0.5f,
    ): Path {
        if (activeIndicatorMorph != null) {
            activeIndicatorMorph!!.toPath(
                progress = amplitude,
                path = path,
                repeatPath = repeatPath,
                // The RoundedPolygon used in the Morph were normalized (i.e. moves to
                // (0.5, 0.5)).
                rotationPivotX = rotationPivotX,
                rotationPivotY = rotationPivotY,
            )
        } else {
            // In case there is no Morph (i.e. it was never created at the update call because a
            // requiresMorph was never true), we return the active indicator polygon in case the
            // amplitude is 1. Otherwise, we return the track polygon.
            if (amplitude == 1f && activeIndicatorPolygon != null) {
                activeIndicatorPolygon!!.toPath(path = path, repeatPath = repeatPath)
            } else {
                trackPolygon?.toPath(path = path, repeatPath = repeatPath)
            }
        }
        return path
    }
}

private const val MinCircularVertexCount = 5

/**
 * Gets a [Path] representation for a [RoundedPolygon] shape. Note that there is some rounding
 * happening (to the nearest thousandth), to work around rendering artifacts introduced by some
 * points being just slightly off from each other (far less than a pixel).
 *
 * @param path a [Path] object which, if supplied, will avoid the function having to create a new
 *   [Path] object
 * @param startAngle an angle (in degrees) to rotate the [Path] to start drawing from. The rotation
 *   pivot is set to be the polygon's centerX and centerY coordinates.
 * @param repeatPath whether or not to repeat the [Path] twice before closing it, so the caller can
 *   draw parts of the path while offsetting the start and stop positions.
 * @param closePath whether or not to close the created [Path]
 */
// Source: RoundedPolygon.toPath, internal/ShapeUtil.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/ShapeUtil.kt
private fun RoundedPolygon.toPath(
    path: Path = Path(),
    startAngle: Int = 270,
    repeatPath: Boolean = false,
    closePath: Boolean = true,
): Path {
    pathFromCubics(
        path = path,
        startAngle = startAngle,
        repeatPath = repeatPath,
        closePath = closePath,
        cubics = cubics,
        rotationPivotX = centerX,
        rotationPivotY = centerY,
    )
    return path
}

/**
 * Returns a [Path] for a [Morph].
 *
 * @param progress the [Morph]'s progress
 * @param path a [Path] to rewind and set with the new path data
 * @param startAngle an angle (in degrees) to rotate the [Path] to start drawing from
 * @param repeatPath whether or not to repeat the [Path] twice before closing it
 * @param closePath whether or not to close the created [Path]
 * @param rotationPivotX the rotation pivot on the X axis; for normalized polygons it is 0.5
 * @param rotationPivotY the rotation pivot on the Y axis; for normalized polygons it is 0.5
 */
// Source: Morph.toPath, internal/ShapeUtil.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/ShapeUtil.kt
private fun Morph.toPath(
    progress: Float,
    path: Path = Path(),
    startAngle: Int = 270, // 12 O'clock
    repeatPath: Boolean = false,
    closePath: Boolean = true,
    rotationPivotX: Float = 0f,
    rotationPivotY: Float = 0f,
): Path {
    pathFromCubics(
        path = path,
        startAngle = startAngle,
        repeatPath = repeatPath,
        closePath = closePath,
        cubics = asCubics(progress),
        rotationPivotX = rotationPivotX,
        rotationPivotY = rotationPivotY,
    )
    return path
}

// Source: pathFromCubics, internal/ShapeUtil.kt
// https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/internal/ShapeUtil.kt
private fun pathFromCubics(
    path: Path,
    startAngle: Int,
    repeatPath: Boolean,
    closePath: Boolean,
    cubics: List<Cubic>,
    rotationPivotX: Float,
    rotationPivotY: Float,
) {
    var first = true
    var firstCubic: Cubic? = null
    path.rewind()
    cubics.fastForEach {
        if (first) {
            path.moveTo(it.anchor0X, it.anchor0Y)
            if (startAngle != 0) {
                firstCubic = it
            }
            first = false
        }
        path.cubicTo(
            it.control0X,
            it.control0Y,
            it.control1X,
            it.control1Y,
            it.anchor1X,
            it.anchor1Y,
        )
    }
    if (repeatPath) {
        var firstInRepeat = true
        cubics.fastForEach {
            if (firstInRepeat) {
                path.lineTo(it.anchor0X, it.anchor0Y)
                firstInRepeat = false
            }
            path.cubicTo(
                it.control0X,
                it.control0Y,
                it.control1X,
                it.control1Y,
                it.anchor1X,
                it.anchor1Y,
            )
        }
    }

    if (closePath) path.close()

    if (startAngle != 0 && firstCubic != null) {
        val angleToFirstCubic =
            radiansToDegrees(
                atan2(
                    y = cubics[0].anchor0Y - rotationPivotY,
                    x = cubics[0].anchor0X - rotationPivotX,
                )
            )
        // Rotate the Path to to start from the given angle.
        path.transform(Matrix().apply { rotateZ(-angleToFirstCubic + startAngle) })
    }
}

private fun radiansToDegrees(radians: Float): Float {
    return (radians * 180.0 / PI).toFloat()
}
