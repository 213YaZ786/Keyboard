package helium314.keyboard.latin.ui.component

// Ported from Gallery by IacobIonut01 (Apache-2.0, https://github.com/IacobIonut01/ReFra):
// app/core/ui/src/commonMain/kotlin/com/dot/gallery/core/presentation/util/MultiSelectExt.kt (mosaicGridDragHandler and
// photoGridDragHandler: detectDragGesturesAfterLongPress on the grid, the item under the finger found in
// LazyGridState.layoutInfo.visibleItemsInfo with the content padding taken off the pointer, the range from the first item to
// the one under the finger selected, and the grid scrolled while the finger is near the top or the bottom) and its use in
// components/MediaGrid.kt (autoScrollSpeed, the 40 dp threshold, scrollBy in a loop). Google Photos' gesture: touch an item,
// slide over the others to choose them, the list scrolls at the edges. Adapted (from Files' feature/browse/SlideSelect.kt) to the
// rows of a LazyColumn and the cells of a LazyVerticalGrid, the items told apart by any key K (a path, a media id): the choice is
// the one before the slide plus the range (so going back leaves what was chosen earlier), the scroll goes on while the finger
// rests at an edge (the item under it is read again after each step), a tick is felt for each item taken, and the slide starts
// from a long press on any item once [startAnywhere], or from a long press on the leading select mark of a row.
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The maths of a slide, apart from the gestures. */
object SlideRange {

    /** The items from [start] to [end], whichever comes first, cut to the list. */
    fun <K> between(order: List<K>, start: Int, end: Int): List<K> {
        if (order.isEmpty()) return emptyList()
        val from = min(start, end).coerceIn(0, order.lastIndex)
        val to = maxOf(start, end).coerceIn(0, order.lastIndex)
        return order.subList(from, to + 1)
    }

    /**
     * What is chosen when the finger that started on [start] is over [current]: what was chosen before the slide, and the range.
     * ReFra subtracts the old range and adds the new one; here the range is added to the choice of the start, so a slide back
     * leaves what was chosen before it.
     */
    fun <K> select(base: Set<K>, order: List<K>, start: Int, current: Int): Set<K> = base + between(order, start, current)

    /**
     * How fast the list scrolls, in pixels a step: up (negative) when the finger is above [top], down when it is below [bottom],
     * more the further it goes (ReFra: the threshold minus the distance), kept to [max].
     */
    fun edgeSpeed(y: Float, top: Float, bottom: Float, gain: Float = GAIN, max: Float = MAX_STEP): Float {
        val up = top - y
        val down = y - bottom
        val speed = when {
            up > 0f && up >= down -> -up * gain
            down > 0f -> down * gain
            else -> 0f
        }
        return speed.coerceIn(-max, max)
    }

    /** ReFra moves the list by the whole distance every 10 ms; a step here lasts a frame, so a fifth of it. */
    const val GAIN = 0.2f
    const val MAX_STEP = 48f
}

/** Where the items of a list are, for a slide: what is under a point, the lines where the scroll begins, how to scroll. */
interface SlideSurface {
    /** The key of the item under [position], in the list's own space (0 is the top of its padding), or null. */
    fun keyAt(position: Offset): Any?

    /** The finger above this line scrolls the list up. */
    val topEdge: Float

    /** The finger below this line scrolls the list down. */
    val bottomEdge: Float

    suspend fun scrollBy(delta: Float)
}

/**
 * The rows of a LazyColumn. [LazyListItemInfo.offset] is in the content's space, which leaves out the top padding
 * (ReFra's comment on LazyGridItemInfo, the same measure), so the pointer is moved by the padding before it is compared.
 */
class ListSurface(private val state: LazyListState, private val edge: Float) : SlideSurface {
    override fun keyAt(position: Offset): Any? {
        val info = state.layoutInfo
        val y = position.y - info.beforeContentPadding
        return info.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.key
    }

    override val topEdge: Float get() = state.layoutInfo.beforeContentPadding + edge
    override val bottomEdge: Float get() = state.layoutInfo.viewportSize.height - state.layoutInfo.afterContentPadding - edge

    override suspend fun scrollBy(delta: Float) {
        state.scrollBy(delta)
    }
}

/** The cells of a LazyVerticalGrid: ReFra's hitKeyAt, with the start padding [padStart] taken off the pointer too. */
class GridSurface(private val state: LazyGridState, private val padStart: Float, private val edge: Float) : SlideSurface {
    override fun keyAt(position: Offset): Any? {
        val info = state.layoutInfo
        val x = position.x - padStart
        val y = position.y - info.beforeContentPadding
        return info.visibleItemsInfo.firstOrNull {
            x >= it.offset.x && x < it.offset.x + it.size.width && y >= it.offset.y && y < it.offset.y + it.size.height
        }?.key
    }

    override val topEdge: Float get() = state.layoutInfo.beforeContentPadding + edge
    override val bottomEdge: Float get() = state.layoutInfo.viewportSize.height - state.layoutInfo.afterContentPadding - edge

    override suspend fun scrollBy(delta: Float) {
        state.scrollBy(delta)
    }
}

/** The start padding of [padding] in pixels, for [GridSurface]. */
fun gridStartPadding(padding: PaddingValues, density: Density, direction: LayoutDirection): Float =
    with(density) { padding.calculateLeftPadding(direction).toPx() }

/** The pixels from an edge where the list begins to scroll (ReFra: 40 dp). */
fun slideEdge(density: Density): Float = with(density) { 40.dp.toPx() }

/**
 * Whether a slide is going on, and whether the finger went down on a select mark. The list does not scroll by itself while a slide
 * goes on, and the tap that ends a slide is not taken as a tap.
 */
@Stable
class SlideSelectState {
    var active by mutableStateOf(false)
        private set

    internal var onHandle = false

    /** The finger is down on an icon that starts a drag of its own: neither a slide nor the row's menu begins. */
    var onDragIcon = false
        internal set
    private var endedAt = 0L
    private var slid = false

    internal fun begin() {
        active = true
        slid = true
    }

    internal fun end(now: Long) {
        active = false
        if (slid) endedAt = now
        slid = false
    }

    /** True for the tap that lifts the finger after a slide, which must not choose or unchoose the item under it. */
    fun swallowTap(now: Long = android.os.SystemClock.uptimeMillis()): Boolean = active || (endedAt != 0L && now - endedAt < SWALLOW_MILLIS)

    private companion object {
        const val SWALLOW_MILLIS = 300L
    }
}

/** The slide of the list below, for the select marks of its rows (null where there is none). */
val LocalSlideSelect = staticCompositionLocalOf<SlideSelectState?> { null }

/**
 * Marks the leading select mark of a row: a long press that starts on it begins a slide even when nothing is chosen yet.
 * Watched on the Initial pass, so the mark's own tap handling does not hide it.
 */
fun Modifier.slideHandle(state: SlideSelectState?): Modifier = if (state == null) this else pointerInput(state) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        state.onHandle = true
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.fastAny { it.pressed })
        } finally {
            state.onHandle = false
        }
    }
}

/**
 * Marks an icon that starts a drag of its own (two panes, a file dragged across) in place of the slide: the same watch on the
 * Initial pass as [slideHandle], on the flag [SlideSelectState.onDragIcon].
 */
fun Modifier.dragMark(state: SlideSelectState?): Modifier = if (state == null) this else pointerInput(state) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        state.onDragIcon = true
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.fastAny { it.pressed })
        } finally {
            state.onDragIcon = false
        }
    }
}

/**
 * The slide itself, on the list or the grid: a long press, then the finger moves over the items and chooses the range from the
 * first to the one under it. [order] is the keys of the items that can be chosen, in the order they are shown (the keys the
 * lazy list gives its items); [selection] what is chosen now; [startAnywhere] is true when a long press on any item starts a
 * slide, otherwise only a long press on a select mark does.
 */
@Composable
fun <K : Any> Modifier.slideSelect(
    surface: SlideSurface,
    state: SlideSelectState,
    order: List<K>,
    selection: Set<K>,
    startAnywhere: Boolean,
    onSelection: (Set<K>) -> Unit
): Modifier {
    val haptics = rememberHaptics()
    val surfaceNow = rememberUpdatedState(surface)
    val orderNow = rememberUpdatedState(order)
    val selectionNow = rememberUpdatedState(selection)
    val anywhereNow = rememberUpdatedState(startAnywhere)
    val onSelectionNow = rememberUpdatedState(onSelection)
    return this.pointerInput(state) {
        coroutineScope {
            var indexed: List<K>? = null
            var index: Map<Any, Int> = emptyMap()
            var startKey: K? = null
            var current = -1
            var base = emptySet<K>()
            var shown = emptySet<K>()
            var last = Offset.Zero
            var speed = 0f
            var scroller: Job? = null

            // The position of a key the lazy list reported, in the order of the items that can be chosen.
            fun indexOf(key: Any?): Int? {
                val order = orderNow.value
                if (order !== indexed) {
                    index = HashMap<Any, Int>(order.size * 2).also { map -> order.forEachIndexed { at, k -> map[k] = at } }
                    indexed = order
                }
                return key?.let { index[it] }
            }

            fun moveTo(position: Offset) {
                val start = startKey?.let { indexOf(it) } ?: return
                val at = indexOf(surfaceNow.value.keyAt(position)) ?: return
                if (at == current) return
                current = at
                val next = SlideRange.select(base, orderNow.value, start, at)
                if (next == shown) return
                val added = (next - shown).size
                shown = next
                onSelectionNow.value(next)
                repeat(min(added, MAX_TICKS)) { haptics.tick() }
            }

            fun finish() {
                scroller?.cancel()
                scroller = null
                speed = 0f
                if (startKey != null) state.end(android.os.SystemClock.uptimeMillis())
                startKey = null
            }

            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    // An icon that drags on its own has the long press.
                    if (state.onDragIcon) return@detectDragGesturesAfterLongPress
                    val hit = surfaceNow.value.keyAt(position)
                    val at = indexOf(hit)
                    if (at == null || !(anywhereNow.value || state.onHandle)) return@detectDragGesturesAfterLongPress
                    val key = orderNow.value[at]
                    startKey = key
                    current = at
                    val before = selectionNow.value
                    base = if (key in before) before else before + key
                    shown = base
                    last = position
                    state.begin()
                    if (base !== before) {
                        haptics.firm()
                        onSelectionNow.value(base)
                    }
                    // The list goes on scrolling while the finger rests at an edge: ReFra's loop, read again after each step.
                    scroller = launch {
                        while (isActive) {
                            delay(STEP_MILLIS)
                            if (speed != 0f) {
                                surfaceNow.value.scrollBy(speed)
                                delay(STEP_MILLIS)
                                moveTo(last)
                            }
                        }
                    }
                },
                onDragCancel = { finish() },
                onDragEnd = { finish() },
                onDrag = { change, _ ->
                    if (startKey != null) {
                        last = change.position
                        val s = surfaceNow.value
                        speed = SlideRange.edgeSpeed(last.y, s.topEdge, s.bottomEdge)
                        moveTo(last)
                    }
                }
            )
        }
    }
}

private const val STEP_MILLIS = 16L
private const val MAX_TICKS = 4
