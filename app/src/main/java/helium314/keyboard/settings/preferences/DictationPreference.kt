// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.core.voice.SpeechModel
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.ui.component.GlassSwitch
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.defaultPinnedToolbarPref
import helium314.keyboard.latin.utils.defaultToolbarPref
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.getEnabledToolbarKeys
import helium314.keyboard.latin.utils.getPinnedToolbarKeys
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity

// Keyboard: the Dictation switch of the main page. HeliBoard has no setting for the voice key: it is the toolbar
// key VOICE, shown when it is among the enabled or the pinned toolbar keys (SuggestionStripView.setupGlassSlots).
// The switch turns that key on or off, writing the toolbar keys as ReorderSwitchPreference does (name:state
// entries, then setThemeNeedsReload); it is shown through the same recomposition trigger as SwitchPreference.
// Sources: https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/settings/preferences/ReorderSwitchPreference.kt
// and removePinnedKey in https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/latin/utils/ToolbarUtils.kt
@Composable
fun DictationPreference(name: String, onClickEngine: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val on = ToolbarKey.VOICE in getEnabledToolbarKeys(prefs) || ToolbarKey.VOICE in getPinnedToolbarKeys(prefs)
    fun switched(newValue: Boolean) {
        // off: gone from the toolbar and from the pinned keys; on: back among the toolbar keys
        setVoiceKey(prefs, Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref, newValue)
        if (!newValue) setVoiceKey(prefs, Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref, false)
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }
    Preference(name = name, onClick = { switched(!on) }) {
        GlassSwitch(checked = on, onCheckedChange = { switched(it) })
    }
    // the mic dictates in the keyboard: its speech engine (This phone, Base, Small) is one row under the switch, shown
    // while the switch is on (settings/screens/DictationScreen)
    if (on) Preference(
        name = stringResource(R.string.dictation_engine),
        description = engineSummary(ctx, prefs),
        onClick = onClickEngine,
    ) { NextScreenIcon() }
}

/**
 * The engine chosen, in a word: "This phone" or the model's name; nothing before a choice is made. Read from the
 * saved choice (SpeechChoice, Settings.PREF_SPEECH_MODEL) so that looking at the settings starts no engine.
 * Same idea as the summary of HeliBoard's ListPreference rows (the chosen value under the name)
 * https://github.com/HeliBorg/HeliBoard/blob/main/app/src/main/java/helium314/keyboard/settings/preferences/ListPreference.kt
 */
private fun engineSummary(context: android.content.Context, prefs: SharedPreferences): String? {
    val choice = prefs.getString(Settings.PREF_SPEECH_MODEL, "").orEmpty()
    return when {
        choice == SpeechModel.SYSTEM -> context.getString(R.string.dictation_this_phone)
        else -> SpeechModel.PINS.firstOrNull { it.name == choice }?.label
    }
}

private fun setVoiceKey(prefs: SharedPreferences, pref: String, default: String, enabled: Boolean) {
    val voice = ToolbarKey.VOICE.name + Separators.KV
    val entries = prefs.getString(pref, default)!!.split(Separators.ENTRY).toMutableList()
    val index = entries.indexOfFirst { it.startsWith(voice) }
    if (index >= 0) entries[index] = voice + enabled else entries.add(voice + enabled)
    prefs.edit { putString(pref, entries.joinToString(Separators.ENTRY)) }
}
