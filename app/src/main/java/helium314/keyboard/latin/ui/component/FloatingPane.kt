package helium314.keyboard.latin.ui.component

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
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
 * standalone panes of glass floating over it, a round action on each side
 * and the screen's name in a small pill between them. What scrolls passes
 * under them and between them.
 */
@Composable
fun FloatingTop(
    title: String?,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    center: @Composable (() -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Both slots keep their room, so the name stays centred.
        Box(Modifier.size(TopActionSize), contentAlignment = Alignment.Center) { leading?.invoke() }
        // A gap on each side: a long name ends in "…" before reaching the round actions.
        Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            when {
                center != null -> center()
                title != null -> TitlePill(title)
            }
        }
        Box(Modifier.size(TopActionSize), contentAlignment = Alignment.Center) { trailing?.invoke() }
    }
}

/** A screen's name on a small pill of glass. */
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
 * floats over the content too, bending it.
 */
@Composable
fun FloatingFrame(
    bottom: Dp,
    top: @Composable ColumnScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val density = LocalDensity.current
    val look = LocalGlass.current
    val backdrop = rememberGlassBackdrop()
    var header by remember { mutableStateOf(0.dp) }
    Box(Modifier.fillMaxSize()) {
        val frame = this
        Box(Modifier.fillMaxSize().then(if (look != null) Modifier.glassSource(backdrop, look) else Modifier)) {
            content(PaddingValues(top = header, bottom = bottom))
        }
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop.takeIf { look != null }) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = LocalReadableInset.current)
                    .onSizeChanged { header = with(density) { it.height.toDp() } }
            ) {
                // The content runs under the status bar, the panes start below it.
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                top()
            }
            // What else floats over the content in glass, a message being written.
            frame.overlay()
        }
    }
}

/** The round actions at the top of a screen. */
val TopActionSize = 48.dp
