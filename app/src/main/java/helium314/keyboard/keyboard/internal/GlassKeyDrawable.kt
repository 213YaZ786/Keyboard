// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.internal

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
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
import androidx.compose.ui.graphics.toArgb
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
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

    /** The kinds of key the glass tints differently, from the GlassLook's tints (Glass.kt glassLookOf). */
    enum class Kind { LETTER, FUNCTIONAL, ACTION }

    // The key's place in the ground drawn behind it (GlassZoneNode's origin and window), set by place().
    private var originX = 0f
    private var originY = 0f
    private var window = Size.Zero
    private var density = context.resources.displayMetrics.density
    private var pressed = false
    private var shader: Any? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    private val normalTint = when (kind) {
        Kind.LETTER -> look.zoneTint
        Kind.FUNCTIONAL -> look.floatTint
        Kind.ACTION -> look.accentTint
    }

    // Pressed: the tint deepened toward the accent (the action key more, it is already the accent).
    private val pressedTint = when (kind) {
        Kind.ACTION -> look.accentTint.copy(alpha = min(0.9f, look.accentTint.alpha * 1.8f))
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
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = min(RADIUS_DP * density, min(w, h) / 2f)
        val tint = if (pressed) pressedTint else normalTint
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
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
        shadowPaint.setShadowLayer(10f * density, 0f, 3.5f * density, android.graphics.Color.argb(if (look.dark) 110 else 34, 0, 0, 0))
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
        // rimBrush: Brush.verticalGradient(0 to strong, 0.3 to faint, 0.7 to faint, 1 to strong * 0.8)
        // as an android.graphics.LinearGradient (https://developer.android.com/reference/android/graphics/LinearGradient).
        val strongAlpha = if (look.dark) 0.20f else 0.30f
        val strong = Color.White.copy(alpha = strongAlpha).toArgb()
        val faint = Color.White.copy(alpha = 0.03f).toArgb()
        val end = Color.White.copy(alpha = strongAlpha * 0.8f).toArgb()
        val stroke = 1.2f * density
        rimPaint.strokeWidth = stroke
        rimPaint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(strong, faint, faint, end), floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
        val inset = stroke / 2f
        rect.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(rect, radius - inset, radius - inset, rimPaint)
        rimPaint.shader = null
    }

    companion object {
        private const val TAG = "Glass"

        /** The corner radius of HeliBoard's Rounded key, res/drawable/btn_keyboard_key_pressed_rounded_base.xml (10dp). */
        private const val RADIUS_DP = 10f

        /** GlassZoneNode's lens default (glassZone(shape, look, lens = 0.7f)). */
        private const val LENS = 0.7f

        /** Set once the shader failed, for every key (GlassZoneNode.shaderFailed). */
        @Volatile
        private var shaderFailed = false

        /**
         * The glass for the phone's Material You colours, by day or by night.
         * dynamicLightColorScheme / dynamicDarkColorScheme:
         * https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#dynamicLightColorScheme(android.content.Context)
         * then glassLookOf (Modules glass/Glass.kt), as the other apps' glass.
         */
        fun lookFor(context: Context, dark: Boolean): GlassLook =
            glassLookOf(if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context))

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
