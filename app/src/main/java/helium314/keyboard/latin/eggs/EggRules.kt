// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.eggs

import android.icu.util.IslamicCalendar
import java.util.Calendar
import java.util.Locale
import android.icu.util.Calendar as IcuCalendar

/**
 * The five easter eggs of the keyboard (point 21). The first four are set off by what is typed, the last by the
 * version row of About (VersionTaps).
 */
enum class Egg {
    /** the yaz (U+2D63) typed three times in a row: a rain of glass yaz */
    YAZ_RAIN,
    /** "Yennayer" or "Aseggas ameggaz" on 12 to 14 January: confetti in the Amazigh flag's colours */
    YENNAYER,
    /** "Eid Mubarak" during Eid: crescents and lanterns rising */
    EID,
    /** "hello world": a wave of light across the keys */
    HELLO_WAVE,
    /** the version tapped seven times: every key lights up together */
    ALL_KEYS,
}

/**
 * What the last typed words say and what day it is: which egg, if any, the text before the cursor sets off.
 * No state, nothing read from the field but the text it is given, nothing kept or logged.
 *
 * The idea is iOS Messages' typed-word screen effects ("happy birthday" balloons, "congratulations" confetti,
 * https://support.apple.com/guide/iphone/send-messages-with-effects-iph3f1f8d5c8/ios), the matching is
 * String.endsWith with ignoreCase (https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/ends-with.html).
 */
object EggRules {
    /** The characters of text before the cursor that detect() needs: the longest phrase and the character before it. */
    const val TEXT_WINDOW = 24

    private const val YAZ = "ⵣ" // TIFINAGH LETTER YAZ, the Amazigh symbol

    private val YENNAYER_PHRASES = listOf("yennayer", "aseggas ameggaz", "ⵢⴻⵏⵏⴰⵢⴻⵔ")
    private val EID_PHRASES = listOf("eid mubarak", "عيد مبارك")
    private const val HELLO_PHRASE = "hello world"

    // What may follow the phrase without ending it: "Eid Mubarak!" is still Eid Mubarak.
    private const val TRAILING = "!.,;:?…،؟"

    /**
     * The egg set off by [before] (the text before the cursor, composing word included) on the day [nowMillis], or null.
     * A phrase counts when the text ends with it, whatever the case, with a space, a punctuation sign or nothing after
     * it, and no letter or digit before it. The yaz needs exactly three in a row.
     */
    fun detect(before: CharSequence?, nowMillis: Long): Egg? {
        if (before.isNullOrEmpty()) return null
        val text = before.toString()
        if (text.endsWith(YAZ + YAZ + YAZ) && !text.endsWith(YAZ + YAZ + YAZ + YAZ)) return Egg.YAZ_RAIN
        // the words, lower case, runs of spaces made one, what ends a sentence taken off
        val tail = text.takeLast(TEXT_WINDOW).lowercase(Locale.ROOT).replace(WHITESPACE, " ").trimEnd { it.isWhitespace() || it in TRAILING }
        if (tail.isEmpty()) return null
        if (endsWithPhrase(tail, HELLO_PHRASE)) return Egg.HELLO_WAVE
        if (EID_PHRASES.any { endsWithPhrase(tail, it) } && isEid(nowMillis)) return Egg.EID
        if (YENNAYER_PHRASES.any { endsWithPhrase(tail, it) } && isYennayer(nowMillis)) return Egg.YENNAYER
        return null
    }

    private val WHITESPACE = Regex("\\s+")

    // The phrase ends the text and what comes before it is not part of a longer word ("othello world").
    private fun endsWithPhrase(tail: String, phrase: String): Boolean {
        if (!tail.endsWith(phrase)) return false
        val start = tail.length - phrase.length
        return start == 0 || !Character.isLetterOrDigit(tail[start - 1])
    }

    /**
     * 12 to 14 January, the days of Yennayer, the Amazigh new year, by the device's calendar and time zone.
     * java.util.Calendar (https://developer.android.com/reference/java/util/Calendar).
     */
    fun isYennayer(nowMillis: Long): Boolean {
        val c = Calendar.getInstance().apply { timeInMillis = nowMillis }
        return c.get(Calendar.MONTH) == Calendar.JANUARY && c.get(Calendar.DAY_OF_MONTH) in 12..14
    }

    /**
     * Eid al-Fitr, 1 to 3 Shawwal, or Eid al-Adha, 10 to 13 Dhu al-Hijjah, in the Umm al-Qura calendar:
     * android.icu.util.IslamicCalendar with CalculationType.ISLAMIC_UMALQURA
     * (https://developer.android.com/reference/android/icu/util/IslamicCalendar), whose months count from 0.
     */
    fun isEid(nowMillis: Long): Boolean {
        val c = IslamicCalendar().apply {
            calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
            timeInMillis = nowMillis
        }
        val month = c.get(IcuCalendar.MONTH)
        val day = c.get(IcuCalendar.DAY_OF_MONTH)
        return (month == IslamicCalendar.SHAWWAL && day in 1..3) || (month == IslamicCalendar.DHU_AL_HIJJAH && day in 10..13)
    }
}
