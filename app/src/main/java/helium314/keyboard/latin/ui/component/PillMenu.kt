package helium314.keyboard.latin.ui.component

import helium314.keyboard.latin.ui.glass.LocalGlass
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * The long press menu of every list, as iOS's context menu: the item held
 * lifts out of its place over the blurred screen, and a pane of glass under
 * it (over it near the bottom) lists the actions, the name before its icon,
 * a dangerous one in red. Each action answers with its own small motion
 * before it acts; a tap beside the pane or Back closes it.
 *
 * The line puts [tracker] on its outer box (it records the item to lift),
 * calls [open] on a long press, and places [PillMenu] inside that box.
 */
@Stable
class PillMenuState internal constructor(private val haptics: Haptics) {
    internal var pressAt by mutableStateOf(Offset.Zero)
    internal var shown by mutableStateOf(false)
    internal var bounds = Rect.Zero
    internal var picture: GraphicsLayer? = null

    val tracker: Modifier = Modifier
        .pointerInput(Unit) {
            awaitEachGesture {
                pressAt = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position
            }
        }
        .onGloballyPositioned { bounds = Rect(it.positionOnScreen(), it.size.let { s -> androidx.compose.ui.geometry.Size(s.width.toFloat(), s.height.toFloat()) }) }
        .drawWithCache {
            val layer = obtainGraphicsLayer()
            picture = layer
            onDrawWithContent {
                layer.record { this@onDrawWithContent.drawContent() }
                drawLayer(layer)
            }
        }

    fun open() {
        haptics.firm()
        shown = true
    }

    fun close() {
        shown = false
    }
}

@Composable
fun rememberPillMenu(): PillMenuState {
    val haptics = rememberHaptics()
    return remember { PillMenuState(haptics) }
}

enum class PillMotion { DROP, WIGGLE, BOUNCE }

/** One action of the menu; [danger] writes it in the error colour (delete, block). */
data class PillItem(
    val icon: ImageVector,
    val label: String,
    val motion: PillMotion,
    val tint: Color? = null,
    val danger: Boolean = false,
    val action: () -> Unit
)

/** [columns] is kept for the callers of the older menu: the actions are one list now. */
@Composable
fun PillMenu(state: PillMenuState, items: List<PillItem>, @Suppress("UNUSED_PARAMETER") columns: Int = 1) {
    if (!state.shown) return
    Dialog(
        onDismissRequest = state::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // Blur off (battery saver, GrapheneOS): a stronger dim and an opaque pane, as ZoneDialog's.
        val blur = rememberCrossWindowBlur()
        BlurBehind(radiusDp = 24, blur = blur)
        val density = LocalDensity.current
        val lift = remember { Animatable(0f) }
        val pane = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            launch { lift.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 420f)) }
            pane.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 520f))
        }
        var origin by remember { mutableStateOf<Offset?>(null) }
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin = it.positionOnScreen() }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = state::close)
        ) {
            val at = origin ?: return@BoxWithConstraints
            val screenW = constraints.maxWidth.toFloat()
            val screenH = constraints.maxHeight.toFloat()
            val margin = with(density) { 12.dp.toPx() }
            val item = state.bounds.translate(-at)
            val gap = with(density) { 10.dp.toPx() }
            // A tall item (a long message) is not lifted: the pane opens at the finger.
            val lifted = state.picture != null && item.height < screenH * 0.45f && item.width > 0f
            val finger = item.left + state.pressAt.x
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            // Where the pane ended up, written by its layout below and read when it draws (its scale grows
            // from the finger, from the top edge or the bottom one).
            val spot = remember { PaneSpot() }

            if (lifted) {
                val picture = state.picture!!
                Box(
                    Modifier
                        .offset { IntOffset(item.left.toInt(), item.top.toInt()) }
                        .size(with(density) { item.width.toDp() }, with(density) { item.height.toDp() })
                        .graphicsLayer {
                            val s = 1f + 0.035f * lift.value
                            scaleX = s
                            scaleY = s
                        }
                        .drawWithCache { onDrawBehind { drawLayer(picture) } }
                )
            }
            val glass = LocalGlass.current
            // A pane on the screen: Material 3's extra large corner, 28 dp (ShapeDefaults,
            // https://developer.android.com/reference/kotlin/androidx/compose/material3/ShapeDefaults).
            val shape = androidx.compose.material3.ShapeDefaults.ExtraLarge
            // The pane is measured, not computed from a row count: its rows grow with their labels (another
            // language, the 200 % font), so its height and width come from them. Width: at least 260 dp, the
            // widest label's one line width when that is more, never past the screen less its margins; height
            // up to 60 % of the screen, then the list scrolls. A custom Layout places it once measured, as
            // Compose's custom layouts recipe does (https://developer.android.com/develop/ui/compose/layouts/custom).
            Layout(
                content = {
                    Box(
                        Modifier
                            .graphicsLayer {
                                val v = pane.value
                                alpha = v.coerceIn(0f, 1f)
                                scaleX = 0.6f + 0.4f * v
                                scaleY = 0.6f + 0.4f * v
                                transformOrigin = TransformOrigin(spot.originX, spot.originY)
                            }
                            .shadow(10.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
                            .then(if (glass != null) Modifier.dialogGlass(shape, glass, blur) else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, shape))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                            items.forEachIndexed { i, entry ->
                                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                PillRow(entry) {
                                    state.close()
                                    entry.action()
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) { measurables, outer ->
                val box = measurables.first()
                val maxW = (screenW - 2 * margin).toInt().coerceAtLeast(0)
                val minW = PANE_WIDTH.roundToPx().coerceAtMost(maxW)
                val contentW = box.maxIntrinsicWidth(Constraints.Infinity)
                val wideW = contentW.coerceIn(minW, maxW)
                // A small item (an app icon in a grid) is narrower than the wide pane: its pane is sized to its
                // content, 200 to 280 dp, and placed against the item as Launcher3's ArrowPopup does.
                val small = lifted && item.width < wideW
                val paneW = if (small) {
                    contentW.coerceIn(SMALL_MIN.roundToPx().coerceAtMost(maxW), SMALL_MAX.roundToPx().coerceAtMost(maxW))
                } else wideW
                val placeable = box.measure(Constraints(minWidth = paneW, maxWidth = paneW, maxHeight = (screenH * 0.6f).toInt()))
                val paneH = placeable.height.toFloat()
                val below = item.bottom + gap + paneH <= screenH - margin
                val above = item.top - gap - paneH >= margin
                val farX = (screenW - paneW - margin).coerceAtLeast(margin)
                val paneX: Float
                val paneY: Float
                if (small) {
                    val beside = !below && !above
                    paneY = when {
                        below -> item.bottom + gap
                        above -> item.top - gap - paneH
                        else -> ((screenH - paneH) / 2).coerceAtLeast(margin)
                    }
                    paneX = if (!beside) {
                        placeAboutItem(item.left, item.right, paneW.toFloat(), screenW, margin, rtl).coerceIn(margin, farX)
                    } else {
                        // Neither above nor below fits: beside the item, on its right in LTR (left in RTL),
                        // else on the other side, else centred.
                        val rightSide = item.right + gap
                        val leftSide = item.left - gap - paneW
                        val rightFits = rightSide + paneW <= screenW - margin
                        val leftFits = leftSide >= margin
                        when {
                            !rtl && rightFits -> rightSide
                            rtl && leftFits -> leftSide
                            leftFits -> leftSide
                            rightFits -> rightSide
                            else -> (screenW - paneW) / 2f
                        }.coerceIn(margin, farX)
                    }
                } else {
                    paneY = when {
                        !lifted -> (item.top + state.pressAt.y - paneH / 2).coerceIn(margin, (screenH - paneH - margin).coerceAtLeast(margin))
                        below -> item.bottom + gap
                        above -> item.top - gap - paneH
                        else -> (screenH - paneH - margin)
                    }
                    paneX = (finger - paneW / 2).coerceIn(margin, farX)
                }
                val anchorX = if (small) (item.left + item.right) / 2f else finger
                spot.originY = if (small && !below && !above) 0.5f else if (!lifted || below || !above) 0f else 1f
                spot.originX = ((anchorX - paneX) / paneW).coerceIn(0f, 1f)
                layout(outer.maxWidth, outer.maxHeight) { placeable.place(paneX.toInt(), paneY.toInt()) }
            }
        }
    }
}

/** Where the pane's scale grows from, set by its layout and read by its graphics layer when it draws. */
private class PaneSpot {
    var originX = 0.5f
    var originY = 0f
}

/**
 * The pane's left edge for a small item, as Launcher3's ArrowPopup.orientAboutObject chooses it
 * (https://github.com/LineageOS/android_packages_apps_Trebuchet/blob/lineage-22.2/src/com/android/launcher3/popup/ArrowPopup.java):
 * the item's left edge if the pane fits from there (right edge first in RTL), else the other edge, else centred.
 */
private fun placeAboutItem(left: Float, right: Float, paneW: Float, screenW: Float, margin: Float, rtl: Boolean): Float {
    val leftAligned = left
    val rightAligned = right - paneW
    val canLeft = leftAligned + paneW <= screenW - margin
    val canRight = rightAligned >= margin
    return when {
        !rtl && canLeft -> leftAligned
        rtl && canRight -> rightAligned
        !rtl && canRight -> rightAligned
        rtl && canLeft -> leftAligned
        else -> (screenW - paneW) / 2f
    }
}

/** An item's motion: plays it, then runs the action once. */
@Stable
private class PillPlay(private val scope: kotlinx.coroutines.CoroutineScope) {
    val drop = Animatable(0f)
    val turn = Animatable(0f)
    private var busy = false

    fun run(motion: PillMotion, onDone: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            when (motion) {
                PillMotion.DROP -> drop.animateTo(1f, tween(180))
                PillMotion.WIGGLE -> {
                    turn.animateTo(-20f, tween(70))
                    turn.animateTo(20f, tween(110))
                    turn.animateTo(0f, tween(70))
                }
                PillMotion.BOUNCE -> {
                    drop.animateTo(0.6f, tween(110))
                    drop.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
                }
            }
            onDone()
        }
    }
}

/** A row of the pane: the action's name, its icon at the end; its motion plays, then it runs. */
@Composable
private fun PillRow(item: PillItem, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val play = remember { PillPlay(scope) }
    val ink = if (item.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    // The press: Android 12's sparkle ripple clipped to the row's own shape, Material 3's Large (16 dp, the
    // rule's shape for what sits inside a pane), 6 dp in from the pane's edges as iOS 26's highlight is.
    val pressed = rememberSparklePress(ShapeDefaults.Large, horizontal = 6.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // 50 dp at least, growing with the label: it wraps between words (a longer language, the 200 %
            // font), and a single word too wide for the pane shrinks last (TextAutoSize.StepBased,
            // https://developer.android.com/develop/ui/compose/text/autosize).
            .heightIn(min = ROW_HEIGHT)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = pressed) { play.run(item.motion, onDone) }
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        val style = MaterialTheme.typography.bodyLarge
        BasicText(
            item.label,
            style = style.copy(color = ink),
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
            modifier = Modifier.weight(1f)
        )
        Icon(
            item.icon,
            contentDescription = null,
            tint = if (item.danger) ink else item.tint ?: ink,
            modifier = Modifier.size(22.dp).graphicsLayer {
                translationY = play.drop.value * 10.dp.toPx()
                rotationZ = play.turn.value
                if (item.motion == PillMotion.DROP) alpha = 1f - play.drop.value * 0.8f
            }
        )
    }
}

private val PANE_WIDTH = 260.dp
private val SMALL_MIN = 200.dp
private val SMALL_MAX = 280.dp
private val ROW_HEIGHT = 50.dp
