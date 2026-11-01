package helium314.keyboard.latin.ui.component

import androidx.compose.foundation.background
import androidx.compose.material3.IconButton
import androidx.compose.foundation.shape.CircleShape
import helium314.keyboard.latin.ui.glass.glassZone
import helium314.keyboard.latin.ui.glass.LocalGlass
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Buttons that stand out: a two point edge in the primary colour around
 * every action a screen offers, so an action reads as something to press and
 * not as more text. Dialog buttons and the icons of a top bar keep Material's
 * plain style, where an edge on everything would leave nothing louder.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
val BoldEdge: Dp = 2.dp

/**
 * The space between buttons side by side. Glass shadows eat into the gap, so
 * Material's 8 dp reads as touching; iOS alerts keep their buttons well apart.
 */
val ButtonGap: Dp = 12.dp

@Composable
fun boldBorder(enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.primary): BorderStroke =
    BorderStroke(BoldEdge, if (enabled) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

/**
 * [filled] keeps a tonal fill inside the edge, for the one main action of a place.
 * [shape] is Material's own `shape` parameter of OutlinedButton
 * (androidx/androidx compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Button.kt),
 * carried through our glass: a button inside a card takes ShapeDefaults.Large.
 */
@Composable
fun BoldButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    shape: Shape = ButtonDefaults.outlinedShape,
    content: @Composable RowScope.() -> Unit
) {
    // In glass, a pane of glass with its label in the accent and no edge;
    // the main action of a place keeps a wash of the accent inside.
    val glass = LocalGlass.current
    if (glass != null) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier
                .glassZone(shape, glass, lens = 1f)
                .then(if (filled && enabled) Modifier.background(glass.accentTint.copy(alpha = 0.45f), shape) else Modifier),
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = if (filled) (if (glass.dark) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimaryContainer) else MaterialTheme.colorScheme.primary
            ),
            border = null,
            content = content
        )
        return
    }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = if (filled) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.outlinedButtonColors(),
        border = boldBorder(enabled),
        content = content
    )
}

/**
 * A quiet action beside a louder one, Skip or Back next to Next: a text
 * button, and in glass a small pane of glass like every other button, so no
 * control floats bare over the ambient light. [shape] is Material's own
 * `shape` parameter of TextButton (androidx/androidx, material3 Button.kt).
 */
@Composable
fun QuietButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    content: @Composable RowScope.() -> Unit
) {
    val glass = LocalGlass.current
    TextButton(
        onClick = onClick,
        modifier = if (glass == null) modifier else modifier.glassZone(shape, glass, lens = 1f),
        enabled = enabled,
        shape = shape,
        contentPadding = if (glass == null) ButtonDefaults.TextButtonContentPadding else ButtonDefaults.ContentPadding,
        content = content
    )
}

/** A tonal icon button with the same edge. [edge] turns to the error colour for a failing state. */
@Composable
fun BoldIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(),
    edge: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit
) {
    // In glass, the ordinary round action is a small pane of glass with its
    // icon in the accent, and no edge. One that signals a problem, with an
    // edge other than the accent, keeps its fill so it is still noticed.
    val glass = LocalGlass.current
    if (glass != null && edge == MaterialTheme.colorScheme.primary) {
        IconButton(
            onClick = onClick,
            modifier = modifier.glassZone(CircleShape, glass, lens = 1f),
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            content = content
        )
        return
    }
    OutlinedIconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        border = boldBorder(enabled, edge),
        content = content
    )
}
