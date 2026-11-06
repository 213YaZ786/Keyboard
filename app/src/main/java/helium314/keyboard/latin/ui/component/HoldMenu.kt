package helium314.keyboard.latin.ui.component

import helium314.keyboard.latin.navigation.LocalReadableInset
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The suite's long press menu for any held item, SMS's message menu made generic (ported from
 * https://github.com/213YaZ786/SMS/blob/main/app/src/main/java/com/yaz/sms/feature/thread/MessageMenu.kt and
 * MenuPlacement.kt, whose own upstreams are cited below). The item lifts out of its place over the blurred screen.
 * Under it (over it where there is no room) a pane of glass shows the actions as round glass tiles in balanced rows,
 * the name under each icon, the dangerous one in red. Over it, if the caller gives [reactions], a bar of emoji hops
 * into place one by one. A tap outside or Back closes it.
 *
 * How a caller uses it: keep a `menuOpen` flag and the item's bounds, taken with
 * `Modifier.onGloballyPositioned { bounds = it.boundsInWindow() }`. A long press sets `menuOpen = true`. While it
 * is open the original is hidden with `Modifier.graphicsLayer { alpha = if (menuOpen) 0f else 1f }`, as SMS's
 * ThreadScreen.kt does at lines 1904 to 1969, and `if (menuOpen) HoldMenu(bounds, actions, { menuOpen = false }) { ... }`
 * is composed beside it. The lambda `held` draws the item again, at the size of [bounds], and HoldMenu lifts that
 * copy. The "+" after the reactions shows only when both [onMoreReactions] and [moreIcon] are given.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
@Composable
fun HoldMenu(
    bounds: Rect,
    actions: List<HoldAction>,
    onDismiss: () -> Unit,
    reactions: List<String>? = null,
    chosen: List<String> = emptyList(),
    onReact: (String) -> Unit = {},
    onMoreReactions: (() -> Unit)? = null,
    moreIcon: ImageVector? = null,
    held: @Composable () -> Unit
) {
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val gap = with(density) { 10.dp.roundToPx() }
    val edge = with(density) { 12.dp.roundToPx() }
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    var listSize by remember { mutableStateOf(IntSize.Zero) }
    // The pane's height with all its rows, reported by HoldPalette, whatever room it is given.
    var naturalPane by remember { mutableIntStateOf(0) }
    // The item's own window: the bounds are in its coordinates, the dialog's window may sit elsewhere.
    val host = LocalView.current
    val heldBounds = bounds
    // A window of its own over the screen, blurred behind it, as PillMenu does (this kit's PillMenu.kt; AOSP's
    // window-blur recipe, https://source.android.com/docs/core/display/window-blurs): Dialog with
    // usePlatformDefaultWidth = false and decorFitsSystemWindows = false, BlurBehind and rememberCrossWindowBlur
    // from ZoneDialog.kt.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // Blur off (battery saver, GrapheneOS): BlurBehind gives a stronger dim, the same as PillMenu's.
        val blur = rememberCrossWindowBlur()
        BlurBehind(radiusDp = 24, blur = blur)
        // Where the dialog's window starts on the screen, against the item's: PillMenu maps the item's
        // bounds the same way (item = state.bounds.translate(-at), positionOnScreen).
        var origin by remember { mutableStateOf<Offset?>(null) }
        val bounds = origin?.let { at ->
            val hostAt = IntArray(2).also { host.getLocationOnScreen(it) }
            heldBounds.translate(Offset(hostAt[0].toFloat(), hostAt[1].toFloat()) - at)
        } ?: heldBounds
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin = it.positionOnScreen() }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        ) {
            var window by remember { mutableStateOf(IntSize.Zero) }
            Box(Modifier.fillMaxSize().onSizeChanged { window = it })
            // The reaction bar and the actions are centred on the item's own bounds (see HoldPlacement), inside
            // the readable column, which on a tablet is narrower than the window.
            val columnInset = with(density) { LocalReadableInset.current.roundToPx() }
            fun xFor(width: Int): Int =
                HoldPlacement.x(bounds.left.roundToInt(), bounds.right.roundToInt(), width, window.width, columnInset, edge)
            // The item itself, above the veil, lifted a little.
            val rise = remember { Animatable(0f) }
            LaunchedEffect(Unit) { rise.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f)) }
            // The room is the window less the system bars (WindowInsets.safeDrawing, Android's inset types,
            // https://developer.android.com/develop/ui/compose/system/insets) and the edge margin: the reactions
            // bar, the lifted item and the pane all stay inside it. As long as the pane, whole, fits under the
            // item or above it, everything hangs off the item's own place. Otherwise (the 200 % font, a tall
            // item) the three are stacked by priority from the item's place: the reactions bar always whole,
            // then the item, whole if it fits with a first part of the pane, else clipped to a quarter of the
            // window with a soft fade at the cut (Signal's long press shows a clipped preview of a long message),
            // then the pane takes the rest and its grid scrolls.
            val bars = WindowInsets.safeDrawing
            val top = bars.getTop(density) + edge
            val bottomLimit = window.height - bars.getBottom(density) - edge
            val msgTop = bounds.top.roundToInt()
            val msgH = bounds.height.roundToInt()
            // The lift below (scale 1.05 around the centre, 4 dp up) draws the item beyond its bounds: graphicsLayer
            // changes only the drawing, never the layout (TransformOrigin.Center,
            // https://developer.android.com/develop/ui/compose/graphics/draw/modifiers). Its top edge rises by half the
            // growth plus 4 dp, its bottom edge drops by half the growth less 4 dp; the gaps are kept beyond those
            // edges, else a tall item at 200 % touches the reactions bar.
            val rise4 = with(density) { 4.dp.roundToPx() }
            val liftTop = (msgH * 0.025f).roundToInt() + rise4
            val liftBottom = ((msgH * 0.025f).roundToInt() - rise4).coerceAtLeast(0)
            val reactionsH = if (reactions != null) rowSize.height + gap + liftTop else 0
            val below = bounds.bottom.roundToInt() + liftBottom + gap
            val roomBelow = bottomLimit - below
            val roomAbove = msgTop - (if (reactions != null) reactionsH else liftTop) - gap - top
            val fitsBelow = naturalPane <= roomBelow
            val fitsAbove = naturalPane <= roomAbove
            // What goes above the item's lifted top: the reactions bar and its gap, or only the lift.
            val headH = if (reactions != null) reactionsH else liftTop
            // An item partly scrolled out at the top or the bottom is brought fully on screen with the rest
            // stacked around it, never with the bar pinned to the edge over it.
            val offScreen = msgTop - headH < top || bounds.bottom.roundToInt() + liftBottom > bottomLimit
            val stacked = window.height > 0 && (offScreen || (!fitsBelow && !fitsAbove))
            val total = bottomLimit - top
            val paneMin = minOf(naturalPane, with(density) { 220.dp.roundToPx() })
            val shownH = if (!stacked || total - headH - gap - liftBottom - msgH >= paneMin) msgH else minOf(msgH, window.height / 4)
            val paneRoom = total - headH - shownH - liftBottom - gap
            val stackH = headH + shownH + liftBottom + gap + minOf(naturalPane, paneRoom).coerceAtLeast(0)
            val stackTop = (msgTop - headH).coerceIn(top, maxOf(top, bottomLimit - stackH))
            val msgY = if (stacked) stackTop + headH else msgTop
            val listY = when {
                stacked -> msgY + shownH + liftBottom + gap
                fitsBelow -> below
                reactions != null -> (msgTop - gap - reactionsH - listSize.height).coerceAtLeast(top)
                else -> (msgTop - liftTop - gap - listSize.height).coerceAtLeast(top)
            }
            val rowY = when {
                stacked -> stackTop
                fitsBelow -> (msgTop - liftTop - gap - rowSize.height).coerceAtLeast(top)
                else -> listY + listSize.height + gap
            }
            val paneMax = when {
                window.height <= 0 -> Dp.Unspecified
                stacked -> with(density) { paneRoom.coerceAtLeast(0).toDp() }
                else -> with(density) {
                    maxOf(roomBelow, roomAbove).coerceAtLeast(220.dp.roundToPx()).coerceAtMost(total - reactionsH).toDp()
                }
            }
            // The item itself, above the veil, lifted a little; clipped with a fade when only part of it fits.
            val clipped = shownH < msgH
            Box(
                Modifier
                    .offset { IntOffset(bounds.left.roundToInt(), msgY) }
                    // fixed size: the held item's own measured bounds, so the copy lands exactly on the original
                    .size(with(density) { bounds.width.toDp() }, with(density) { shownH.toDp() })
                    .graphicsLayer {
                        val s = 1f + 0.05f * rise.value
                        scaleX = s
                        scaleY = s
                        translationY = -4.dp.toPx() * rise.value
                    }
                    // Cut short, the end fades out instead of ending on a hard line: the layer is drawn offscreen and a
                    // gradient from opaque to transparent is drawn over its last 48 dp with BlendMode.DstIn, the bottom
                    // edge of OuterTune's fadingEdge (GPL-3.0,
                    // https://github.com/OuterTune/OuterTune/blob/main/app/src/main/java/com/dd3boh/outertune/ui/utils/FadingEdge.kt),
                    // clipped to the bounds first (https://developer.android.com/develop/ui/compose/graphics/draw/modifiers).
                    .then(
                        if (clipped) {
                            Modifier
                                .clipToBounds()
                                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                                .drawWithContent {
                                    drawContent()
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(Color.Black, Color.Transparent),
                                            startY = size.height - 48.dp.toPx(),
                                            endY = size.height
                                        ),
                                        blendMode = BlendMode.DstIn
                                    )
                                }
                        } else Modifier
                    )
            ) {
                if (clipped) {
                    // fixed size: the item laid out at its whole measured bounds, then cut by the box above
                    Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).size(with(density) { bounds.width.toDp() }, with(density) { msgH.toDp() })) { held() }
                } else held()
            }

            if (reactions != null) {
                ZoneSurface(
                    shape = CircleShape,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .offset { IntOffset(xFor(rowSize.width), rowY) }
                        .onSizeChanged { rowSize = it }
                        .popIn(0)
                ) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        reactions.forEachIndexed { i, emoji ->
                            val hop = remember { Animatable(1f) }
                            LaunchedEffect(Unit) {
                                delay(40L * i)
                                hop.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 500f))
                            }
                            val on = emoji in chosen
                            Box(
                                Modifier
                                    // fixed size: a round touch target holding one emoji, a single glyph that never wraps
                                    .size(44.dp)
                                    .graphicsLayer {
                                        translationY = hop.value * 10.dp.toPx()
                                        alpha = 1f - hop.value
                                    }
                                    .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent, CircleShape)
                                    // The press is Android 12's sparkle ripple in a circle (SparklePress), never a square.
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = rememberSparklePress(CircleShape),
                                        onClickLabel = emoji
                                    ) {
                                        haptics.tick()
                                        onReact(emoji)
                                        onDismiss()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(emoji, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        // Any other emoji, from Android's picker: shown only when the caller gives both the action and its icon.
                        if (onMoreReactions != null && moreIcon != null) {
                            Box(
                                // fixed size: a round touch target holding one icon, no text
                                Modifier.size(44.dp).clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = rememberSparklePress(CircleShape),
                                    onClickLabel = "More reactions"
                                ) {
                                    haptics.tick()
                                    onDismiss()
                                    onMoreReactions()
                                },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(moreIcon, contentDescription = "More reactions", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            HoldPalette(
                actions,
                onDismiss,
                Modifier
                    .offset { IntOffset(xFor(listSize.width), listY) }
                    .onSizeChanged { listSize = it },
                maxHeight = paneMax,
                onNatural = { naturalPane = it }
            )
        }
    }
}

/** One action of a held item's menu: its icon, its word, red when it takes something away. */
data class HoldAction(val icon: ImageVector, val label: String, val danger: Boolean = false, val run: () -> Unit)

/**
 * The actions as a palette: a pane of glass with a tile for each, the
 * icon over its word, four to a line at most, rising one by one. Nothing scrolls,
 * everything reads at a glance; what takes something away is in red. The lines
 * are measured and balanced ([BalancedFlow]), so a longer language or a larger
 * font gives fewer to a line and names on two lines, never a cut word.
 */
@Composable
private fun HoldPalette(
    actions: List<HoldAction>,
    onDismiss: () -> Unit,
    modifier: Modifier,
    maxHeight: Dp = Dp.Unspecified,
    onNatural: (Int) -> Unit = {}
) {
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    // The window less the pane's padding and its edge margin on each side.
    val room = (LocalConfiguration.current.screenWidthDp - 44).dp
    // Taller than [maxHeight] (the window less the system bars and the reactions bar, the 200 % font), the grid
    // scrolls inside the pane and the pane stays on screen (Modifier.verticalScroll under heightIn,
    // https://developer.android.com/develop/ui/compose/touch-input/pointer-input/scroll).
    val inner = if (maxHeight.isSpecified) Modifier.heightIn(max = (maxHeight - 24.dp).coerceAtLeast(0.dp)).verticalScroll(rememberScrollState()) else Modifier
    // Material 3's shape scale: https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Shapes.kt
    ZoneSurface(shape = ShapeDefaults.ExtraLarge, shadowElevation = 6.dp, modifier = modifier.popIn(1)) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 12.dp).widthIn(max = room).then(inner)) {
            BalancedFlow(
                labels = actions.map { it.label },
                style = MaterialTheme.typography.labelLarge,
                floor = 78.dp,
                gap = 6.dp,
                rowGap = 10.dp,
                // Inside the scroll the grid is measured whole: its height, with the pane's padding, is the pane's natural one.
                modifier = Modifier.onSizeChanged { onNatural(it.height + with(density) { 24.dp.roundToPx() }) }
            ) { i, tileModifier ->
                val action = actions[i]
                val rise = remember { Animatable(1f) }
                LaunchedEffect(Unit) {
                    delay(30L * i)
                    rise.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 500f))
                }
                val tint = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                // The whole tile stays the tap target, but the press plays only inside the round drop:
                // the tile's clickable has no indication, the drop shows the sparkle from this source.
                val press = remember { MutableInteractionSource() }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = tileModifier
                        .graphicsLayer {
                            translationY = rise.value * 8.dp.toPx()
                            alpha = 1f - rise.value
                        }
                        .clickable(interactionSource = press, indication = null, onClickLabel = action.label) {
                            haptics.tick()
                            onDismiss()
                            action.run()
                        }
                        .padding(vertical = 4.dp)
                ) {
                    // Each action in a drop of polished glass of its own (rim, bevel, shadow), the
                    // colour in the icon only: an accent wash flattened them into plain discs.
                    // fixed size: the round drop holds an icon only, its word sits under it in the adaptive tile
                    ZoneSurface(shape = CircleShape, modifier = Modifier.size(56.dp)) {
                        // The sparkle sits on the Box that fills the drop, above the glass, from its centre.
                        Box(
                            Modifier.fillMaxSize().indication(press, rememberSparklePress(CircleShape, fromCentre = true)),
                            contentAlignment = Alignment.Center
                        ) {
                            // fixed size: an icon, no text
                            Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    TileLabel(
                        action.label,
                        MaterialTheme.typography.labelLarge,
                        if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/** Grows from small with a spring, the second part a beat after the first. */
@Composable
private fun Modifier.popIn(order: Int): Modifier {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(30L * order)
        grow.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 600f))
    }
    return graphicsLayer {
        val s = 0.6f + 0.4f * grow.value
        scaleX = s
        scaleY = s
        alpha = grow.value.coerceIn(0f, 1f)
    }
}

/**
 * Where the long press panel and the reaction bar sit across the window, on the lifted item's own bounds (SMS's
 * MenuPlacement.kt, https://github.com/213YaZ786/SMS/blob/main/app/src/main/java/com/yaz/sms/feature/thread/MenuPlacement.kt).
 *
 * Both are centred on the item (the user, 2026-10-30), with the offset of Compose's BiasAlignment.Horizontal at
 * bias 0, `(space - size) / 2f` rounded (androidx, compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/Alignment.kt,
 * https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/Alignment.kt),
 * the space being the item's width. The result is then held inside the readable column as Signal Android's
 * ReactionOverlayPlacement keeps its menu inside the overlay (AGPL-3.0, https://github.com/signalapp/Signal-Android,
 * app/src/main/java/org/thoughtcrime/securesms/conversation/ReactionOverlayPlacement.kt): the readable column is the
 * window less columnInset on each side, with an edge margin.
 */
private object HoldPlacement {

    /**
     * The x of a panel [width] wide for an item at [bubbleLeft] to [bubbleRight]. All in pixels of the window. The
     * panel keeps inside [columnInset] to `windowWidth - columnInset`, [edge] from its sides; wider than that, it
     * starts at the column's left margin.
     */
    fun x(bubbleLeft: Int, bubbleRight: Int, width: Int, windowWidth: Int, columnInset: Int, edge: Int): Int {
        val centred = bubbleLeft + ((bubbleRight - bubbleLeft - width).toFloat() / 2f).roundToInt()
        val low = columnInset + edge
        val high = windowWidth - columnInset - edge - width
        return if (high < low) low else centred.coerceIn(low, high)
    }
}
