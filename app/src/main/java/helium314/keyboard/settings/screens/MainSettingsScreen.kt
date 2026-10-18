// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.core.update.Updates
import helium314.keyboard.latin.gesture.FreeGlide
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsPage
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.DictationPreference
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceSection
import helium314.keyboard.settings.preferences.SwitchPreference

// Keyboard: one page instead of HeliBoard's ten screens, as iOS's Settings > General > Keyboard: Keyboards, Text
// Replacement, then the switches in iOS's order (Auto-Capitalization, Auto-Correction, Check Spelling, Predictive
// Text, Smart Punctuation, "." Shortcut, Slide to Type, Character Preview, Enable Dictation), plus the feedback
// Gboard keeps in Preferences. https://support.apple.com/guide/iphone/type-with-the-onscreen-keyboard-iph3c50f96e/ios
// The rows are the former settings, same keys and callbacks (screens/TextCorrectionScreen.kt, PreferencesScreen.kt,
// GestureTypingScreen.kt); everything else HeliBoard has stays one tap away under Advanced (MoreSettingsScreen).
@Composable
fun MainSettingsScreen(
    onClickLanguage: () -> Unit,
    onClickTextReplacement: () -> Unit,
    onClickAppearance: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickDictation: () -> Unit,
    onClickBack: () -> Unit,
) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.ime_settings),
        settings = emptyList(),
    ) {
        val enabledSubtypes = SubtypeSettings.getEnabledSubtypes(true)
        // Keyboard: a page without categories, its rows in zones, as Section in Calculator's settings
        // https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/feature/settings/SettingsScreen.kt
        SettingsPage {
            PreferenceSection(null) {
                Preference(
                    name = stringResource(R.string.language_and_layouts_title),
                    description = enabledSubtypes.joinToString(", ") { it.displayName() },
                    onClick = onClickLanguage,
                    icon = R.drawable.ic_settings_languages
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.main_text_replacement),
                    onClick = onClickTextReplacement,
                    icon = R.drawable.ic_dictionary
                ) { NextScreenIcon() }
                Preference(
                    name = stringResource(R.string.settings_screen_appearance),
                    onClick = onClickAppearance,
                    icon = R.drawable.ic_settings_appearance
                ) { NextScreenIcon() }
            }
            PreferenceSection(null) {
                SwitchPreference(stringResource(R.string.auto_cap), key = Settings.PREF_AUTO_CAP, default = Defaults.PREF_AUTO_CAP)
                SwitchPreference(
                    stringResource(R.string.autocorrect),
                    key = Settings.PREF_AUTO_CORRECTION, default = Defaults.PREF_AUTO_CORRECTION
                )
                SwitchPreference(
                    stringResource(R.string.main_spell_check),
                    key = Settings.PREF_SPELLCHECK_SUGGEST, default = Defaults.PREF_SPELLCHECK_SUGGEST
                )
                SwitchPreference(
                    stringResource(R.string.main_predictions),
                    key = Settings.PREF_BIGRAM_PREDICTIONS, default = Defaults.PREF_BIGRAM_PREDICTIONS
                ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
                SwitchPreference(
                    stringResource(R.string.main_smart_punctuation),
                    key = Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, default = Defaults.PREF_AUTOSPACE_AFTER_PUNCTUATION
                )
                SwitchPreference(
                    stringResource(R.string.main_period_shortcut),
                    key = Settings.PREF_KEY_USE_DOUBLE_SPACE_PERIOD, default = Defaults.PREF_KEY_USE_DOUBLE_SPACE_PERIOD
                )
                if (FreeGlide.AVAILABLE) // free glide typing (latin/gesture) works without Google's library
                    SwitchPreference(
                        stringResource(R.string.main_glide_typing),
                        key = Settings.PREF_GESTURE_INPUT, default = Defaults.PREF_GESTURE_INPUT
                    )
                SwitchPreference(
                    stringResource(R.string.main_key_preview),
                    key = Settings.PREF_POPUP_ON, default = Defaults.PREF_POPUP_ON
                ) { KeyboardSwitcher.getInstance().reloadKeyboard() }
                DictationPreference(stringResource(R.string.main_dictation), onClickDictation)
                if (AudioAndHapticFeedbackManager.getInstance().hasVibrator())
                    SwitchPreference(
                        stringResource(R.string.main_vibration),
                        key = Settings.PREF_VIBRATE_ON, default = Defaults.PREF_VIBRATE_ON
                    )
                SwitchPreference(
                    stringResource(R.string.main_sound),
                    key = Settings.PREF_SOUND_ON, default = Defaults.PREF_SOUND_ON
                )
                // Keyboard: off, nothing of the app touches the network: no update check, no dictionary download
                SwitchPreference(
                    stringResource(R.string.network_title),
                    key = Settings.PREF_NETWORK, default = Defaults.PREF_NETWORK
                ) { if (!it) Updates.drop() } // an update downloaded and waiting is forgotten
            }
            PreferenceSection(null) {
                Preference(
                    name = stringResource(R.string.settings_screen_advanced),
                    onClick = onClickAdvanced,
                    icon = R.drawable.ic_settings_advanced
                ) { NextScreenIcon() }
            }
        }
    }
}

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            MainSettingsScreen({}, {}, {}, {}, {}, {})
        }
    }
}
