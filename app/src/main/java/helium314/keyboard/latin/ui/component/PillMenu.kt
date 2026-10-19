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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
        BlurBehind(radiusDp = 24)
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
            val paneW = with(density) { PANE_WIDTH.toPx() }.coerceAtMost(screenW - 2 * margin)
            val paneH = with(density) { (ROW_HEIGHT * items.size).toPx() + 12.dp.toPx() }.coerceAtMost(screenH * 0.6f)
            val gap = with(density) { 10.dp.toPx() }
            // A tall item (a long message) is not lifted: the pane opens at the finger.
            val lifted = state.picture != null && item.height < screenH * 0.45f && item.width > 0f
            val below = item.bottom + gap + paneH <= screenH - margin
            val above = item.top - gap - paneH >= margin
            val paneY = when {
                !lifted -> (item.top + state.pressAt.y - paneH / 2).coerceIn(margin, screenH - paneH - margin)
                below -> item.bottom + gap
                above -> item.top - gap - paneH
                else -> (screenH - paneH - margin)
            }
            val finger = item.left + state.pressAt.x
            val paneX = (finger - paneW / 2).coerceIn(margin, screenW - paneW - margin)
            val fromTop = !lifted || below || !above

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
            val shape = RoundedCornerShape(22.dp)
            Box(
                Modifier
                    .offset { IntOffset(paneX.toInt(), paneY.toInt()) }
                    .width(with(density) { paneW.toDp() })
                    .graphicsLayer {
                        val v = pane.value
                        alpha = v.coerceIn(0f, 1f)
                        scaleX = 0.6f + 0.4f * v
                        scaleY = 0.6f + 0.4f * v
                        transformOrigin = TransformOrigin(((finger - paneX) / paneW).coerceIn(0f, 1f), if (fromTop) 0f else 1f)
                    }
                    .shadow(10.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
                    .then(if (glass != null) Modifier.dialogGlass(shape, glass) else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, shape))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            ) {
                Column(Modifier.heightIn(max = with(density) { paneH.toDp() }).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                    items.forEachIndexed { i, entry ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        PillRow(entry) {
                            state.close()
                            entry.action()
                        }
                    }
                }
            }
        }
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clickable { play.run(item.motion, onDone) }
            .padding(horizontal = 18.dp)
    ) {
        Text(
            item.label,
            style = MaterialTheme.typography.bodyLarge,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
private val ROW_HEIGHT = 50.dp
