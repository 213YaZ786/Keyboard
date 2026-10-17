// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.internal

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.ui.glass.GlassLook
import helium314.keyboard.latin.ui.glass.ZONE_AGSL
import helium314.keyboard.latin.ui.glass.setHalos
import helium314.keyboard.latin.utils.isDarkColor
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The key preview of the Glass style: iOS's input callout, the key grows into a bubble joined to it,
 * in the same glass as the keys.
 *
 * Geometry ported from KeyboardKit 9.9.1 (MIT), Sources/KeyboardKit/Callouts:
 * Callouts+InputCallout.swift (sizes, position, the bubble overlapping the body by 1 pt),
 * Callouts+InputCalloutButtonArea.swift (the body and the two Curve shapes),
 * CustomRoundedRectangle.swift (a rectangle with its own radius per corner),
 * Callouts+CalloutStyle.swift (cornerRadius 10, curveSize 8x15, inputItemMinSize height 55,
 * shadow black 10 % radius 5), https://github.com/KeyboardKit/KeyboardKit/tree/9.9.1/Sources/KeyboardKit/Callouts.
 * Points are dp. The outline is one android.graphics.Path
 * (https://developer.android.com/reference/android/graphics/Path), its parts joined with Path.op;
 * the fill is GlassKeyDrawable.drawGlass (same ZONE_AGSL shader and uniforms) and, without
 * RuntimeShader, GlassKeyDrawable.drawPane (the tint, then the rim).
 */
class GlassCalloutDrawable(private val look: GlassLook, context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density

    // The callout's place in the ground behind it (GlassKeyDrawable.place), set by setPlace().
    private var originX = 0f
    private var originY = 0f
    private var window = Size.Zero

    private var shader: Any? = null
    private val path = Path()
    private val piece = Path()
    private val rect = RectF()
    private val box = RectF()
    private var keyRadius = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /**
     * Builds the outline for a key of [keyW] x [keyH] pixels and returns the callout's bounding box in
     * pixels, relative to the key's top left; the path then lies in the box (its top left is 0, 0).
     * [textW] is the label's width (the bubble grows with it, as Text(minWidth:) in InputCallout.calloutBubble),
     * [smallSize] the phone in landscape (InputCallout.shouldEnforceSmallSize: the bubble is as high as the key).
     * [limitLeft] and [limitRight] bound the bubble, relative to the key's left, so it stays on the screen
     * while the body stays over the key (the clamp of KeyPreviewChoreographer.placeKeyPreview).
     */
    fun layout(keyW: Float, keyH: Float, textW: Float, smallSize: Boolean, limitLeft: Float, limitRight: Float): RectF {
        val corner = CORNER_RADIUS_DP * density
        val curveW = CURVE_WIDTH_DP * density
        val curveH = CURVE_HEIGHT_DP * density
        keyRadius = min(GlassKeyDrawable.RADIUS_DP * density, min(keyW, keyH) / 2f)

        // InputCallout.calloutSizeHeight and calloutSizeWidth (inputItemMinSize.width is 0).
        val bubbleH = if (smallSize) keyH else MIN_HEIGHT_DP * density
        val bubbleW = max(keyW + 2f * curveW + corner, textW)
        // InputCallout.positionX: centred on the key; then kept inside the limits.
        var left = keyW / 2f - bubbleW / 2f
        if (left + bubbleW > limitRight) left = limitRight - bubbleW
        if (left < limitLeft) left = limitLeft
        // VStack(bubble.offset(y: 1), button area): the bubble ends 1 pt below the key's top.
        val overlap = density
        val bubbleTop = overlap - bubbleH

        path.rewind()
        // calloutBubble: CustomRoundedRectangle is cornerRadius on the four corners (.cornerRadius(style.cornerRadius)).
        rect.set(left, bubbleTop, left + bubbleW, bubbleTop + bubbleH)
        addRoundedRect(path, rect, corner, corner, corner, corner)
        // ButtonArea.buttonBody: only the bottom corners are rounded, at the key's radius.
        piece.rewind()
        rect.set(0f, 0f, keyW, keyH)
        addRoundedRect(piece, rect, 0f, 0f, keyRadius, keyRadius)
        union(piece)
        // ButtonArea.body: the left curve turned about the vertical axis, the body, the right curve.
        piece.rewind()
        addCurve(piece, 0f, -curveW, curveH)
        union(piece)
        piece.rewind()
        addCurve(piece, keyW, curveW, curveH)
        union(piece)

        // The box on whole pixels, so the view that carries it and the path agree.
        box.set(floor(min(left, -curveW)), floor(bubbleTop), ceil(max(left + bubbleW, keyW + curveW)), ceil(keyH))
        bubble.set(left - box.left, bubbleTop - box.top, left + bubbleW - box.left, bubbleTop + bubbleH - box.top)
        val result = RectF(box)
        path.offset(-box.left, -box.top)
        return result
    }

    /** The bubble in the box (after [layout]), where the label is centred. */
    val bubble = RectF()

    /**
     * Where the callout lies in the keyboard frame (main_keyboard_frame, which carries the ground):
     * [x] and [y] are the box's top left in it, [frameW] and [frameH] the frame's size. As GlassKeyDrawable.place;
     * a zero size makes the callout its own window.
     */
    fun setPlace(x: Float, y: Float, frameW: Float, frameH: Float) {
        originX = x; originY = y
        window = Size(frameW, frameH)
    }

    // The glass is not repainted by HeliBoard's colour code: tints, filters and alpha are ignored.
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun setAlpha(alpha: Int) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    // The order of GlassKeyDrawable.draw: the shadow with the path, the glass, then the rim.
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f || path.isEmpty) return
        val tint = look.floatTint
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        // CalloutStyle.shadowColor (black 10 %) and shadowRadius (5), drawn before the glass; the ground under it
        // so the callout is opaque over the keys beside it.
        shadowPaint.color = look.ground.toArgb()
        shadowPaint.setShadowLayer(SHADOW_RADIUS_DP * density, 0f, 0f, android.graphics.Color.argb(26, 0, 0, 0))
        canvas.drawPath(path, shadowPaint)
        var drawn = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !GlassKeyDrawable.shaderFailed) {
            drawn = try {
                drawGlass(canvas, w, h)
                true
            } catch (t: Throwable) {
                // A shader the phone's engine refuses leaves the plain pane for good, never a crash (GlassKeyDrawable.draw).
                GlassKeyDrawable.shaderFailed = true
                Log.w(TAG, "glass callout shader failed", t)
                false
            }
        }
        if (!drawn) {
            // GlassKeyDrawable.drawPane: the tint, then the rim.
            paint.shader = null
            paint.color = tint.toArgb()
            canvas.drawPath(path, paint)
            stroke(canvas, h, 1f)
        }
        canvas.restore()
    }

    // GlassKeyDrawable.drawGlass: the callout's box is the zone, its corners the bubble's on top and the key's below.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawGlass(canvas: Canvas, w: Float, h: Float) {
        val s = (shader as? RuntimeShader) ?: RuntimeShader(ZONE_AGSL).also { shader = it }
        val corner = min(CORNER_RADIUS_DP * density, min(w, h) / 2f)
        val frame = if (window.width > 0f) window else Size(w, h)
        val origin = if (window.width > 0f) Pair(originX, originY) else Pair(0f, 0f)
        s.setFloatUniform("size", w, h)
        s.setFloatUniform("radii", corner, corner, keyRadius, keyRadius)
        s.setFloatUniform("flanks", 0f)
        s.setFloatUniform("origin", origin.first, origin.second)
        s.setFloatUniform("dpr", density)
        s.setFloatUniform("lens", GlassKeyDrawable.LENS)
        s.setFloatUniform("dark", if (look.dark) 1f else 0f)
        s.setColorUniform("ground", look.ground.toArgb())
        s.setColorUniform("tint", look.floatTint.toArgb())
        setHalos(s, look, frame)
        // The shader is set again on each draw: the paint then takes its new uniforms.
        paint.shader = s
        canvas.drawPath(path, paint)
        paint.shader = null
        // The rim at a third of its strength, so the curves get a soft edge too.
        stroke(canvas, h, 1f / 3f)
    }

    // GlassKeyDrawable.drawPane's rim (rimGradient) as a stroke along the outline.
    private fun stroke(canvas: Canvas, h: Float, strength: Float) {
        rimPaint.strokeWidth = GlassKeyDrawable.RIM_STROKE_DP * density
        rimPaint.shader = GlassKeyDrawable.rimGradient(look, h, strength)
        canvas.drawPath(path, rimPaint)
        rimPaint.shader = null
    }

    // Path.op(UNION): https://developer.android.com/reference/android/graphics/Path#op(android.graphics.Path,%20android.graphics.Path.Op)
    private fun union(other: Path) {
        if (!path.op(other, Path.Op.UNION)) path.addPath(other)
    }

    // CustomRoundedRectangle.path: each radius at most half the height and half the width; the arcs are
    // Path.addRoundRect (https://developer.android.com/reference/android/graphics/Path#addRoundRect(android.graphics.RectF,%20float[],%20android.graphics.Path.Direction)).
    private fun addRoundedRect(into: Path, r: RectF, topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float) {
        val limit = min(r.height() / 2f, r.width() / 2f)
        val tl = min(topLeft, limit)
        val tr = min(topRight, limit)
        val br = min(bottomRight, limit)
        val bl = min(bottomLeft, limit)
        into.addRoundRect(r, floatArrayOf(tl, tl, tr, tr, br, br, bl, bl), Path.Direction.CW)
    }

    // InputCalloutButtonArea.Curve.path: from the foot (x, h) a cubic with both controls at (x, h/2) to the head
    // (x + sign * w, 0), then to (x, 0) and back to the foot. The left curve is the same shape turned 180 degrees
    // about the vertical axis (rotation3DEffect), so its width is negative.
    private fun addCurve(into: Path, x: Float, w: Float, h: Float) {
        into.moveTo(x, h)
        into.cubicTo(x, h / 2f, x, h / 2f, x + w, 0f)
        into.lineTo(x, 0f)
        into.close()
    }

    companion object {
        private const val TAG = "Glass"

        /** CalloutStyle.cornerRadius: 10. */
        private const val CORNER_RADIUS_DP = 10f

        /** CalloutStyle.curveSize: 8 x 15. */
        private const val CURVE_WIDTH_DP = 8f
        private const val CURVE_HEIGHT_DP = 15f

        /** CalloutStyle.inputItemMinSize: height 55. */
        private const val MIN_HEIGHT_DP = 55f

        /** CalloutStyle.shadowRadius: 5. */
        private const val SHADOW_RADIUS_DP = 5f

        /** CalloutStyle.inputItemFont: largeTitle, 34 pt on iOS, light (weight 300). */
        const val TEXT_SIZE_SP = 34f
        const val TEXT_WEIGHT = 300

        /** The glass for the keyboard's colours, the look of its keys (GlassKeyDrawable.forColorType). */
        @JvmStatic
        fun forColors(context: Context, colors: Colors) =
            GlassCalloutDrawable(GlassKeyDrawable.lookFor(context, isDarkColor(colors.get(ColorType.MAIN_BACKGROUND))), context)

        /**
         * InputCallout.isEnabled: phones only. The tablet is told by its smallest width of 600 dp
         * (https://developer.android.com/training/multiscreen/screensizes#TaskUseSWQuali).
         */
        @JvmStatic
        fun isEnabled(context: Context) = context.resources.configuration.smallestScreenWidthDp < 600
    }
}
