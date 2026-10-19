// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.internal

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.toArgb
import helium314.keyboard.latin.ui.glass.GlassLook

/**
 * The keyboard's ground: the look's ground colour with the soft halos of the ambient light.
 *
 * Port of drawGround (Modules glass/Glass.kt, held by the app as latin/ui/glass/Glass.kt) to a View's
 * Drawable: the ground filled, then each halo as a radial gradient (android.graphics.RadialGradient,
 * https://developer.android.com/reference/android/graphics/RadialGradient) from its colour to
 * transparent, centred at (x * width, y * height) with radius radius * width.
 *
 * Keyboard: with [topRadius] above zero (the keyboard frame in the Glass style, as iOS 26's glass sheet) the top
 * corners are rounded and the ground is a path (android.graphics.Path.addRoundRect with radii,
 * https://developer.android.com/reference/android/graphics/Path#addRoundRect(android.graphics.RectF,%20float[],%20android.graphics.Path.Direction)),
 * so the window shows through outside the curves; the rim of the glass runs along the top edge and the curves
 * (GlassKeyDrawable.rimGradient, rimBrush of GlassZone.kt, a stroke of 1.2 dp as drawPane's), and a soft shadow lies
 * above, the key's (GlassKeyDrawable.drawGlass: blur 10 dp, offset 3.5 dp, here upward) when the views above
 * let it draw outside the frame's bounds.
 */
class GlassGroundDrawable(private val look: GlassLook, private val topRadius: Float = 0f, private val density: Float = 1f) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shape = Path()
    private val rim = Path()
    private var gradients: List<RadialGradient> = emptyList()

    // The gradients depend on the bounds only (drawGround's centre and radius).
    override fun onBoundsChange(bounds: Rect) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        gradients = if (width <= 0f || height <= 0f) emptyList() else look.halos.map { halo ->
            RadialGradient(
                bounds.left + halo.x * width, bounds.top + halo.y * height, maxOf(halo.radius * width, 1f),
                halo.color.toArgb(), halo.color.copy(alpha = 0f).toArgb(), Shader.TileMode.CLAMP
            )
        }
        shape.reset()
        rim.reset()
        if (topRadius > 0f && width > 0f && height > 0f) {
            val r = minOf(topRadius, width / 2f, height)
            shape.addRoundRect(RectF(bounds), floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
            // The rim: left flank up, the curve, the top edge, the curve, right flank down, inside the edge by half the stroke.
            val inset = RIM_STROKE_DP * density / 2f
            val l = bounds.left + inset
            val t = bounds.top + inset
            val rr = bounds.right - inset
            val ri = maxOf(r - inset, 0f)
            rim.moveTo(l, t + r)
            rim.arcTo(RectF(l, t, l + 2f * ri, t + 2f * ri), 180f, 90f)
            rim.lineTo(rr - ri, t)
            rim.arcTo(RectF(rr - 2f * ri, t, rr, t + 2f * ri), 270f, 90f)
            rim.lineTo(rr, t + r)
            // Bright at the top edge, faint by the end of the curves (rimGradient's 0 to 0.3 of its height).
            rimPaint.strokeWidth = RIM_STROKE_DP * density
            rimPaint.shader = GlassKeyDrawable.rimGradient(look, r / 0.3f)
        }
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        if (topRadius <= 0f) {
            paint.shader = null
            paint.color = look.ground.toArgb()
            canvas.drawRect(bounds, paint)
            for (gradient in gradients) {
                paint.shader = gradient
                canvas.drawRect(bounds, paint)
            }
            paint.shader = null
            return
        }
        shadowPaint.color = look.ground.toArgb()
        shadowPaint.setShadowLayer(10f * density, 0f, -3.5f * density, android.graphics.Color.argb(if (look.dark) 110 else 34, 0, 0, 0))
        canvas.drawPath(shape, shadowPaint)
        paint.shader = null
        paint.color = look.ground.toArgb()
        canvas.drawPath(shape, paint)
        for (gradient in gradients) {
            paint.shader = gradient
            canvas.drawPath(shape, paint)
        }
        paint.shader = null
        canvas.drawPath(rim, rimPaint)
    }

    // The ground keeps its own colours: HeliBoard's colour filters, tints and alpha are ignored.
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun setAlpha(alpha: Int) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = if (topRadius > 0f) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE

    companion object {
        /** Material 3's extra-large corner (shape scale, https://m3.material.io/styles/shape/corner-radius-scale; androidx ShapeTokens.CornerExtraLarge), in dp. */
        const val TOP_RADIUS_DP = 28f
        private const val RIM_STROKE_DP = GlassKeyDrawable.RIM_STROKE_DP
    }
}
