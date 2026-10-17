// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.internal

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.ui.glass.GlassLook
import helium314.keyboard.latin.utils.isDarkColor
import kotlin.math.min

/**
 * The long press panel of the Glass style: iOS's action callout, the bubble of the items joined by two curves
 * to the pressed key, in the same glass as GlassCalloutDrawable (whose fill, shadow and rim it uses).
 *
 * Geometry ported from KeyboardKit 9.9.1 (MIT), Sources/KeyboardKit/Callouts:
 * Callouts+ActionCallout.swift (calloutBubble, calloutButton, the bubble overlapping nothing, the body hidden on a pad),
 * Callouts+ActionCalloutButtonArea.swift (the body and LeadingCurve, TrailingCurve),
 * CustomRoundedRectangle.swift, Callouts+CalloutStyle.swift (cornerRadius 10, curveSize 8x15),
 * https://github.com/KeyboardKit/KeyboardKit/tree/9.9.1/Sources/KeyboardKit/Callouts. Points are dp.
 * The drawable is the background of PopupKeysKeyboardView: its bounds are the bubble and, under it, the body.
 */
class GlassActionCalloutDrawable(look: GlassLook, context: Context) : GlassCalloutDrawable(look, context) {

    /**
     * Builds the outline in the view's own pixels: the bubble is [0, viewW] x [0, bubbleH], the body (the pressed key)
     * lies under it from x = [bodyLeft] over [bodyW] x [bodyH]. Without [bodyVisible] (a pad: ButtonArea.opacity(0))
     * only the bubble is drawn. Each side joins the body to the bubble with LeadingCurve when the bubble ends
     * within a curve of the body (ActionCallout: the bubble starts at the key, or ends at it), else with
     * TrailingCurve, the fillet of a body under the middle of the bubble (the keyboard's edge shifted the panel).
     */
    fun layoutAction(viewW: Float, bubbleH: Float, bodyLeft: Float, bodyW: Float, bodyH: Float, bodyVisible: Boolean) {
        val corner = CORNER_RADIUS_DP * density
        val curveW = CURVE_WIDTH_DP * density
        keyRadius = min(GlassKeyDrawable.RADIUS_DP * density, min(bodyW, bodyH) / 2f)
        path.rewind()
        // calloutBubble: .cornerRadius(style.cornerRadius), all four corners.
        rect.set(0f, 0f, viewW, bubbleH)
        addRoundedRect(path, rect, corner, corner, corner, corner)
        if (!bodyVisible) return
        // ButtonArea.buttonBody: CustomRoundedRectangle with only the bottom corners rounded.
        piece.rewind()
        rect.set(bodyLeft, bubbleH, bodyLeft + bodyW, bubbleH + bodyH)
        addRoundedRect(piece, rect, 0f, 0f, keyRadius, keyRadius)
        union(piece)
        val bodyRight = bodyLeft + bodyW
        val slack = density
        addSide(bodyLeft, -1f, bubbleH, bodyLeft <= curveW + slack)
        addSide(bodyRight, 1f, bubbleH, viewW - bodyRight <= curveW + slack)
    }

    private fun addSide(edge: Float, dir: Float, bubbleH: Float, outer: Boolean) {
        piece.rewind()
        if (outer) addLeadingCurve(piece, edge, dir, bubbleH) else addTrailingCurve(piece, edge, dir, bubbleH)
        union(piece)
    }

    // ButtonArea.LeadingCurve.path in a frame of curveSize (w x h) whose top is the bubble's bottom [fy] and whose
    // foot is the body's edge: from (w + 10, 0) to (0, -20), down to (0, -10), a cubic with controls (0, 5) and (w, 0) to
    // (w, h + 10), and back. [dir] is -1 for the curve left of the body, +1 for its mirror right of it
    // (the whole button area is turned about the vertical axis when the key is not on the leading side).
    private fun addLeadingCurve(into: Path, edge: Float, dir: Float, fy: Float) {
        val w = CURVE_WIDTH_DP * density
        val h = CURVE_HEIGHT_DP * density
        val d = density
        fun x(local: Float) = edge + dir * (w - local)
        into.moveTo(x(w + 10f * d), fy)
        into.lineTo(x(0f), fy - 20f * d)
        into.lineTo(x(0f), fy - 10f * d)
        into.cubicTo(x(0f), fy + 5f * d, x(w), fy, x(w), fy + h + 10f * d)
        into.lineTo(x(w + 10f * d), fy)
        into.close()
    }

    // ButtonArea.TrailingCurve.path: from (0, 0) to (h, 0) (the swift code uses rect.maxY as x), a cubic with
    // controls (0, 0) and (0, h) to (0, h), and back; [dir] is +1 right of the body, -1 for the mirror left of it.
    private fun addTrailingCurve(into: Path, edge: Float, dir: Float, fy: Float) {
        val h = CURVE_HEIGHT_DP * density
        fun x(local: Float) = edge + dir * local
        into.moveTo(x(0f), fy)
        into.lineTo(x(h), fy)
        into.cubicTo(x(0f), fy, x(0f), fy + h, x(0f), fy + h)
        into.lineTo(x(0f), fy)
        into.close()
    }
}

/**
 * One item of the long press panel: nothing while it is not under the finger; under it, ActionCallout's selected
 * look, a filled rounded rectangle of cornerRadius 10 (calloutBubble: .background(selectedBackgroundColor)
 * .cornerRadius(style.cornerRadius)), as high as the item without its 6 dp of padding (actionItemPadding 0 x 6).
 * The colour is the dynamic scheme's primary (iOS: selectedBackgroundColor blue), the label onPrimary.
 * State handling as GlassKeyDrawable (Drawable.onStateChange,
 * https://developer.android.com/reference/android/graphics/drawable/Drawable#onStateChange(int[])).
 */
class GlassCalloutItemDrawable(private val primary: Int, context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary }
    private val rect = RectF()
    private var pressed = false

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val now = state.contains(android.R.attr.state_pressed)
        if (now == pressed) return false
        pressed = now
        return true
    }

    override fun draw(canvas: Canvas) {
        if (!pressed || bounds.isEmpty) return
        val inset = ITEM_PADDING_DP * density
        rect.set(bounds.left.toFloat(), bounds.top + inset, bounds.right.toFloat(), bounds.bottom - inset)
        val radius = min(GlassCalloutDrawable.CORNER_RADIUS_DP * density, min(rect.width(), rect.height()) / 2f)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    // The colours are the scheme's own: HeliBoard's colour code does not repaint it.
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun setAlpha(alpha: Int) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** CalloutStyle.actionItemPadding: 0 x 6. */
        const val ITEM_PADDING_DP = 6f

        /** CalloutStyle.actionItemMaxSize: 50 x 50. */
        const val ITEM_MAX_DP = 50f

        /** CalloutStyle.actionItemFont: title3, 20 pt on iOS. */
        const val TEXT_SIZE_SP = 20f

        /** ActionCallout.itemSizeWidthScale for a single action. */
        const val SINGLE_WIDTH_SCALE = 1.4f

        /** The selected item for the keyboard's colours; dynamicLightColorScheme / dynamicDarkColorScheme as GlassKeyDrawable.lookFor. */
        @JvmStatic
        fun forColors(context: Context, colors: Colors): GlassCalloutItemDrawable =
            GlassCalloutItemDrawable(scheme(context, colors).first, context)

        /** The label of the selected item (CalloutStyle.selectedForegroundColor, white on iOS): the scheme's onPrimary. */
        @JvmStatic
        fun selectedTextColor(context: Context, colors: Colors): Int = scheme(context, colors).second

        private fun scheme(context: Context, colors: Colors): Pair<Int, Int> {
            val dark = isDarkColor(colors.get(ColorType.MAIN_BACKGROUND))
            val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            return Pair(scheme.primary.toArgb(), scheme.onPrimary.toArgb())
        }
    }
}
