// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.internal

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
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
 */
class GlassGroundDrawable(private val look: GlassLook) : Drawable() {
    private val paint = Paint()
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
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        paint.shader = null
        paint.color = look.ground.toArgb()
        canvas.drawRect(bounds, paint)
        for (gradient in gradients) {
            paint.shader = gradient
            canvas.drawRect(bounds, paint)
        }
        paint.shader = null
    }

    // The ground keeps its own colours: HeliBoard's colour filters, tints and alpha are ignored.
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun setAlpha(alpha: Int) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
