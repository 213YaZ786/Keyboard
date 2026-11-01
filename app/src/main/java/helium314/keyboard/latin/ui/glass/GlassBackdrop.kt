package helium314.keyboard.latin.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlin.math.roundToInt

/**
 * What scrolls under the floating controls, for them to bend.
 *
 * The content a control floats over is recorded as it is drawn, with the
 * ambient light behind it, into one layer: [glassSource] on the content,
 * [glassFloating] on the control. A control never records itself: it floats
 * over a sibling, so its source is always the content next to it.
 */
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin = Offset.Zero
    internal var window = Size.Zero
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** The backdrop of the content the current controls float over, if glass is on. */
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/**
 * Records this content, over the ambient light, for the controls floating
 * above it. Redrawn whenever it moves in the window: a tab sliding in moves
 * its list without redrawing it, and the light painted under the list slid
 * along with it while the zones on it showed the light where it really is.
 */
fun Modifier.glassSource(backdrop: GlassBackdrop, look: GlassLook): Modifier =
    this then GlassSourceElement(backdrop, look)

private data class GlassSourceElement(val backdrop: GlassBackdrop, val look: GlassLook) :
    ModifierNodeElement<GlassSourceNode>() {
    override fun create() = GlassSourceNode(backdrop, look)
    override fun update(node: GlassSourceNode) {
        node.backdrop = backdrop; node.look = look; node.invalidateDraw()
    }
    override fun InspectorInfo.inspectableProperties() { name = "glassSource" }
}

private class GlassSourceNode(var backdrop: GlassBackdrop, var look: GlassLook) :
    Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val o = coordinates.positionInRoot()
        val w = coordinates.findRootCoordinates().size.toSize()
        if (o != backdrop.origin || w != backdrop.window) {
            backdrop.origin = o; backdrop.window = w; invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val content = this
        backdrop.layer.record {
            if (backdrop.window.width > 0f) drawGround(look, look.ground, backdrop.origin, backdrop.window)
            content.drawContent()
        }
        drawLayer(backdrop.layer)
    }
}

/**
 * A control floating over [backdrop], in glass: what lies under it, bent
 * near its edges and barely frosted, in [tint], with the rim light. No
 * shadow. Below Android 13 the lens is left out: frosted glass and the rim.
 */
fun Modifier.glassFloating(
    backdrop: GlassBackdrop,
    shape: Shape,
    look: GlassLook,
    tint: Color = look.floatTint,
    lens: Float = 1f
): Modifier = this then GlassFloatingElement(backdrop, shape, look, tint, lens)

private data class GlassFloatingElement(
    val backdrop: GlassBackdrop,
    val shape: Shape,
    val look: GlassLook,
    val tint: Color,
    val lens: Float
) : ModifierNodeElement<GlassFloatingNode>() {
    override fun create() = GlassFloatingNode(backdrop, shape, look, tint, lens)
    override fun update(node: GlassFloatingNode) {
        node.backdrop = backdrop; node.shape = shape; node.look = look; node.tint = tint; node.lens = lens
        node.forget(); node.invalidateDraw()
    }
    override fun InspectorInfo.inspectableProperties() { name = "glassFloating" }
}

private class GlassFloatingNode(
    var backdrop: GlassBackdrop,
    var shape: Shape,
    var look: GlassLook,
    var tint: Color,
    var lens: Float
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    private var position = Offset.Zero
    private var layer: GraphicsLayer? = null
    private var shader: Any? = null
    private var effectKey: Any? = null
    private var shaderFailed = false

    override fun onAttach() {
        layer = requireGraphicsContext().createGraphicsLayer()
    }

    override fun onDetach() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
        forget()
    }

    fun forget() { effectKey = null }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInRoot()
        if (p != position) { position = p; invalidateDraw() }
    }

    override fun ContentDrawScope.draw() {
        val own = layer ?: return drawContent()
        val outline = shape.createOutline(size, layoutDirection, this)
        // Room around the control, so the lens can pull in what lies just
        // outside its edge.
        val margin = (24f * density).roundToInt()
        val area = IntSize(size.width.roundToInt() + 2 * margin, size.height.roundToInt() + 2 * margin)
        own.record(size = area) {
            translate(backdrop.origin.x - position.x + margin, backdrop.origin.y - position.y + margin) {
                drawLayer(backdrop.layer)
            }
        }
        val radius = cornerRadius(outline)
        val key = listOf(area, radius, tint, lens, look.dark, density, look.quiet)
        if (key != effectKey) {
            own.renderEffect = runCatching { effect(area, margin.toFloat(), radius) }
                .onFailure { shaderFailed = true; android.util.Log.w("Glass", "lens failed", it) }
                .getOrNull()
            effectKey = key
        }
        // The zones' soft shadow (GlassZone), so a round action over the white of the ground still shows
        // as glass (the user, 2026-10-28: "on dirait pas du liquid glass"), as iOS 26's buttons keep theirs.
        // Quiet glass has no shadow (Haze's materials, https://github.com/chrisbanes/haze/blob/main/haze-materials/src/commonMain/kotlin/dev/chrisbanes/haze/blur/materials/HazeMaterials.kt;
        // the long press menu's pane, dialogGlass; the user, 2026-11-01).
        if (!look.quiet) drawIntoCanvas { canvas ->
            val paint = androidx.compose.ui.graphics.Paint().apply { color = look.ground }
            paint.asFrameworkPaint().setShadowLayer(10f * density, 0f, 3.5f * density, android.graphics.Color.argb(if (look.dark) 110 else 34, 0, 0, 0))
            canvas.drawOutline(outline, paint)
        }
        clipPath(Path().apply { addOutline(outline) }) {
            translate(-margin.toFloat(), -margin.toFloat()) { drawLayer(own) }
        }
        if (look.quiet) {
            // The blurred layer, one flat tint, the hairline (as dialogGlass).
            drawOutline(outline, tint)
            drawOutline(outline, rimBrush(look, size.height), style = Stroke(width = 1.4f * density))
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || shaderFailed) {
            drawOutline(outline, tint)
            drawOutline(outline, rimBrush(look, size.height), style = Stroke(width = 1.2f * density))
        }
        drawContent()
    }

    private fun ContentDrawScope.effect(area: IntSize, margin: Float, radius: Float): androidx.compose.ui.graphics.RenderEffect {
        if (look.quiet) {
            // Only the blur, at the dialog's BlurBehind radii (Haze's materials, https://github.com/chrisbanes/haze/blob/main/haze-materials/src/commonMain/kotlin/dev/chrisbanes/haze/blur/materials/HazeMaterials.kt;
            // the long press menu's pane, dialogGlass; the user, 2026-11-01): no lens.
            val r = (if (look.dark) 8f else 16f) * density
            return RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP).asComposeRenderEffect()
        }
        val frost = RenderEffect.createBlurEffect(2f * density, 2f * density, Shader.TileMode.CLAMP)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return frost.asComposeRenderEffect()
        // A shader the phone's engine refuses leaves frosted glass, never a crash.
        val s = (shader as? RuntimeShader) ?: (if (shaderFailed) null else runCatching { RuntimeShader(LENS_AGSL) }
            .onFailure { shaderFailed = true; android.util.Log.w("Glass", "lens shader refused", it) }
            .getOrNull())?.also { shader = it } ?: return frost.asComposeRenderEffect()
        s.setFloatUniform("box", area.width - 2 * margin, area.height - 2 * margin)
        s.setFloatUniform("margin", margin)
        s.setFloatUniform("radius", radius)
        s.setFloatUniform("dpr", density)
        s.setFloatUniform("lens", lens)
        s.setFloatUniform("dark", if (look.dark) 1f else 0f)
        s.setColorUniform("tint", tint.toArgb())
        val lensing = RenderEffect.createRuntimeShaderEffect(s, "content")
        return RenderEffect.createChainEffect(lensing, frost).asComposeRenderEffect()
    }
}

private const val LENS_AGSL = """
    uniform shader content;
    uniform float2 box;
    uniform float margin;
    uniform float radius;
    uniform float dpr;
    uniform float lens;
    uniform float dark;
    layout(color) uniform half4 tint;
""" + GLASS_COMMON + """
    half4 main(float2 p) {
        float2 b = box * 0.5;
        float2 centre = float2(margin) + b;
        float2 q = p - centre;
        float d = sdb(q, b, radius);
        if (d > 0.0) return content.eval(p);
        float2 n = normalAt(q, b, radius, 1.5 * dpr);
        float depth = -d;
        float k = 1.0 - clamp(depth / min(min(b.x, b.y), 22.0 * dpr), 0.0, 1.0);
        k = k * k;
        float2 src = centre + q * (1.0 - 0.05 * lens) - n * k * lens * 20.0 * dpr;
        half4 s = content.eval(src);
        half3 c = mix(s.rgb, tint.rgb, tint.a);
        c = lightGlass(c, rimLight(depth, highlightNormal(q, b, float4(radius)), dpr, dark, 0.0, min(b.x, b.y)), dark);
        return half4(c, 1.0);
    }
"""
