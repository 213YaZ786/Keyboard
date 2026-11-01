// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.internal

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.utils.isDarkColor
import helium314.keyboard.latin.ui.glass.GlassLook
import helium314.keyboard.latin.ui.glass.ZONE_AGSL
import helium314.keyboard.latin.ui.glass.glassLookOf
import helium314.keyboard.latin.ui.glass.setHalos
import kotlin.math.min

/**
 * One key drawn as a pane of liquid glass, for the Glass style.
 *
 * Port of GlassZoneNode (draw, zoneShader) in Modules' glass kit, held by the app as
 * latin/ui/glass/GlassZone.kt, from a Compose draw node to a View's Drawable. The shader is an
 * android.graphics.RuntimeShader set on a Paint
 * (https://developer.android.com/reference/android/graphics/RuntimeShader), the state handling is
 * Drawable.onStateChange (https://developer.android.com/reference/android/graphics/drawable/Drawable#onStateChange(int[])).
 * Below Android 13, or if the shader is refused, the key is a tinted pane with the same rim (rimBrush).
 */
class GlassKeyDrawable(private val look: GlassLook, private val kind: Kind, context: Context) : Drawable() {

    /**
     * The kinds of key the glass tints differently, from the GlassLook's tints (Glass.kt glassLookOf).
     * PANEL is not a key: the pane behind the popup keys and the more suggestions, tinted as the
     * floating glass of the apps (look.floatTint, as FloatingPane in Modules' panes kit).
     */
    enum class Kind { LETTER, FUNCTIONAL, ACTION, PANEL }

    // Keyboard: the bar above the keys draws round buttons and a pill in this drawable; [host] is the view it is the
    // background of, which places it in the keyboard frame at each draw. The pill is the autocorrect item's
    // background of KeyboardKit 9.9.1 (Autocomplete+ToolbarItem.swift standardAutocorrect, corner 4, inside the
    // toolbar's padding of 4: https://github.com/KeyboardKit/KeyboardKit/tree/9.9.1/Sources/KeyboardKit/Autocomplete)
    // CIRCLE, a circle of the smaller side, and [onlyPressed], nothing at rest, are for the emoji keys (FlorisBoard's
    // ime/media/emoji/EmojiPaletteView.kt shows the glyph alone); [cornerDp], a fixed corner for the clipboard cards
    // (FlorisBoard's stylesheet, clipboard-item shape rounded-corner(12dp))
    enum class Shape { KEY, ROUND, PILL, CIRCLE }
    var shape = Shape.KEY
    var pillRadiusDp = 4f
    var onlyPressed = false
    var cornerDp = 0f
    var host: View? = null

    // The key's place in the ground drawn behind it (GlassZoneNode's origin and window), set by place().
    private var originX = 0f
    private var originY = 0f
    private var window = Size.Zero
    private var density = context.resources.displayMetrics.density
    private var pressed = false
    private var shader: Any? = null

    /**
     * Kept in its pressed look without a finger: the mic while it dictates (the stop button). Redrawn on change,
     * Drawable.invalidateSelf https://developer.android.com/reference/android/graphics/drawable/Drawable#invalidateSelf()
     */
    var held = false
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    /**
     * How much the key glows, 0 (at rest) to 1 (its pressed look), set by KeyboardView just before it draws the key
     * and put back to 0 after: the background is one drawable for all the keys of its kind, the light is the key's own.
     * Colour mix: androidx.compose.ui.graphics.lerp (https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/package-summary#lerp(androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Color,kotlin.Float))
     */
    var lit = 0f

    /**
     * False while KeyboardView draws the key over its rest image, which already holds the key's shadow; true otherwise.
     * Like [lit], set by KeyboardView around one draw.
     */
    var drawShadow = true

    /**
     * Keyboard: the glass body of a key in the bounds, the rounded rect [draw] fills (same radius), in the drawable's own
     * coordinates; false when the drawable is not a plain key (another shape, a panel). KeyboardView cuts it out of the rest
     * image. Source: Path.addRoundRect, https://developer.android.com/reference/android/graphics/Path#addRoundRect(float,%20float,%20float,%20float,%20float,%20float,%20android.graphics.Path.Direction)
     */
    fun bodyPath(out: Path): Boolean {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f || shape != Shape.KEY || kind == Kind.PANEL || onlyPressed) return false
        val radius = min((if (cornerDp > 0f) cornerDp else RADIUS_DP) * density, min(w, h) / 2f)
        out.reset()
        out.addRoundRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), radius, radius, Path.Direction.CW)
        return true
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    private val normalTint = when (kind) {
        Kind.LETTER -> look.zoneTint
        Kind.FUNCTIONAL -> look.floatTint
        Kind.ACTION -> look.accentTint
        Kind.PANEL -> look.floatTint
    }

    // Pressed: the tint deepened toward the accent (the action key more, it is already the accent).
    private val pressedTint = when (kind) {
        Kind.ACTION -> look.accentTint.copy(alpha = min(0.9f, look.accentTint.alpha * 1.8f))
        Kind.PANEL -> look.floatTint
        else -> look.accentTint.copy(alpha = min(0.85f, look.accentTint.alpha * 1.6f))
    }

    /**
     * Where this key lies, set by KeyboardView before it draws the key: [x] and [y] are the key's top left
     * in [view]. When [view] sits in the keyboard frame (which carries the ground), the key is placed in
     * that frame, so the light the glass bends is the light of the ground behind it; else in [view].
     * Same idea as GlassZoneNode.onGloballyPositioned (positionInRoot, root size) in Modules' GlassZone.kt,
     * walking the parents as in https://developer.android.com/reference/android/view/View#getLeft().
     */
    fun place(view: View, x: Float, y: Float) {
        density = view.resources.displayMetrics.density
        var dx = 0f
        var dy = 0f
        var v: View? = view
        while (v != null && v.id != R.id.main_keyboard_frame) {
            dx += v.left + v.translationX
            dy += v.top + v.translationY
            v = v.parent as? View
        }
        if (v == null) {
            originX = x; originY = y
            window = Size(view.width.toFloat(), view.height.toFloat())
        } else {
            originX = x + dx; originY = y + dy
            window = Size(v.width.toFloat(), v.height.toFloat())
        }
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val now = state.contains(android.R.attr.state_pressed)
        if (now == pressed) return false
        pressed = now
        return true
    }

    // The glass is not repainted by HeliBoard's colour code: tints, filters and alpha are ignored.
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun setAlpha(alpha: Int) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun draw(canvas: Canvas) {
        var w = bounds.width().toFloat()
        var h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        // Keyboard: a panel is its own window, as place() makes it for a view outside the keyboard frame.
        if (kind == Kind.PANEL) {
            originX = 0f; originY = 0f
            window = Size(w, h)
        }
        // Keyboard: a drawable of the bar above the keys finds its place in the frame from its view
        host?.let { place(it, 0f, 0f) }
        // Keyboard: the glass lies in the bounds at (dx, dy) in w x h, a circle or a pill for the bar above the keys
        var dx = 0f
        var dy = 0f
        var radius = min((if (kind == Kind.PANEL) PANEL_RADIUS_DP else RADIUS_DP) * density, min(w, h) / 2f)
        if (onlyPressed && !pressed) return
        if (cornerDp > 0f) radius = min(cornerDp * density, min(w, h) / 2f)
        if (shape == Shape.CIRCLE) {
            val d = min(w, h)
            dx = (w - d) / 2f; dy = (h - d) / 2f; w = d; h = d
            radius = d / 2f
        } else if (shape == Shape.ROUND) {
            val d = min(min(w, h - 2f * STRIP_PADDING_DP * density), MAX_BUTTON_DP * density)
            if (d <= 0f) return
            dx = (w - d) / 2f; dy = (h - d) / 2f; w = d; h = d
            radius = d / 2f
        } else if (shape == Shape.PILL) {
            val inset = STRIP_PADDING_DP * density
            dx = inset; dy = inset; w -= 2f * inset; h -= 2f * inset
            if (w <= 0f || h <= 0f) return
            radius = min(pillRadiusDp * density, min(w, h) / 2f)
        }
        var tint = if (pressed || held) pressedTint else normalTint
        // Keyboard: easter eggs 4 and 5, KeyLight lights the key by [lit], from its look at rest to its pressed look
        if (lit > 0f && !(pressed || held)) tint = lerp(normalTint, pressedTint, lit)
        val savedX = originX
        val savedY = originY
        originX += dx; originY += dy
        canvas.save()
        canvas.translate(bounds.left.toFloat() + dx, bounds.top.toFloat() + dy)
        rect.set(0f, 0f, w, h)
        var drawn = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !shaderFailed && window.width > 0f) {
            drawn = try {
                drawGlass(canvas, w, h, radius, tint)
                true
            } catch (t: Throwable) {
                // A shader the phone's engine refuses leaves the plain pane for good, never a crash (GlassZoneNode.draw).
                shaderFailed = true
                Log.w(TAG, "glass key shader failed", t)
                false
            }
        }
        if (!drawn) drawPane(canvas, w, h, radius, tint)
        canvas.restore()
        originX = savedX; originY = savedY
    }

    // Port of GlassZoneNode.zoneShader and the shadow in GlassZoneNode.draw (Modules glass/GlassZone.kt).
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawGlass(canvas: Canvas, w: Float, h: Float, radius: Float, tint: Color) {
        val s = (shader as? RuntimeShader) ?: RuntimeShader(ZONE_AGSL).also { shader = it }
        s.setFloatUniform("size", w, h)
        s.setFloatUniform("radii", radius, radius, radius, radius)
        s.setFloatUniform("flanks", 0f)
        s.setFloatUniform("origin", originX, originY)
        s.setFloatUniform("dpr", density)
        s.setFloatUniform("lens", LENS)
        s.setFloatUniform("dark", if (look.dark) 1f else 0f)
        s.setColorUniform("ground", look.ground.toArgb())
        s.setColorUniform("tint", tint.toArgb())
        setHalos(s, look, window)
        // A soft shadow under the key, so it floats over the light (the glass is opaque over it).
        shadowPaint.color = look.ground.toArgb()
        // Keyboard: drawn again over the rest image, a key keeps the ground fill (opaque body) but not a second shadow
        if (drawShadow) shadowPaint.setShadowLayer(10f * density, 0f, 3.5f * density, android.graphics.Color.argb(if (look.dark) 110 else 34, 0, 0, 0))
        else shadowPaint.clearShadowLayer()
        canvas.drawRoundRect(rect, radius, radius, shadowPaint)
        // The shader is set again on each draw: the paint then takes its new uniforms.
        paint.shader = s
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null
    }

    // Port of the fallback in GlassZoneNode.draw: the tint, then rimBrush as a stroke of 1.2 dp (Modules glass/GlassZone.kt).
    private fun drawPane(canvas: Canvas, w: Float, h: Float, radius: Float, tint: Color) {
        paint.shader = null
        paint.color = tint.toArgb()
        canvas.drawRoundRect(rect, radius, radius, paint)
        val stroke = RIM_STROKE_DP * density
        rimPaint.strokeWidth = stroke
        rimPaint.shader = rimGradient(look, h) // Keyboard: the gradient moved to the companion, shared with GlassCalloutDrawable
        val inset = stroke / 2f
        rect.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(rect, radius - inset, radius - inset, rimPaint)
        rimPaint.shader = null
    }

    companion object {
        private const val TAG = "Glass"

        /** The corner radius of HeliBoard's Rounded key, res/drawable/btn_keyboard_key_pressed_rounded_base.xml (10dp). */
        internal const val RADIUS_DP = 10f // Keyboard: internal, GlassCalloutDrawable's key radius

        /** The corner radius of the pane behind popup keys and more suggestions: 16 dp, rounder than a key. */
        private const val PANEL_RADIUS_DP = 16f

        /** GlassZoneNode's lens default (glassZone(shape, look, lens = 0.7f)). */
        internal const val LENS = 0.7f // Keyboard: internal, GlassCalloutDrawable's lens

        /** Set once the shader failed, for every key (GlassZoneNode.shaderFailed). */
        @Volatile
        internal var shaderFailed = false // Keyboard: internal, GlassCalloutDrawable shares it

        internal const val RIM_STROKE_DP = 1.2f

        /**
         * rimBrush (Modules glass/GlassZone.kt, latin/ui/glass/GlassZone.kt in the app) as an
         * android.graphics.LinearGradient (https://developer.android.com/reference/android/graphics/LinearGradient):
         * Brush.verticalGradient(0 to strong, 0.3 to faint, 0.7 to faint, 1 to strong * 0.8) over [h] pixels,
         * every alpha scaled by [strength] (1 for a key, a third for GlassCalloutDrawable's curves).
         */
        internal fun rimGradient(look: GlassLook, h: Float, strength: Float = 1f): LinearGradient {
            val strongAlpha = if (look.dark) 0.20f else 0.30f
            val strong = Color.White.copy(alpha = strongAlpha * strength).toArgb()
            val faint = Color.White.copy(alpha = 0.03f * strength).toArgb()
            val end = Color.White.copy(alpha = strongAlpha * 0.8f * strength).toArgb()
            return LinearGradient(0f, 0f, 0f, h, intArrayOf(strong, faint, faint, end), floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
        }

        /**
         * The glass for the phone's Material You colours, by day or by night.
         * dynamicLightColorScheme / dynamicDarkColorScheme:
         * https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#dynamicLightColorScheme(android.content.Context)
         * then glassLookOf (Modules glass/Glass.kt), as the other apps' glass.
         */
        fun lookFor(context: Context, dark: Boolean): GlassLook =
            glassLookOf(if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context))

        /**
         * The glass pane for the view of popup keys or more suggestions (Kind.PANEL), its own window.
         * The same look as the keys' (lookFor); it becomes View.background
         * (https://developer.android.com/reference/android/view/View#setBackground(android.graphics.drawable.Drawable)).
         */
        fun forPanel(context: Context, dark: Boolean): GlassKeyDrawable =
            GlassKeyDrawable(lookFor(context, dark), Kind.PANEL, context)

        /** ToolbarStyle.padding of KeyboardKit 9.9.1 (Autocomplete+Toolbar.swift): 4. */
        internal const val STRIP_PADDING_DP = 4f

        /** The biggest round button of the bar above the keys, in dp. */
        internal const val MAX_BUTTON_DP = 40f

        /**
         * A round glass button for the bar above the keys, as big as the bar allows (the bar's height less its
         * padding twice, at most [MAX_BUTTON_DP]), the background of [host]; [accent] is a pinned tool.
         * Kind.FUNCTIONAL glass with a radius of half its size, https://developer.android.com/reference/android/view/View#setBackground(android.graphics.drawable.Drawable)
         * [bare]: the icon alone at rest and the round glass only under the finger, the onlyPressed pattern of [forEmoji]
         * (FlorisBoard's ime/media/emoji/EmojiPaletteView.kt, https://github.com/florisboard/florisboard), as Gboard's tools.
         */
        fun forStripButton(context: Context, colors: Colors, host: View, accent: Boolean = false, bare: Boolean = false): GlassKeyDrawable =
            GlassKeyDrawable(lookFor(context, isDarkColor(colors.get(ColorType.MAIN_BACKGROUND))),
                if (accent) Kind.ACTION else Kind.FUNCTIONAL, context).also { it.shape = Shape.ROUND; it.host = host; it.onlyPressed = bare }

        /**
         * The autocorrect word's background in the bar above the keys: Kind.LETTER glass as a rounded rectangle of
         * corner 4 inside the slot (ToolbarItemStyle.standardAutocorrect, backgroundCornerRadius 4).
         */
        fun forAutocorrect(context: Context, colors: Colors, host: View): GlassKeyDrawable =
            GlassKeyDrawable(lookFor(context, isDarkColor(colors.get(ColorType.MAIN_BACKGROUND))), Kind.LETTER, context)
                .also { it.shape = Shape.PILL; it.host = host }

        /**
         * A clipboard card: Kind.LETTER glass with the corner of FlorisBoard's clipboard-item (12 dp), the background of
         * [host] (https://developer.android.com/reference/android/view/View#setBackground(android.graphics.drawable.Drawable)).
         */
        fun forCard(context: Context, colors: Colors, host: View): GlassKeyDrawable =
            GlassKeyDrawable(lookFor(context, isDarkColor(colors.get(ColorType.MAIN_BACKGROUND))), Kind.LETTER, context)
                .also { it.cornerDp = 12f; it.host = host }

        /** The background of an emoji key: nothing at rest, a Kind.LETTER glass circle of the key's smaller side when pressed. */
        fun forEmoji(context: Context, colors: Colors): GlassKeyDrawable =
            GlassKeyDrawable(lookFor(context, isDarkColor(colors.get(ColorType.MAIN_BACKGROUND))), Kind.LETTER, context)
                .also { it.shape = Shape.CIRCLE; it.onlyPressed = true }

        /**
         * The colour of an emoji category's icon in the bare tab row (iOS's and Gboard's: grey icons, the chosen one in
         * the accent): the scheme's primary for the chosen category (ColorScheme.primary,
         * https://developer.android.com/reference/kotlin/androidx/compose/material3/ColorScheme#primary()), else the key
         * text colour at 60 %, Material's medium emphasis (https://m2.material.io/design/color/text-legibility.html#legibility-standards).
         */
        fun tabIconColor(context: Context, colors: Colors, selected: Boolean): Int {
            if (selected) {
                val dark = isDarkColor(colors.get(ColorType.MAIN_BACKGROUND))
                return (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary.toArgb()
            }
            return Color(colors.get(ColorType.EMOJI_CATEGORY)).copy(alpha = 0.6f).toArgb()
        }

        /**
         * The separator between two suggestions: ToolbarSeparatorStyle of KeyboardKit 9.9.1
         * (Autocomplete+ToolbarSeparator.swift): 1 wide, 30 high, the secondary colour at 50 %, here the scheme's
         * onSurface; a GradientDrawable of that size (https://developer.android.com/reference/android/graphics/drawable/GradientDrawable#setSize(int,%20int)).
         */
        fun separator(context: Context, colors: Colors): android.graphics.drawable.GradientDrawable {
            val dark = isDarkColor(colors.get(ColorType.MAIN_BACKGROUND))
            val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            val density = context.resources.displayMetrics.density
            return android.graphics.drawable.GradientDrawable().apply {
                setColor(scheme.onSurface.copy(alpha = 0.5f).toArgb())
                setSize(Math.round(density), Math.round(30f * density))
            }
        }

        /** The glass key for a kind of background of Colors.selectAndColorDrawable. */
        fun forColorType(context: Context, dark: Boolean, color: ColorType): GlassKeyDrawable {
            val kind = when (color) {
                ColorType.FUNCTIONAL_KEY_BACKGROUND -> Kind.FUNCTIONAL
                ColorType.ACTION_KEY_BACKGROUND, ColorType.ACTION_KEY_POPUP_KEYS_BACKGROUND -> Kind.ACTION
                else -> Kind.LETTER // keys, space bar, popup keys, more suggestions words
            }
            return GlassKeyDrawable(lookFor(context, dark), kind, context)
        }
    }
}
