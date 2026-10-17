package helium314.keyboard.latin.ui.glass

import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.foundation.background
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance

/*
 * Liquid glass over Material You.
 *
 * Material You gives the colour, the glass gives the matter. Every zone of
 * the app, and the few controls floating above it, is a pane of glass: it
 * bends what lies behind it near its edges (lensing), takes a light tint of
 * the theme, and catches the light in a bright hairline, brighter on its top
 * and bottom edges. No drop shadow: the shapes stay those of the app.
 *
 * Behind the zones lies an ambient light: soft halos in the wallpaper's
 * colours over the page's own ground. Because that light is known, a zone
 * computes the part of it that it bends on its own, in a shader, without
 * capturing the screen, so a list of cards costs almost nothing. The floating
 * controls do capture what scrolls under them, see GlassBackdrop.
 *
 * Shared across the apps of this base: edit Modules/shared, then run sync.sh.
 */

/** One halo of the ambient light, placed and sized in fractions of the window width and height. */
@Immutable
class Halo(val x: Float, val y: Float, val radius: Float, val color: Color)

/** The glass for the current theme. Null in LocalGlass when the reader turned glass off. */
@Immutable
class GlassLook(
    val dark: Boolean,
    /** The page's own ground, under the halos. */
    val ground: Color,
    val halos: List<Halo>,
    /** The tint of a zone: enough for text to sit on it comfortably. */
    val zoneTint: Color,
    /** The tint of a floating control: lighter, it carries only icons. */
    val floatTint: Color,
    /** The tint of the drop that marks the chosen tab or the switched on state. */
    val accentTint: Color
)

val LocalGlass = staticCompositionLocalOf<GlassLook?> { null }

/** The glass for [scheme], or null when [enabled] is false. */
@Composable
fun rememberGlassLook(scheme: ColorScheme, enabled: Boolean): GlassLook? = remember(scheme, enabled) {
    if (!enabled) return@remember null
    val dark = scheme.background.luminance() < 0.5f
    GlassLook(
        dark = dark,
        ground = scheme.background,
        halos = listOf(
            Halo(0.15f, 0.18f, 0.55f, scheme.primary.copy(alpha = if (dark) 0.30f else 0.35f)),
            Halo(0.95f, 0.45f, 0.60f, scheme.tertiary.copy(alpha = if (dark) 0.24f else 0.30f)),
            Halo(0.25f, 0.85f, 0.55f, scheme.primaryContainer.copy(alpha = if (dark) 0.55f else 0.80f)),
            Halo(0.80f, 0.95f, 0.40f, scheme.primary.copy(alpha = 0.22f))
        ),
        // Clear enough for the light behind to show through, as polished glass.
        zoneTint = if (dark) scheme.surfaceContainerLow.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.32f),
        floatTint = if (dark) scheme.surfaceContainerLow.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.42f),
        // By night a deep wash of the accent, so what sits on it reads light as all the night's text.
        // By day as clear as the floating glass (0.70 drowned the rim and the bevel: a flat disc,
        // the send button, 2026-10-17), so a chosen or prominent pane stays polished glass.
        accentTint = if (dark) scheme.primary.copy(alpha = 0.30f) else scheme.primaryContainer.copy(alpha = 0.42f)
    )
}

/**
 * The page's ground, with the ambient light when glass is on. [origin] is
 * where this drawing sits in the window and [window] the window's size, so a
 * halo lands in the same place whoever draws it.
 */
fun DrawScope.drawGround(look: GlassLook?, ground: Color, origin: Offset = Offset.Zero, window: Size = size) {
    drawRect(look?.ground ?: ground)
    look ?: return
    if (window.width <= 0f || window.height <= 0f) return
    for (halo in look.halos) {
        val centre = Offset(halo.x * window.width, halo.y * window.height) - origin
        val radius = halo.radius * window.width
        drawRect(Brush.radialGradient(listOf(halo.color, halo.color.copy(alpha = 0f)), centre, radius))
    }
}

/** Paints the whole window's ground: the root of the app. */
fun Modifier.glassGround(look: GlassLook?, ground: Color): Modifier = drawBehind { drawGround(look, ground) }

/**
 * Paints the ground behind this element as it lies at this place of the
 * window, halos included, for an element that must hide what scrolls behind
 * it and still look like the page: the band above a top bar.
 */
fun Modifier.groundHere(look: GlassLook?, ground: Color): Modifier =
    if (look == null) background(ground) else this then GroundHereElement(look)

private data class GroundHereElement(val look: GlassLook) : ModifierNodeElement<GroundHereNode>() {
    override fun create() = GroundHereNode(look)
    override fun update(node: GroundHereNode) { node.look = look; node.invalidateDraw() }
    override fun InspectorInfo.inspectableProperties() { name = "groundHere" }
}

private class GroundHereNode(var look: GlassLook) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var origin = Offset.Zero
    private var window = Size.Zero

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val o = coordinates.positionInRoot()
        val w = coordinates.findRootCoordinates().size.toSize()
        if (o != origin || w != window) { origin = o; window = w; invalidateDraw() }
    }

    override fun ContentDrawScope.draw() {
        drawGround(look, look.ground, origin, window)
        drawContent()
    }
}
