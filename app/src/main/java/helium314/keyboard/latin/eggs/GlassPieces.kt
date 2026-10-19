// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.eggs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/**
 * One piece of the effects, drawn as glass: the body see-through, a sheen on its upper half, a thin rim bright at the top
 * and the bottom (the glass rim of GlassKeyDrawable.rimGradient, the software rules' "polished glass") and a point of light.
 * It takes the confetti's place: Konfetti draws a Shape.DrawableShape by setting the drawable's bounds and calling draw
 * (nl.dionsegijn.konfetti.xml DrawShapes.kt, https://github.com/DanielMartinus/Konfetti/blob/v2.0.5/konfetti/xml/src/main/java/nl/dionsegijn/konfetti/xml/DrawShapes.kt),
 * with the drawable's alpha set to the particle's while it fades (applyAlpha), so the colours stay the piece's own
 * (tint = false). Drawable's contract: https://developer.android.com/reference/android/graphics/drawable/Drawable
 *
 * The shape is a path in a box of [UNIT] units wide and [boxHeight] high, scaled to the bounds at each draw (the
 * bounds keep the box's ratio, as the drawable's intrinsic size says).
 * [tint] is the piece's colour, [dark] the night look: the body a little clearer by day.
 */
class GlassPiece(private val path: Path, private val boxHeight: Float, tint: Int, dark: Boolean) : Drawable() {
    private var alpha = 255
    private val bodyAlpha = if (dark) 0.34f else 0.42f
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 0f, boxHeight, intArrayOf(0x8CFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.55f), Shader.TileMode.CLAMP)
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RIM_UNITS
        strokeJoin = Paint.Join.ROUND
        val edge = ColorUtils.setAlphaComponent(tint, 0xD0)
        shader = LinearGradient(0f, 0f, 0f, boxHeight, intArrayOf(0xF2FFFFFF.toInt(), edge, edge, 0x99FFFFFF.toInt()), floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
    }
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(GLINT_X * UNIT, GLINT_Y * boxHeight, GLINT_UNITS, intArrayOf(0xF2FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0) return
        body.alpha = (bodyAlpha * alpha).toInt()
        sheen.alpha = alpha
        rim.alpha = alpha
        glint.alpha = alpha
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        val s = b.width() / UNIT
        canvas.scale(s, s)
        canvas.drawPath(path, body)
        canvas.drawPath(path, sheen)
        canvas.drawPath(path, rim)
        canvas.drawCircle(GLINT_X * UNIT, GLINT_Y * boxHeight, GLINT_UNITS, glint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { this.alpha = alpha }
    override fun getAlpha() = alpha
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = UNIT.toInt()
    override fun getIntrinsicHeight() = boxHeight.toInt()

    companion object {
        const val UNIT = 100f
        private const val RIM_UNITS = 3.2f
        private const val GLINT_X = 0.30f
        private const val GLINT_Y = 0.26f
        private const val GLINT_UNITS = 13f

        /** A disc: the "bead" of the confetti and what stands in for an emoji the phone cannot draw. Path.addCircle. */
        fun disc(tint: Int, dark: Boolean) =
            GlassPiece(Path().apply { addCircle(UNIT / 2, UNIT / 2, UNIT / 2 - RIM_UNITS, Path.Direction.CW) }, UNIT, tint, dark)

        /** A tile with rounded corners, Path.addRoundRect. */
        fun tile(tint: Int, dark: Boolean) =
            GlassPiece(Path().apply { addRoundRect(RectF(8f, 8f, 92f, 92f), 22f, 22f, Path.Direction.CW) }, UNIT, tint, dark)

        /** A capsule twice as wide as high, Path.addRoundRect. */
        fun bar(tint: Int, dark: Boolean) =
            GlassPiece(Path().apply { addRoundRect(RectF(RIM_UNITS, RIM_UNITS, UNIT - RIM_UNITS, 50f - RIM_UNITS), 22f, 22f, Path.Direction.CW) }, 50f, tint, dark)

        /**
         * [text] (a letter, here the yaz) as glass: the outline of its glyph in the phone's font,
         * Paint.getTextPath (https://developer.android.com/reference/android/graphics/Paint#getTextPath(java.lang.String,%20int,%20int,%20float,%20float,%20android.graphics.Path)),
         * set in the middle of the box and made to fill it, Matrix.setRectToRect. Null when the font draws nothing.
         */
        fun glyph(text: String, tint: Int, dark: Boolean): GlassPiece? {
            val outline = Path()
            Paint().apply { textSize = 200f; isFakeBoldText = true }.getTextPath(text, 0, text.length, 0f, 0f, outline)
            val r = RectF()
            outline.computeBounds(r, true)
            if (r.isEmpty) return null
            val margin = 14f
            val fit = RectF(margin, margin, UNIT - margin, UNIT - margin)
            // the glyph keeps its ratio: the box it is fitted to has the same
            val ratio = r.width() / r.height()
            if (ratio > 1f) {
                val h = fit.width() / ratio
                fit.inset(0f, (fit.height() - h) / 2f)
            } else {
                val w = fit.height() * ratio
                fit.inset((fit.width() - w) / 2f, 0f)
            }
            outline.transform(Matrix().apply { setRectToRect(r, fit, Matrix.ScaleToFit.FILL) })
            return GlassPiece(outline, UNIT, tint, dark)
        }
    }
}

/**
 * An emoji of the system's emoji font as a see-through piece with a point of light: the glyph is drawn once into a
 * small bitmap (Canvas.drawText with the phone's own font, never a picture from outside) and the bitmap is drawn at
 * about four fifths of its opacity, the point of light over it. The system draws 🌙 and 🏮 (a crescent and a paper
 * lantern, no being). Same contract as [GlassPiece] with Konfetti.
 */
class GlassEmoji private constructor(private val bitmap: Bitmap) : Drawable() {
    private var alpha = 255
    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0.30f * SIZE, 0.26f * SIZE, 0.13f * SIZE, intArrayOf(0xF2FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    }
    private val from = android.graphics.Rect(0, 0, SIZE, SIZE)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0) return
        image.alpha = alpha * 4 / 5
        glint.alpha = alpha
        canvas.drawBitmap(bitmap, from, b, image)
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        val s = b.width() / SIZE.toFloat()
        canvas.scale(s, s)
        canvas.drawCircle(0.30f * SIZE, 0.26f * SIZE, 0.13f * SIZE, glint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { this.alpha = alpha }
    override fun getAlpha() = alpha
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = SIZE
    override fun getIntrinsicHeight() = SIZE

    companion object {
        private const val SIZE = 96

        /**
         * [emoji] drawn by the phone's font, or null when the font has no glyph for it (Paint.hasGlyph,
         * https://developer.android.com/reference/android/graphics/Paint#hasGlyph(java.lang.String)).
         */
        fun of(emoji: String): GlassEmoji? {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = SIZE * 0.8f; textAlign = Paint.Align.CENTER }
            if (!paint.hasGlyph(emoji)) return null
            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val metrics = paint.fontMetrics
            Canvas(bitmap).drawText(emoji, SIZE / 2f, SIZE / 2f - (metrics.ascent + metrics.descent) / 2f, paint)
            return GlassEmoji(bitmap)
        }
    }
}
