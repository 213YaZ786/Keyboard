package helium314.keyboard.latin.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * The fills of zones, taken from the dynamic palette so they carry the
 * wallpaper's colour.
 *
 * Zones used surfaceContainerLow, which Material builds from the neutral
 * palette: on a red wallpaper it came out a pink so pale it could not be told
 * from the page, and on most wallpapers it read as grey. Checked on a tablet
 * emulator with a red seed, the zones looked fixed while the switches and the
 * headings were red. Mixing in primaryContainer keeps the calm of a surface
 * and makes the zone follow the wallpaper visibly. The share is lower in dark
 * themes, where primaryContainer is a strong mid tone.
 *
 * Text stays on onSurface: the mix sits between two tones Material already
 * pairs with it, so the contrast of every label is kept.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */
val ColorScheme.zone: Color
    get() = lerp(surfaceContainerLow, primaryContainer, if (isDark) 0.22f else 0.30f)

/** A zone inside a zone: a quote, a link preview, a document. */
val ColorScheme.innerZone: Color
    get() = lerp(surfaceContainerHigh, secondaryContainer, if (isDark) 0.30f else 0.40f)

private val ColorScheme.isDark: Boolean get() = background.luminance() < 0.5f
