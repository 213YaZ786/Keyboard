package helium314.keyboard.latin.ui.component

import kotlin.math.sin
import kotlin.math.floor
import kotlin.math.PI
import androidx.compose.ui.graphics.Color
import helium314.keyboard.latin.ui.glass.glassFloating
import helium314.keyboard.latin.ui.glass.LocalGlassBackdrop
import helium314.keyboard.latin.ui.glass.LocalGlass
import helium314.keyboard.latin.ui.theme.zone
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Space the floating dock covers at the bottom of the screen. Scrolling
 * screens add it to their bottom padding so their last item is not hidden.
 */
val LocalDockPadding = staticCompositionLocalOf { 0.dp }

/** Height of the dock plus the gap under it, for [LocalDockPadding]. */
val DockClearance: Dp = 96.dp

/**
 * Width the vertical dock takes on the left of a wide window: the 16 dp gap
 * to the edge, the 64 dp pill, and 16 dp of air before the content.
 */
val SideDockClearance: Dp = 96.dp

/** A tab of the dock; [dot] marks something new on it, in [dotColor] when given (the accent otherwise). */
data class DockItem(val icon: ImageVector, val label: String, val dot: Boolean = false, val dotColor: Color = Color.Unspecified)

/**
 * A floating pill with one icon per tab. The highlight follows the finger
 * while swiping between tabs, so the dock and the pages always agree.
 *
 * [position] is the current page plus how far the swipe has moved toward the
 * next one, from 0 to the last index.
 *
 * [vertical] stacks the same pill upright, for the left edge of a tablet or
 * a phone on its side. Same shape, colours and icons, so both read as one app.
 */
@Composable
fun FloatingDock(
    items: List<DockItem>,
    position: Float,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false
) {
    // Each item is long along the dock and short across it, in both directions.
    val alongDock = 64.dp
    val acrossDock = 48.dp
    val itemWidth = if (vertical) acrossDock else alongDock
    val itemHeight = if (vertical) alongDock else acrossDock
    val stepPx = with(LocalDensity.current) { alongDock.toPx() }
    // No extra animation here: the pager already animates taps, and while
    // swiping the highlight must stay exactly under the finger.
    val animated = position

    val haptics = rememberHaptics()

    // In glass, the dock bends the tabs under it, with no edge nor shadow,
    // and the chosen tab is a drop of glass in the accent that stretches as
    // it slides from one tab to the next.
    val look = LocalGlass.current
    val backdrop = LocalGlassBackdrop.current
    val glass = look != null && backdrop != null
    Surface(
        shape = CircleShape,
        // Tinted like every zone, with the edge the buttons carry, so the
        // dock reads as the row of actions it is.
        color = if (glass) Color.Transparent else MaterialTheme.colorScheme.zone,
        border = if (glass) null else boldBorder(),
        shadowElevation = if (glass) 0.dp else 8.dp,
        tonalElevation = if (glass) 0.dp else 2.dp,
        modifier = if (glass) modifier.glassFloating(backdrop!!, CircleShape, look!!) else modifier
    ) {
        Box(Modifier.padding(8.dp)) {
            // Half way between two tabs the drop is at its longest.
            val stretch = if (glass) sin(PI.toFloat() * (animated - floor(animated))) * 0.35f else 0f
            val along = if (vertical) itemHeight else itemWidth
            val extraPx = with(LocalDensity.current) { (along * stretch).toPx() }
            Box(
                Modifier
                    .offset {
                        val step = (animated * stepPx - extraPx / 2f).roundToInt()
                        if (vertical) IntOffset(0, step) else IntOffset(step, 0)
                    }
                    .size(
                        if (vertical) itemWidth else itemWidth * (1f + stretch),
                        if (vertical) itemHeight * (1f + stretch) else itemHeight
                    )
                    .then(
                        if (glass) {
                            // By day a pale wash of the accent, whatever the wallpaper: the dark icon on it stays clear.
                            Modifier.glassFloating(backdrop!!, CircleShape, look!!, tint = if (look.dark) look.accentTint else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f), lens = 1.6f)
                        } else {
                            Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
                        }
                    )
            )
            val buttons: @Composable () -> Unit = {
                items.forEachIndexed { index, item ->
                    val closeness = (1f - abs(animated - index)).coerceIn(0f, 1f)
                    val tint = lerp(
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        // On the dark wash of the night the chosen one is light, as the night's text.
                        if (glass && look!!.dark) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSecondaryContainer,
                        closeness
                    )
                    Box(
                        modifier = Modifier
                            .size(itemWidth, itemHeight)
                            .clip(CircleShape)
                            .clickable(onClickLabel = item.label, role = Role.Tab) {
                                // Firm: moving to another tab is taking the
                                // whole screen somewhere else, not pressing a
                                // button on the one you are reading.
                                haptics.firm()
                                onSelect(index)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(item.icon, contentDescription = item.label, tint = tint)
                        if (item.dotColor == Color.Unspecified) NewDot(item.dot, Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = if (vertical) 6.dp else 14.dp))
                        else if (item.dot) Box(
                            Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = if (vertical) 6.dp else 14.dp)
                                .size(10.dp).background(item.dotColor, CircleShape)
                        )
                    }
                }
            }
            if (vertical) Column { buttons() } else Row { buttons() }
        }
    }
}
