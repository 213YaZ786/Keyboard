// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictation

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardTheme.Companion.STYLE_GLASS
import helium314.keyboard.keyboard.internal.GlassKeyDrawable
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.dpToPx

/**
 * Keyboard: what the bar shows when the mic is tapped and nobody can listen (no Voice, no on-device recognizer): a plain
 * line and one button that opens Store, where Voice is installed from, instead of a bare jump to Store. The view is given
 * to the bar as HeliBoard gives it an inline autofill view, SuggestionStripView.setExternalSuggestionView(view, true)
 * (with its close button), as LatinIME.onInlineSuggestionsResponse does
 * https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/latin/LatinIME.java
 * Layout: a horizontal LinearLayout, the line taking the room the button leaves (weight 1), both centred
 * https://developer.android.com/reference/android/widget/LinearLayout
 * The button is a TextView with a glass capsule behind it (GlassKeyDrawable.forStripButton, the Kind.FUNCTIONAL look
 * of the mic and the tools, in Shape.PILL), its height a minimum (View.setMinimumHeight) so a larger font or a longer translation grows
 * it; no Material control.
 */
class VoiceMissingView(context: Context, private val onInstall: () -> Unit) : LinearLayout(context) {

    init {
        val colors = Settings.getValues().mColors
        val textColor = colors.get(ColorType.KEY_TEXT)
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val line = TextView(context, null, R.attr.suggestionWordStyle).apply {
            setText(R.string.dictation_voice_missing)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        addView(line)

        val button = TextView(context, null, R.attr.suggestionWordStyle).apply {
            setText(R.string.dictation_voice_install)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            // The capsule is drawn inside the view with the bar's padding on every side (GlassKeyDrawable Shape.PILL, 4 dp,
            // KeyboardKit 9.9.1 toolbar items as forAutocorrect), so its height is the round button's size plus that padding
            // twice, and the words keep 12 dp from the capsule's edge
            val inset = GlassKeyDrawable.STRIP_PADDING_DP.toInt()
            minimumHeight = ResourceUtils.getStripButtonSize(resources) + 2 * inset.dpToPx(resources)
            setPadding((12 + inset).dpToPx(resources), 0, (12 + inset).dpToPx(resources), 0)
            layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isClickable = true
            isFocusable = true
            // Glass: the bar's round button; the other styles: HeliBoard's functional key colour on the view, as its strip does
            // https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt
            // Glass: the same Kind.FUNCTIONAL glass as the round buttons, as a capsule (Shape.PILL; the radius is capped at half the height)
            if (colors.themeStyle == STYLE_GLASS) background = GlassKeyDrawable.forStripButton(context, colors, this).also {
                it.shape = GlassKeyDrawable.Shape.PILL
                it.pillRadiusDp = 100f
            }
            else colors.setBackground(this, ColorType.FUNCTIONAL_KEY_BACKGROUND)
            setOnClickListener { v: View ->
                AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, v, HapticEvent.KEY_PRESS)
                onInstall()
            }
        }
        addView(button)
    }
}
