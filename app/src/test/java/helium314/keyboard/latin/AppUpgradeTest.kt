// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings
import helium314.keyboard.latin.utils.defaultPinnedToolbarPref
import helium314.keyboard.latin.utils.defaultToolbarPref
import helium314.keyboard.latin.utils.getResourceSubtypes
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Source: the pattern of this app's own Robolectric tests (VersionTapsTest: ApplicationProvider, one prefs file per test);
// the ladder under test is HeliBoard's
// https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/latin/AppUpgrade.kt
// Keyboard: our versionCode restarted at 1, so the ladder ran again at each update of ours; these tests keep it from
// rewriting current-format prefs and check the one-time repair of what it wrote
@RunWith(RobolectricTestRunner::class)
class AppUpgradeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.prefs()
    private val enSubtype = getResourceSubtypes(context.resources).first { it.locale().language == "en" }.toSettingsSubtype().toPref()
    private val validAdditional = "de${Separators.SET}KeyboardLayoutSet=MAIN${Separators.KV}qwerty"
    private fun gapKey(i: Int) = createPrefKeyForBooleanSettings(Settings.PREF_KEY_GAP_SCALE_PREFIX, i, 2)

    @BeforeTest fun clearPrefs() {
        prefs.edit().clear().commit()
    }

    private fun seedCurrentFormat() {
        prefs.edit()
            .putInt(Settings.PREF_VERSION_CODE, 2) // a 0.1.2 install
            .putString(Settings.PREF_ENABLED_SUBTYPES, enSubtype)
            .putString(Settings.PREF_SELECTED_SUBTYPE, enSubtype)
            .putString(Settings.PREF_ADDITIONAL_SUBTYPES, Defaults.PREF_ADDITIONAL_SUBTYPES)
            .putString(Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)
            .putString(Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)
            .putString(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, "UNDO,-10006,null")
            .putFloat(gapKey(0), 1.5f)
            .commit()
    }

    private fun others() = prefs.all.filterKeys { it != Settings.PREF_VERSION_CODE && it != Settings.PREF_UPSTREAM_VERSION_CODE }

    @Test fun `a fresh install stores both codes and nothing else`() {
        checkVersionUpgrade(context)
        assertEquals(BuildConfig.VERSION_CODE, prefs.getInt(Settings.PREF_VERSION_CODE, 0))
        assertEquals(UPSTREAM_VERSION_CODE, prefs.getInt(Settings.PREF_UPSTREAM_VERSION_CODE, 0))
        assertTrue(others().isEmpty())
    }

    @Test fun `an install of ours with prefs in the current format keeps every pref`() {
        seedCurrentFormat()
        val before = others()
        checkVersionUpgrade(context)
        assertEquals(before, others())
        assertEquals(BuildConfig.VERSION_CODE, prefs.getInt(Settings.PREF_VERSION_CODE, 0))
        assertEquals(UPSTREAM_VERSION_CODE, prefs.getInt(Settings.PREF_UPSTREAM_VERSION_CODE, 0))
    }

    @Test fun `once the upstream code is stored an update of ours rewrites nothing, not even what the repair removes`() {
        seedCurrentFormat()
        prefs.edit()
            .putInt(Settings.PREF_UPSTREAM_VERSION_CODE, UPSTREAM_VERSION_CODE)
            .putBoolean(Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, true)
            .putBoolean(Settings.PREF_SUGGEST_PUNCTUATION, true)
            .putFloat(gapKey(1), 1.1f)
            .commit()
        val before = others()
        checkVersionUpgrade(context)
        assertEquals(before, others())
        assertEquals(BuildConfig.VERSION_CODE, prefs.getInt(Settings.PREF_VERSION_CODE, 0))
    }

    @Test fun `the repair undoes what the ladder wrote and keeps what is valid`() {
        seedCurrentFormat()
        prefs.edit()
            .putFloat(gapKey(0), 1.75f) // 3901
            .putFloat(gapKey(1), 1.1f)
            .putFloat(gapKey(2), 1.5f) // not what 3901 writes: the user's own
            .putBoolean(Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, true) // 2000
            .putBoolean(Settings.PREF_SUGGEST_PUNCTUATION, true) // 3201
            .putString(Settings.PREF_PINNED_TOOLBAR_KEYS, "VOICE,true;CLIPBOARD,false") // old separators
            .putString(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, "NOTAKEY,1,2") // 2302
            .putString(
                Settings.PREF_ADDITIONAL_SUBTYPES, // 2305 and 2306
                listOf(
                    validAdditional,
                    "fr${Separators.SET}KeyboardLayoutSet=MAIN${Separators.SET}qwertz",
                    "hu${Separators.SET}KeyboardLayoutSet=MAIN${Separators.KV}KeyboardLayoutSet=MAIN,qwerty",
                ).joinToString(Separators.SETS)
            )
            .putString(Settings.PREF_ENABLED_SUBTYPES, enSubtype + Separators.SETS + "en${Separators.SET}qwerty")
            .putString(Settings.PREF_SELECTED_SUBTYPE, "en${Separators.SET}qwerty")
            .commit()
        checkVersionUpgrade(context)

        assertFalse(prefs.contains(gapKey(0)))
        assertFalse(prefs.contains(gapKey(1)))
        assertEquals(1.5f, prefs.getFloat(gapKey(2), 0f))
        assertFalse(prefs.contains(Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY))
        assertFalse(prefs.contains(Settings.PREF_SUGGEST_PUNCTUATION))
        assertFalse(prefs.contains(Settings.PREF_PINNED_TOOLBAR_KEYS))
        assertEquals(defaultToolbarPref, prefs.getString(Settings.PREF_TOOLBAR_KEYS, ""))
        assertFalse(prefs.contains(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES))
        assertEquals(validAdditional, prefs.getString(Settings.PREF_ADDITIONAL_SUBTYPES, ""))
        assertEquals(enSubtype, prefs.getString(Settings.PREF_ENABLED_SUBTYPES, ""))
        assertFalse(prefs.contains(Settings.PREF_SELECTED_SUBTYPE))
        assertEquals(UPSTREAM_VERSION_CODE, prefs.getInt(Settings.PREF_UPSTREAM_VERSION_CODE, 0))
    }

    @Test fun `the repair removes a subtype pref left with nothing valid`() {
        seedCurrentFormat()
        prefs.edit()
            .putString(Settings.PREF_ADDITIONAL_SUBTYPES, "de${Separators.SET}KeyboardLayoutSet=MAIN${Separators.SET}qwerty")
            .putString(Settings.PREF_ENABLED_SUBTYPES, "zz9${Separators.SET}foo")
            .commit()
        checkVersionUpgrade(context)
        assertFalse(prefs.contains(Settings.PREF_ADDITIONAL_SUBTYPES))
        assertFalse(prefs.contains(Settings.PREF_ENABLED_SUBTYPES))
    }

    @Test fun `custom toolbar codes with no code left are removed, kept codes stay`() {
        seedCurrentFormat()
        prefs.edit().putString(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, "UNDO,null,null;REDO,null,null").commit()
        checkVersionUpgrade(context)
        assertFalse(prefs.contains(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES))
        clearPrefs()
        seedCurrentFormat()
        checkVersionUpgrade(context)
        assertEquals("UNDO,-10006,null", prefs.getString(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, ""))
    }

    @Test fun `a HeliBoard code stored without ours runs the ladder from it`() {
        // restored HeliBoard backup: its versionCode, in HeliBoard's numbering, below the first block that touches this pref
        prefs.edit().putInt(Settings.PREF_VERSION_CODE, 3200).putBoolean(Settings.PREF_BIGRAM_PREDICTIONS, false).commit()
        checkVersionUpgrade(context)
        assertTrue(prefs.getBoolean(Settings.PREF_SUGGEST_PUNCTUATION, false)) // 3201 ran
        assertEquals(UPSTREAM_VERSION_CODE, prefs.getInt(Settings.PREF_UPSTREAM_VERSION_CODE, 0))
    }
}
