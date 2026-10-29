package helium314.keyboard.latin.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import helium314.keyboard.latin.navigation.LocalReadableInset
import helium314.keyboard.latin.ui.glass.glassSource
import helium314.keyboard.latin.ui.glass.rememberGlassBackdrop
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.ui.glass.LocalGlass
import helium314.keyboard.latin.ui.glass.LocalGlassBackdrop
import helium314.keyboard.latin.ui.glass.glassFloating

/**
 * A pane of glass floating over the list, as the dock does: what scrolls
 * under it shows through, bent near its edges. Needs the list recorded as
 * LocalGlassBackdrop (TabFrame does it); without it, or with glass off, it
 * is an ordinary zone. [accent] washes it with the accent, for a chosen pill.
 */
@Composable
fun FloatingPane(
    shape: Shape,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val look = LocalGlass.current
    val backdrop = LocalGlassBackdrop.current
    if (look == null || backdrop == null) {
        ZoneSurface(shape = shape, modifier = modifier, accent = accent, onClick = onClick, content = content)
        return
    }
    val color = if (accent && !look.dark) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    CompositionLocalProvider(LocalContentColor provides color) {
        Box(
            modifier
                .clip(shape)
                // Frosted like a zone, so the text it carries stays readable
                // over what scrolls under it; the lens still bends the edges.
                .glassFloating(backdrop, shape, look, tint = if (accent) look.accentTint else look.zoneTint, lens = 1.2f)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        ) { content() }
    }
}

/**
 * The top of a screen whose content fills the whole window: no bar, only
 * standalone panes of glass floating over it, a round action at each end.
 * The screen's name does nothing, so it has no pane: plain text on the page,
 * centred between the two actions (the user, 2026-10-28: "text must be
 * centered"; iOS 26's inline title, centred without a background). What
 * scrolls under it fades out first, see [FloatingFrame]. A [center] slot
 * (clickable pills) is centred the same way.
 */
@Composable
fun FloatingTop(
    title: String?,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    center: @Composable (() -> Unit)? = null
) {
    // Under a large title (FloatingFrame's [largeTitle]) the small one fades in as the large one
    // leaves: Material 3's LargeTopAppBar cross-fades its two titles on collapsedFraction,
    // https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/AppBar.kt
    // The large title is the screen's heading for TalkBack, so the small one is hidden from it.
    val collapse = LocalTitleCollapse.current
    Row(
        modifier.fillMaxWidth().heightIn(min = TopActionSize + 16.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Both action slots keep their room, so the name or the centre slot stays centred.
        Box(Modifier.size(TopActionSize), contentAlignment = Alignment.Center) { leading?.invoke() }
        // A gap on each side: a long name ends in "…" before reaching the round actions.
        Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            when {
                center != null -> center()
                title != null -> Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = if (collapse == null) Modifier.semantics { heading() }
                    else Modifier
                        .graphicsLayer { alpha = ((collapse() - 0.5f) * 2f).coerceIn(0f, 1f) }
                        .clearAndSetSemantics {}
                )
            }
        }
        Box(Modifier.size(TopActionSize), contentAlignment = Alignment.Center) { trailing?.invoke() }
    }
}

/** A name on a small pill of glass, for a name that is also a tap target. FloatingTop no longer uses it. */
@Composable
fun TitlePill(title: String) {
    FloatingPane(shape = CircleShape) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}

/** A round pane of glass on its own, for a screen's action at the top. */
@Composable
fun FloatingAction(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Color.Unspecified) {
    val haptics = rememberHaptics()
    FloatingPane(
        shape = CircleShape,
        onClick = {
            haptics.tick()
            onClick()
        },
        modifier = Modifier.size(TopActionSize).semantics { contentDescription = label }
    ) {
        Box(Modifier.size(TopActionSize), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint.takeOrElse { MaterialTheme.colorScheme.primary }, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * A screen drawn on the whole window, top to bottom, with [top] floating
 * over it in glass: the content is recorded so the panes bend it. [content]
 * gets the padding that keeps its first and last lines clear of the panes
 * and of the bottom by [bottom] when scrolled to either end. [overlay]
 * floats over the content too, bending it. What the content has scrolled under
 * the header (the status bar and [top]) fades to transparent, as iOS 26's soft
 * scroll edge, so text never runs under the title: nothing fades at rest, the
 * fade grows with the scroll over the header's height (ScrollView's fading edge
 * strength); the glass panes still bend it whole.
 * Every list inside bounces at its ends, see [ProvideBounce].
 *
 * With a [largeTitle] (off by default) the header gains a large centred title under the
 * top row, and a one-line status under it; the content's scroll collapses it into the top
 * row's small title (see [LargeTitle]). [content]'s padding then follows the header as it
 * shrinks: read it where it is laid out (a Spacer with Modifier.layout, a lazy list's
 * contentPadding), not in composition, or the whole content recomposes on every frame.
 *
 * Every pixel of the screen scrolls the content (the user, 2026-10-29: "jamais de zone morte"): given the
 * content's [scroll] state, a vertical drag that starts on a pane of the header (a round action, a pill)
 * scrolls and flings the content, and collapses the large title, as a drag on the content does; a tap still
 * reaches the pane. This is AppBarLayout's behaviour, where a drag that starts on the app bar, past the touch
 * slop, scrolls with the content and flings with a VelocityTracker (Material Components'
 * https://github.com/material-components/material-components-android/blob/master/lib/java/com/google/android/material/appbar/HeaderBehavior.java),
 * done the Compose way: Modifier.scrollable on the header drives the content's own ScrollableState
 * (https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/Scrollable.kt).
 * [reverseLayout] is the content list's own (a thread laid out from the bottom), so a drag goes the same way
 * as on the list (ScrollableDefaults.reverseDirection). Floating pieces over the content (the [overlay], a
 * [FloatingDock] inside it) take the same drag from [LocalFrameScroll]. Without [scroll] nothing changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingFrame(
    bottom: Dp,
    top: @Composable ColumnScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
    largeTitle: LargeTitle? = null,
    scroll: ScrollableState? = null,
    reverseLayout: Boolean = false,
    content: @Composable (PaddingValues) -> Unit
) {
    val density = LocalDensity.current
    val look = LocalGlass.current
    val backdrop = rememberGlassBackdrop()
    // The header's own height; with a large title, only the part above it (status bar and top row).
    val headerState = remember { mutableStateOf(0.dp) }
    val collapse = if (largeTitle != null) rememberTitleCollapse() else null
    // The header now: the part above the large title, plus the large title as far as it is open.
    val headerNow: () -> Dp = {
        headerState.value + if (collapse == null) 0.dp else with(density) { collapse.heightPx().toDp() }
    }
    // How far the content has scrolled from its top, in px; read only when drawing.
    val scrolled = remember { mutableFloatStateOf(0f) }
    // Taken from Material 3's ExitUntilCollapsedScrollBehavior nestedScrollConnection (onPostScroll
    // reading what the content consumed), keeping only the sum:
    // https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/AppBar.kt
    val scrolledConnection = remember(scrolled) {
        object : NestedScrollConnection {
            // consumed is what the content itself scrolled (a fling's frames arrive here too, as
            // SideEffect scrolls): up is negative. This node sits inside the large title's, so
            // its post scroll sees the content's consumption only, not the title's collapse.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                scrolled.floatValue = (scrolled.floatValue - consumed.y).coerceAtLeast(0f)
                return Offset.Zero
            }
        }
    }
    val edgeStrength: () -> Float = {
        val fade = with(density) { headerNow().toPx() }
        if (fade <= 0f) 0f else (scrolled.floatValue / fade).coerceIn(0f, 1f)
    }
    // A drag on the header or on a floating piece drives the content's own state, as a drag on the
    // content does (see the doc above). Scrollable.kt's ScrollableDefaults.reverseDirection and flingBehavior.
    val dragScroll: Modifier = if (scroll != null) {
        val fling = ScrollableDefaults.flingBehavior()
        Modifier.scrollable(
            state = scroll,
            orientation = Orientation.Vertical,
            reverseDirection = ScrollableDefaults.reverseDirection(LocalLayoutDirection.current, Orientation.Vertical, reverseLayout),
            flingBehavior = fling
        )
    } else Modifier
    Box(
        Modifier.fillMaxSize()
            // Material 3's exitUntilCollapsed: the scroll first collapses the large title, and opens
            // it again only once the content is back at its top. On the frame, not the content, so a
            // drag on the header does it too and drives the soft top edge below.
            .then(if (collapse != null) Modifier.nestedScroll(collapse.behavior.nestedScrollConnection) else Modifier)
            .nestedScroll(scrolledConnection)
    ) {
        val frame = this
        // The fade is outside glassSource: the backdrop records the content whole.
        Box(
            Modifier.fillMaxSize()
                .then(if (headerState.value > 0.dp) Modifier.softTopEdge(headerNow, edgeStrength) else Modifier)
                .then(if (look != null) Modifier.glassSource(backdrop, look) else Modifier)
        ) {
            ProvideBounce {
                if (collapse == null) {
                    content(PaddingValues(top = headerState.value, bottom = bottom))
                } else {
                    content(remember(collapse, bottom) { HeaderPadding(headerNow, bottom) })
                }
            }
        }
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop.takeIf { look != null }) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = LocalReadableInset.current)
                    .then(
                        if (collapse == null) Modifier.onSizeChanged { headerState.value = with(density) { it.height.toDp() } }
                        else Modifier
                    )
                    .then(dragScroll)
            ) {
                Column(
                    if (collapse == null) Modifier
                    else Modifier.onSizeChanged { headerState.value = with(density) { it.height.toDp() } }
                ) {
                    // The content runs under the status bar, the panes start below it.
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    CompositionLocalProvider(LocalTitleCollapse provides collapse?.fraction) { top() }
                }
                if (collapse != null && largeTitle != null) LargeTitleBlock(largeTitle, collapse)
            }
            // What else floats over the content in glass, a message being written.
            CompositionLocalProvider(LocalFrameScroll provides dragScroll) { frame.overlay() }
        }
    }
}

/**
 * The vertical drag of the enclosing [FloatingFrame] (its content's scroll), empty where the frame was
 * given no state. A floating piece over the content (FloatingDock, an overlay's pane) applies it with
 * `.then(LocalFrameScroll.current)` so a drag that starts on it scrolls the content; a piece with drags
 * of its own (a slider, the dialpad) does not.
 */
val LocalFrameScroll = staticCompositionLocalOf<Modifier> { Modifier }

/**
 * A large title centred at the top of a screen, with an optional one-line [status] under it:
 * plain text on the page, no bar and no zone (it does nothing when tapped). Given to
 * [FloatingFrame]; as the content scrolls it shrinks and fades into [FloatingTop]'s small title.
 * Samsung One UI's and iOS's large titles, Material 3's LargeTopAppBar for the mechanics.
 */
@Immutable
class LargeTitle(val title: String, val status: String? = null)

/**
 * How far the large title has collapsed, 0 at rest to 1 once it is the small title; FloatingTop
 * reads it to fade its own title in. Null where the frame has no large title.
 */
val LocalTitleCollapse = staticCompositionLocalOf<(() -> Float)?> { null }

/**
 * Material 3's collapsing large top app bar, without its container: [behavior] is
 * TopAppBarDefaults.exitUntilCollapsedScrollBehavior (its nestedScrollConnection collapses the
 * bar before the content scrolls and opens it again only at the content's top, and settles it
 * half-way on release), [TopAppBarScrollBehavior.state]'s heightOffset runs from 0 (open) to
 * -[expandedPx] (collapsed), collapsedFraction is that over the limit. Source:
 * https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/AppBar.kt
 * (ExitUntilCollapsedScrollBehavior, TopAppBarState.collapsedFraction, LargeTopAppBar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
private class TitleCollapse(val behavior: TopAppBarScrollBehavior, val expandedPx: Float) {
    val fraction: () -> Float = { behavior.state.collapsedFraction }

    /** The large title's height now. */
    fun heightPx(): Float = (expandedPx + behavior.state.heightOffset).coerceIn(0f, expandedPx)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberTitleCollapse(): TitleCollapse {
    val expandedPx = with(LocalDensity.current) { largeTitleHeight().toPx() }
    val state = rememberTopAppBarState(initialHeightOffsetLimit = -expandedPx)
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state)
    // LargeTopAppBar sets the limit from its own height, in its layout; a turn of the screen changes it here.
    SideEffect { if (state.heightOffsetLimit != -expandedPx) state.heightOffsetLimit = -expandedPx }
    return remember(behavior, expandedPx) { TitleCollapse(behavior, expandedPx) }
}

/**
 * The large title's height at rest: the title and its status line with a little room, never more
 * (the user, 2026-10-28: a quarter of the window left an empty band above the content on a phone).
 */
@Composable
private fun largeTitleHeight(): Dp {
    val window = LocalConfiguration.current
    return if (window.screenHeightDp < 480) 72.dp else 96.dp
}

/**
 * The large title and its status, centred in a block that shrinks with the scroll. The block keeps its
 * content's size and slides up as it shrinks; the title scales down and fades out over the first half of
 * the collapse while the small title fades in over the second (LargeTopAppBar's cross-fade).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LargeTitleBlock(spec: LargeTitle, collapse: TitleCollapse) {
    Column(
        Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val full = collapse.expandedPx.roundToInt()
                val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, full))
                val height = collapse.heightPx().roundToInt()
                layout(placeable.width, height) { placeable.place(0, height - full) }
            }
            .graphicsLayer {
                val fraction = collapse.fraction()
                alpha = (1f - fraction * 2f).coerceIn(0f, 1f)
                scaleX = 1f - 0.3f * fraction
                scaleY = scaleX
            }
            .padding(horizontal = 24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            spec.title,
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() }
        )
        if (spec.status != null) {
            Text(
                spec.status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * The padding a frame with a large title gives its content: the top follows the header as it collapses,
 * as Material 3's Scaffold gives its content the top bar's current height.
 * https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Scaffold.kt
 */
private class HeaderPadding(private val top: () -> Dp, private val bottom: Dp) : PaddingValues {
    override fun calculateTopPadding(): Dp = top()
    override fun calculateBottomPadding(): Dp = bottom
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp
    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp
}

/**
 * What has scrolled under the first [height] of this element fades to nothing at the
 * very top and is whole again at [height]: a gradient drawn with BlendMode.DstIn
 * over an offscreen layer. Android's own recipe, FadedEdgeBox:
 * https://github.com/android/snippets/blob/main/compose/snippets/src/main/java/com/example/compose/snippets/graphics/GraphicsModifiersSnippets.kt
 * (region android_compose_graphics_faded_edge_example), with the gradient
 * bounded to [height] instead of the whole element ([height] is read when drawing,
 * so a header that collapses redraws without recomposing).
 *
 * The fade grows with the scroll as ScrollView.getTopFadingEdgeStrength does: 0 at rest, the
 * scroll offset over the fade length while under it, then 1
 * (https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/ScrollView.java).
 * [strength], 0 to 1, is read when drawing; at 0 nothing is erased, so a glow a page draws from
 * its top (HeroGlow) stays whole at rest.
 */
private fun Modifier.softTopEdge(height: () -> Dp, strength: () -> Float): Modifier =
    this
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val s = strength()
            if (s <= 0f) return@drawWithContent
            // Fading under the whole title row, whole again over the last 16 dp of the header,
            // where the first row of the content starts at rest.
            val end = height().toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 1f - s),
                    1f to Color.Black,
                    startY = (end - 16.dp.toPx()).coerceAtLeast(0f),
                    endY = end
                ),
                blendMode = BlendMode.DstIn
            )
        }

/** The round actions at the top of a screen. */
val TopActionSize = 48.dp
