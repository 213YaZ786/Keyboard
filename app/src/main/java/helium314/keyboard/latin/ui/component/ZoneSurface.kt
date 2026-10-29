package helium314.keyboard.latin.ui.component

import helium314.keyboard.latin.ui.glass.LocalGlass
import helium314.keyboard.latin.ui.glass.glassZone
import helium314.keyboard.latin.ui.theme.zone
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A zone of the app: a card, a banner, a section, a row. Tinted by Material
 * You, and made of glass when glass is on (see ui/glass): then the fill, the
 * outline and the elevation give way to the glass, whose rim draws the edge.
 * Every zone goes through here, so the whole app changes at once.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
@Composable
fun ZoneSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.zone,
    border: BorderStroke? = null,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
    /** A chosen option: in glass, a wash of the accent over the glass, as [color] gives without glass. */
    accent: Boolean = false,
    content: @Composable () -> Unit
) {
    val glass = LocalGlass.current
    val look = when {
        glass == null -> modifier
        accent -> modifier.glassZone(shape, glass).background(glass.accentTint, shape)
        else -> modifier.glassZone(shape, glass)
    }
    val fill = if (glass == null) color else Color.Transparent
    val edge = if (glass == null) border else null
    val tonal = if (glass == null) tonalElevation else 0.dp
    val shadow = if (glass == null) shadowElevation else 0.dp
    // A transparent fill has no text colour of its own, and Material would
    // fall back to black: the glass carries the page's text colour instead.
    val text = when {
        glass == null -> contentColorFor(color)
        accent -> readableOn(
            androidx.compose.ui.graphics.Color(androidx.core.graphics.ColorUtils.compositeColors(glass.accentTint.toArgb(), glass.ground.toArgb())),
            MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.onPrimaryContainer
        )
        else -> MaterialTheme.colorScheme.onSurface
    }
    if (onClick == null) {
        Surface(look, shape, fill, text, tonal, shadow, edge, content)
    } else {
        Surface(onClick, look, shape = shape, color = fill, contentColor = text, border = edge, tonalElevation = tonal, shadowElevation = shadow, content = content)
    }
}

/**
 * Of [light] and [dark], the text colour that reads best on [ground]: the higher WCAG contrast ratio,
 * androidx's ColorUtils.calculateContrast
 * (https://github.com/androidx/androidx/blob/androidx-main/core/core/src/main/java/androidx/core/graphics/ColorUtils.java).
 * An accent zone's wash depends on the wallpaper's palette (Material You): a fixed colour per theme
 * left "Name and photo · On" unreadable on some phones by night (the user, 2026-10-28).
 */
internal fun readableOn(ground: Color, light: Color, dark: Color): Color {
    val g = ground.toArgb()
    val a = androidx.core.graphics.ColorUtils.calculateContrast(light.copy(alpha = 1f).toArgb(), g)
    val b = androidx.core.graphics.ColorUtils.calculateContrast(dark.copy(alpha = 1f).toArgb(), g)
    return if (a >= b) light else dark
}
