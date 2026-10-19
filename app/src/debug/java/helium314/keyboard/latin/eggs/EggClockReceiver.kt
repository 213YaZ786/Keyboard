// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.eggs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.ZoneId

/**
 * Debug builds only. Sets the day the easter eggs see, to try Yennayer (12 to 14 January) and Eid without changing the
 * device's date (which needs root). Same shape as DictationTestReceiver of this app (debug manifest, only the shell,
 * DUMP, can call it), BroadcastReceiver: https://developer.android.com/reference/android/content/BroadcastReceiver
 *   adb shell am broadcast -n com.yaz.keyboard.debug/helium314.keyboard.latin.eggs.EggClockReceiver --es date 2026-03-20
 *   adb shell am broadcast -n com.yaz.keyboard.debug/helium314.keyboard.latin.eggs.EggClockReceiver --es date off
 * The day stays until "off" or until the process ends. java.time.LocalDate.parse reads yyyy-MM-dd.
 */
class EggClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val date = intent.getStringExtra("date") ?: return
        EggEffects.clockOverride =
            if (date == "off") null
            else runCatching { LocalDate.parse(date).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    }
}
