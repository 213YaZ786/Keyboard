package helium314.keyboard.latin.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Every list gives way at its ends: pulled past the first or last row, the content follows the
// finger with resistance and springs back on release; a fling that reaches the end bounces.
// Vertical, as the sample it comes from. Where the system's animations are removed (animator
// duration scale 0) the list stops dead at its ends.
// The sample's 0.1 hardly shows on a phone (checked on the tablet 2026-10-28): the content
// moves under a third of the finger's way, closer to iOS's rubber band.
private const val RESISTANCE = 0.3f

/**
 * [OffsetOverscrollEffect] of androidx's own sample, adapted: the offset is
 * applied in the layer block (no relayout on each frame), the coroutine scope
 * is the node's own (a factory has no composition to ask for one), and the
 * motion is skipped when the system's duration scale is 0.
 * Source: https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/samples/src/main/java/androidx/compose/foundation/samples/OverscrollSample.kt
 * (Apache-2.0). The node's scope: Modifier.Node.coroutineScope,
 * https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/Modifier.kt
 * The duration scale: MotionDurationScale.scaleFactor,
 * https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/MotionDurationScale.kt
 */
private class BounceOverscrollEffect : OverscrollEffect {
    private val overscrollOffset = Animatable(0f)

    private val bounceNode = object : Modifier.Node(), LayoutModifierNode {
        override fun onAttach() {
            // A node detached mid-bounce must not come back displaced.
            coroutineScope.launch { overscrollOffset.snapTo(0f) }
        }

        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            val placeable = measurable.measure(constraints)
            return layout(placeable.width, placeable.height) {
                placeable.placeRelativeWithLayer(0, 0) { translationY = overscrollOffset.value }
            }
        }
    }

    /** The node's scope while it is on screen, null when there is no motion to make. */
    private fun motionScope(): CoroutineScope? {
        if (!bounceNode.isAttached) return null
        val scope = bounceNode.coroutineScope
        return if (scope.coroutineContext[MotionDurationScale]?.scaleFactor == 0f) null else scope
    }

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset
    ): Offset {
        val scope = motionScope() ?: return performScroll(delta)
        // in pre scroll we relax the overscroll if needed
        // relaxation: when we are in progress of the overscroll and user scrolls in the
        // different direction = substract the overscroll first
        val sameDirection = sign(delta.y) == sign(overscrollOffset.value)
        val consumedByPreScroll =
            if (abs(overscrollOffset.value) > 0.5 && !sameDirection) {
                val prevOverscrollValue = overscrollOffset.value
                val newOverscrollValue = overscrollOffset.value + delta.y
                if (sign(prevOverscrollValue) != sign(newOverscrollValue)) {
                    // sign changed, coerce to start scrolling and exit
                    scope.launch { overscrollOffset.snapTo(0f) }
                    Offset(x = 0f, y = delta.y + prevOverscrollValue)
                } else {
                    scope.launch { overscrollOffset.snapTo(overscrollOffset.value + delta.y) }
                    delta.copy(x = 0f)
                }
            } else {
                Offset.Zero
            }
        val leftForScroll = delta - consumedByPreScroll
        val consumedByScroll = performScroll(leftForScroll)
        val overscrollDelta = leftForScroll - consumedByScroll
        // if it is a drag, not a fling, add the delta left to our over scroll value
        if (abs(overscrollDelta.y) > 0.5 && source == NestedScrollSource.UserInput) {
            scope.launch {
                // multiply by 0.1 for the sake of parallax effect (the sample's comment)
                overscrollOffset.snapTo(overscrollOffset.value + overscrollDelta.y * RESISTANCE)
            }
        }
        return consumedByPreScroll + consumedByScroll
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        val consumed = performFling(velocity)
        if (motionScope() == null) return
        // when the fling happens - we just gradually animate our overscroll to 0
        val remaining = velocity - consumed
        overscrollOffset.animateTo(targetValue = 0f, initialVelocity = remaining.y, animationSpec = spring())
    }

    override val isInProgress: Boolean
        get() = overscrollOffset.value != 0f

    override val node: DelegatableNode = bounceNode
}

/**
 * The factory the scrolling components ask for their effect, as
 * [OverscrollFactory] requires (equals and hashCode of a singleton).
 * Source: https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/Overscroll.kt
 */
object BounceOverscrollFactory : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = BounceOverscrollEffect()
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = 0
}

/**
 * Every list, grid and pager below gets the bounce, through
 * LocalOverscrollFactory (the documented way to change the effect of a whole
 * hierarchy).
 */
@Composable
fun ProvideBounce(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverscrollFactory provides BounceOverscrollFactory, content = content)
}
