// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.eggs

import android.content.Context
import android.icu.text.MessageFormat
import android.widget.Toast
import helium314.keyboard.latin.R

/**
 * The seven taps on the version row of About (easter egg 5), counted as Android's Settings counts the taps on the
 * build number: BuildNumberPreferenceController.passwordEntered / onPreferenceTreeClick
 * (https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/src/com/android/settings/deviceinfo/BuildNumberPreferenceController.java):
 * TAPS_TO_BE_A_DEVELOPER = 7, a countdown from 7, a Toast with the steps left while it is under TAPS - 2 (the toast
 * before is cancelled, Toast.cancel), nothing at the last tap but the effect. The text of the toast is its string
 * show_dev_countdown (https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values/strings.xml),
 * an ICU plural formatted with android.icu.text.MessageFormat as the Settings' StringUtil.getIcuPluralsString does.
 * After the seventh tap the count stays at 0: the effect plays once for each visit of the screen, as the Settings
 * count restarts when its screen is shown again.
 */
class VersionTaps(private val context: Context) {
    private var countdown = TAPS_TO_BE_A_DEVELOPER
    private var toast: Toast? = null

    /** One tap. Returns true on the last one, the tap that sets the egg off. */
    fun tap(): Boolean {
        if (countdown <= 0) return false
        countdown--
        if (countdown == 0) {
            toast?.cancel()
            toast = null
            return true
        }
        if (countdown < TAPS_TO_BE_A_DEVELOPER - 2) {
            toast?.cancel()
            toast = Toast.makeText(context, countdownText(context, countdown), Toast.LENGTH_SHORT).also { it.show() }
        }
        return false
    }

    companion object {
        const val TAPS_TO_BE_A_DEVELOPER = 7

        /** StringUtil.getIcuPluralsString of the Settings: MessageFormat.format(pattern, mapOf("count" to n)). */
        fun countdownText(context: Context, left: Int): String =
            MessageFormat.format(context.getString(R.string.egg_taps_countdown), mapOf("count" to left))
    }
}
