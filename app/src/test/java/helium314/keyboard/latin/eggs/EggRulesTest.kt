// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.eggs

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Source: Robolectric's way of writing a test (https://robolectric.org/writing-a-test/, as this app's InputLogicTest), because
// android.icu needs its framework, not the plain JVM. The dates of Eid are those of the Umm al-Qura calendar, Saudi Arabia's
// official one (https://developer.android.com/reference/android/icu/util/IslamicCalendar, the ICU class the rule uses):
// Eid al-Fitr 1447 on 20 March 2026, Eid al-Adha 1447 on 27 May 2026 (10 Dhu al-Hijjah).
@RunWith(RobolectricTestRunner::class)
class EggRulesTest {
    private fun day(year: Int, month: Int, dayOfMonth: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month - 1, dayOfMonth, 12, 0) }.timeInMillis

    private val ordinary = day(2026, 6, 3)

    @Test fun `the yaz three times in a row`() {
        assertEquals(Egg.YAZ_RAIN, EggRules.detect("ⵣⵣⵣ", ordinary))
        assertEquals(Egg.YAZ_RAIN, EggRules.detect("azul ⵣⵣⵣ", ordinary))
        assertNull(EggRules.detect("ⵣⵣ", ordinary))
        assertNull(EggRules.detect("ⵣ", ordinary))
        // a fourth makes a run of four, no new rain; a letter between breaks the run
        assertNull(EggRules.detect("ⵣⵣⵣⵣ", ordinary))
        assertNull(EggRules.detect("ⵣⵣaⵣ", ordinary))
        assertNull(EggRules.detect("ⵣⵣⵣa", ordinary))
    }

    @Test fun `hello world, any case, any day`() {
        assertEquals(Egg.HELLO_WAVE, EggRules.detect("hello world", ordinary))
        assertEquals(Egg.HELLO_WAVE, EggRules.detect("Oh, HELLO World", ordinary))
        assertEquals(Egg.HELLO_WAVE, EggRules.detect("hello   world!", ordinary))
        assertEquals(Egg.HELLO_WAVE, EggRules.detect("hello world ", ordinary))
        assertNull(EggRules.detect("hello", ordinary))
        assertNull(EggRules.detect("hello worlds", ordinary))
        assertNull(EggRules.detect("othello world", ordinary))
        assertNull(EggRules.detect("hello world and more", ordinary))
        assertNull(EggRules.detect("", ordinary))
        assertNull(EggRules.detect(null, ordinary))
        assertNull(EggRules.detect("   ", ordinary))
    }

    @Test fun `only the last words count, a long text does not matter`() {
        val long = "x".repeat(5000) + " hello world"
        assertEquals(Egg.HELLO_WAVE, EggRules.detect(long.takeLast(EggRules.TEXT_WINDOW), ordinary))
        assertNull(EggRules.detect("hello world " + "x".repeat(40), ordinary))
    }

    @Test fun `Yennayer from 12 to 14 January only`() {
        for (d in 12..14) {
            assertEquals(Egg.YENNAYER, EggRules.detect("Yennayer", day(2027, 1, d)))
            assertEquals(Egg.YENNAYER, EggRules.detect("Aseggas ameggaz!", day(2027, 1, d)))
            assertEquals(Egg.YENNAYER, EggRules.detect("ⵢⴻⵏⵏⴰⵢⴻⵔ", day(2027, 1, d)))
        }
        assertNull(EggRules.detect("Yennayer", day(2027, 1, 11)))
        assertNull(EggRules.detect("Yennayer", day(2027, 1, 15)))
        assertNull(EggRules.detect("Yennayer", day(2027, 2, 13)))
        assertNull(EggRules.detect("Aseggas ameggaz", ordinary))
        assertNull(EggRules.detect("Yennayers", day(2027, 1, 13)))
    }

    @Test fun `the days of Yennayer`() {
        assertTrue(EggRules.isYennayer(day(2026, 1, 12)))
        assertTrue(EggRules.isYennayer(day(2026, 1, 14)))
        assertFalse(EggRules.isYennayer(day(2026, 1, 11)))
        assertFalse(EggRules.isYennayer(day(2026, 1, 15)))
        assertFalse(EggRules.isYennayer(day(2026, 12, 13)))
    }

    @Test fun `Eid al-Fitr 1447, 1 to 3 Shawwal, is 20 to 22 March 2026`() {
        assertFalse(EggRules.isEid(day(2026, 3, 19)))
        for (d in 20..22) assertTrue(EggRules.isEid(day(2026, 3, d)), "March $d")
        assertFalse(EggRules.isEid(day(2026, 3, 23)))
    }

    @Test fun `Eid al-Adha 1447, 10 to 13 Dhu al-Hijjah, is 27 to 30 May 2026`() {
        assertFalse(EggRules.isEid(day(2026, 5, 26))) // the day of Arafah
        for (d in 27..30) assertTrue(EggRules.isEid(day(2026, 5, d)), "May $d")
        assertFalse(EggRules.isEid(day(2026, 5, 31)))
    }

    @Test fun `an ordinary day is no Eid`() {
        assertFalse(EggRules.isEid(ordinary))
        assertFalse(EggRules.isEid(day(2026, 10, 18)))
    }

    @Test fun `Eid Mubarak in Latin and Arabic during Eid only`() {
        val eid = day(2026, 3, 20)
        assertEquals(Egg.EID, EggRules.detect("Eid Mubarak", eid))
        assertEquals(Egg.EID, EggRules.detect("eid  mubarak!", eid))
        assertEquals(Egg.EID, EggRules.detect("عيد مبارك", eid))
        assertEquals(Egg.EID, EggRules.detect("عيد مبارك!", day(2026, 5, 28)))
        assertNull(EggRules.detect("Eid Mubarak", ordinary))
        assertNull(EggRules.detect("عيد مبارك", ordinary))
        assertNull(EggRules.detect("Eid", eid))
    }

    @Test fun `the same text on two days`() {
        assertEquals(Egg.HELLO_WAVE, EggRules.detect("hello world", day(2026, 3, 20)))
        assertEquals(Egg.HELLO_WAVE, EggRules.detect("hello world", day(2027, 1, 13)))
    }
}
