package helium314.keyboard.latin.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** What a swipe does to an item; [removes] makes the item leave the way it was pushed. */
class SwipeAction(val icon: ImageVector, val label: String, val danger: Boolean = false, val removes: Boolean = danger, val run: () -> Unit)

/**
 * An item of a list that a swipe acts on, as SMS's conversations: it follows
 * the finger, the action it uncovers grows under it, past a point the action
 * lights with a tick, and letting go runs it. [start] is uncovered by a swipe
 * towards the end, [end] by a swipe towards the start; null leaves that side
 * still. The item keeps its own taps and long press.
 */
@Composable
fun SwipeRow(start: SwipeAction?, end: SwipeAction?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val pull = remember { Animatable(0f) }
    var armed by remember { mutableStateOf(false) }
    val reach = with(LocalDensity.current) { 88.dp.toPx() }
    fun sideOf(x: Float) = if (x < 0f) end else start
    Box(modifier.pointerInput(start, end) {
        detectHorizontalDragGestures(
            onDragEnd = {
                val action = sideOf(pull.value)
                val fire = armed && action != null
                armed = false
                scope.launch {
                    if (fire && action!!.removes) {
                        pull.animateTo(if (pull.value < 0f) -size.width.toFloat() else size.width.toFloat(), tween(180))
                        action.run()
                        pull.snapTo(0f)
                    } else {
                        if (fire) action!!.run()
                        pull.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 400f))
                    }
                }
            },
            onDragCancel = {
                armed = false
                scope.launch { pull.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 400f)) }
            },
            onHorizontalDrag = { change, dx ->
                val beyond = kotlin.math.abs(pull.value) > reach
                val next = (pull.value + dx * (if (beyond) 0.35f else 1f)).coerceIn(if (end == null) 0f else -reach * 1.6f, if (start == null) 0f else reach * 1.6f)
                scope.launch { pull.snapTo(next) }
                val now = kotlin.math.abs(next) >= reach && sideOf(next) != null
                if (now != armed) {
                    armed = now
                    haptics.threshold(now)
                }
                change.consume()
            }
        )
    }) {
        val side = sideOf(pull.value)
        if (pull.value != 0f && side != null) {
            val tint = when {
                !armed -> MaterialTheme.colorScheme.onSurfaceVariant
                side.danger -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(if (pull.value < 0f) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 20.dp)
                    .graphicsLayer {
                        val shown = (kotlin.math.abs(pull.value) / reach).coerceIn(0f, 1f)
                        alpha = shown
                        scaleX = 0.7f + 0.3f * shown
                        scaleY = 0.7f + 0.3f * shown
                    }
            ) {
                Icon(side.icon, contentDescription = null, tint = tint)
                Spacer(Modifier.width(8.dp))
                Text(side.label, style = MaterialTheme.typography.labelLarge, color = tint)
            }
        }
        Box(Modifier.graphicsLayer { translationX = pull.value }) { content() }
    }
}
