// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.eggs

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Source: Robolectric's ShadowToast (https://robolectric.org/javadoc/latest/org/robolectric/shadows/ShadowToast.html) and the
// count of AOSP Settings' BuildNumberPreferenceController, TAPS_TO_BE_A_DEVELOPER = 7
// (https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/src/com/android/settings/deviceinfo/BuildNumberPreferenceController.java)
@RunWith(RobolectricTestRunner::class)
class VersionTapsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `seven taps set the egg off, the seventh and no other`() {
        val taps = VersionTaps(context)
        for (i in 1..6) assertFalse(taps.tap(), "tap $i")
        assertTrue(taps.tap())
    }

    @Test fun `after the seventh tap nothing more until the screen is shown again`() {
        val taps = VersionTaps(context)
        repeat(7) { taps.tap() }
        repeat(10) { assertFalse(taps.tap()) }
        assertTrue(repeat7(VersionTaps(context)))
    }

    private fun repeat7(taps: VersionTaps): Boolean {
        var last = false
        repeat(7) { last = taps.tap() }
        return last
    }

    @Test fun `the steps left are told from the third tap, as the Settings do under TAPS minus 2`() {
        val taps = VersionTaps(context)
        taps.tap(); taps.tap()
        assertEquals(0, ShadowToast.shownToastCount())
        taps.tap()
        assertEquals("4 steps away", ShadowToast.getTextOfLatestToast())
        taps.tap(); taps.tap()
        assertEquals("2 steps away", ShadowToast.getTextOfLatestToast())
        taps.tap()
        assertEquals("1 step away", ShadowToast.getTextOfLatestToast())
    }
}
