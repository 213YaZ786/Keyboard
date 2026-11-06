// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.InputWindowState
import helium314.keyboard.latin.core.net.KeyboardNetwork
import helium314.keyboard.latin.core.update.UpdateMode
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.ui.component.UpdatePrompt
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs

/**
 * The update kit's check, when the settings open: one small request to the app's releases on GitHub, never from the
 * keyboard service, never in the background. Nothing when the Network switch is off. The mode is Settings > Advanced >
 * Updates. An update downloaded goes in when the user leaves the settings, unless the keyboard is on screen.
 * While Store is on the phone and the app's own updater is off (StoreLink.present and not StoreLink.selfUpdates, the
 * default once Store is there), UpdatePrompt asks GitHub nothing and shows nothing: it only tells Store the app opened
 * and, when the settings are left, that it was left (Updates.arm, Updates.leave, StoreLink.opened / left), Store checks and
 * installs. That is the same wiring as SMS's SmsApp.
 * Wired as Calculator's CalculatorApp (UpdatePrompt(settings.updates, BuildConfig.VERSION_NAME)):
 * https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/navigation/CalculatorApp.kt
 */
@Composable
fun UpdateCheck() {
    val ctx = LocalContext.current
    // read again whenever a setting changes (SettingsActivity.prefChanged)
    val changed = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()?.value ?: 0
    if (changed < 0 || !KeyboardNetwork.enabled(ctx)) return
    val mode = runCatching {
        UpdateMode.valueOf(ctx.prefs().getString(Settings.PREF_UPDATE_MODE, Defaults.PREF_UPDATE_MODE)!!)
    }.getOrDefault(UpdateMode.INSTALL)
    UpdatePrompt(mode, BuildConfig.VERSION_NAME, busy = { InputWindowState.shown })
}
