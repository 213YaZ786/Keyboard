// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SystemBroadcastReceiver
import helium314.keyboard.latin.ui.component.BoldButton
import helium314.keyboard.latin.ui.component.ZoneSurface
import helium314.keyboard.latin.ui.glass.LocalGlass
import helium314.keyboard.latin.ui.glass.glassGround
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.previewDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Keyboard: FlorisBoard's setup steps, Enable IME, Select IME, then the last one, in our glass: a ZoneSurface per
// step, its action a BoldButton, the app's glass ground. The steps tick off by themselves: the keyboard being
// enabled and being selected are read again every half second while the screen is shown (FlorisBoard's
// observeIsFlorisboardEnabled and observeIsFlorisboardSelected, timed from Android 14), and the step in front
// is the first not done (stepState.setCurrentAuto). The step's action is the one of SetupScreen's steps:
// the input method settings (showImeEnablerActivity), the picker (showImePicker). Its last step opens Languages &
// Layouts, then Finished.
// Sources: https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/app/setup/SetupScreen.kt
// https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/lib/util/InputMethodUtils.kt
// https://github.com/florisboard/florisboard/blob/main/lib/compose/src/main/kotlin/org/florisboard/lib/compose/FlorisStepLayout.kt
// (Apache-2.0); the zone as SetupZone in https://github.com/213YaZ786/Dialer/blob/main/app/src/main/java/com/yaz/dialer/feature/main/SetupZone.kt
@Composable
fun WelcomeWizard(
    close: () -> Unit,
    finish: () -> Unit
) {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    var enabled by remember { mutableStateOf(UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm)) }
    var selected by remember { mutableStateOf(UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)) }
    // only while the screen is in front: nothing runs when the user is away in Android's settings
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                enabled = withContext(Dispatchers.IO) { UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm) }
                selected = withContext(Dispatchers.IO) { UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm) }
                delay(500)
            }
        }
    }
    val step = when {
        !enabled -> 1
        !selected -> 2
        else -> 3
    }
    // the last step reached = enabled and selected: the launcher icon goes (SystemBroadcastReceiver.hideAppIconOnceSetUp),
    // unless the user turned "Show app icon" on themselves
    LaunchedEffect(step) {
        if (step == 3) SystemBroadcastReceiver.hideAppIconOnceSetUp(ctx)
    }
    val appName = stringResource(ctx.applicationInfo.labelRes)
    // the Surface takes every touch, so the settings behind it stay out of reach; its ground is the app's
    Surface(
        modifier = Modifier.fillMaxSize().glassGround(LocalGlass.current, MaterialTheme.colorScheme.background),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Every title in the accent (the user, 2026-10-31), as Modules' SettingsSectionTitle.
                Text(
                    stringResource(R.string.setup_steps_title, appName),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                SetupStepZone(1, step, stringResource(R.string.setup_step1_title, appName)) {
                    BoldButton(
                        onClick = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addCategory(Intent.CATEGORY_DEFAULT)
                            )
                        },
                        filled = true,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.setup_step1_action)) }
                }
                SetupStepZone(2, step, stringResource(R.string.setup_step2_title, appName)) {
                    BoldButton(
                        onClick = { imm.showInputMethodPicker() },
                        filled = true,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.setup_step2_action)) }
                }
                SetupStepZone(3, step, stringResource(R.string.setup_step3_choose_languages)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BoldButton(
                            onClick = {
                                SettingsDestination.navigateTo(SettingsDestination.Languages)
                                close()
                            },
                            filled = true,
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.language_and_layouts_title), textAlign = TextAlign.Center) }
                        BoldButton(onClick = finish, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.setup_finish_action), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One step: its number (a tick once done) and title, and the action only while it is the one in front; the
 * steps still to come are dimmed, as FlorisStepLayout's Step does (0.38 alpha).
 */
@Composable
private fun SetupStepZone(number: Int, current: Int, title: String, action: @Composable ColumnScope.() -> Unit) {
    val done = number < current
    val badge = MaterialTheme.colorScheme.primary
    ZoneSurface(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().alpha(if (number > current) 0.38f else 1f)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(badge), contentAlignment = Alignment.Center) {
                    if (done)
                        Icon(painterResource(R.drawable.ic_setup_check), null, tint = MaterialTheme.colorScheme.onPrimary)
                    else
                        Text(number.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
                }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            AnimatedVisibility(visible = number == current) {
                Column(Modifier.padding(top = 16.dp)) { action() }
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}
