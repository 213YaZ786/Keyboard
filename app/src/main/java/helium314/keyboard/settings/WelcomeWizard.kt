// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import helium314.keyboard.latin.ui.component.LoadingMark
import helium314.keyboard.latin.ui.component.SetupTile
import helium314.keyboard.latin.ui.component.SetupTileDialog
import helium314.keyboard.latin.ui.component.SetupTileGrid
import helium314.keyboard.latin.ui.component.leftText
import helium314.keyboard.latin.ui.component.requiredDone
import helium314.keyboard.latin.ui.component.requiredLeft
import helium314.keyboard.latin.ui.glass.LocalGlass
import helium314.keyboard.latin.ui.glass.glassGround
import helium314.keyboard.latin.ui.icon.AppIcons
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.previewDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Keyboard: the setup guide as Modules' SetupTiles, after Language's WelcomeScreen, which is itself after FlorisBoard's
// SetupScreen (Apache-2.0): each step read from the system, not remembered. Only the two choices Android asks of any
// keyboard, both required; everything else takes the best default (languages are the system's) and is asked nowhere.
// The three functions below are ported under our names: rememberSetupTiles is rememberKeyboardSetupTiles, WelcomeScreen
// and SetupScreen are WelcomeWizard.
// not ported: rememberSetupTiles (as such: ported as rememberKeyboardSetupTiles, with two tiles)
// not ported: WelcomeScreen (as such: ported as WelcomeWizard)
// not ported: SetupScreen (as such: ported as WelcomeWizard, the rest in its comment)

/**
 * The two choices, as tiles: each is read from the system every half second while the screen is in front, and turns
 * done by itself (FlorisBoard's observeIsFlorisboardEnabled and observeIsFlorisboardSelected, which SetupScreen reads
 * as isFlorisBoardEnabled and isFlorisBoardSelected to set the current step). The actions are those of SetupScreen's
 * EnableIme (showImeEnablerActivity: the input method settings) and SelectIme (showImePicker).
 * Ported from rememberSetupTiles of
 * https://github.com/213YaZ786/Language/blob/main/app/src/main/java/com/yaz/language/feature/welcome/WelcomeScreen.kt#rememberSetupTiles
 * not ported: the microphone, engine, voice input, voice keyboard, languages and updates tiles (Language's own choices;
 * the user's rule, 2026-11-06: a tile only where the user must make an important choice), LifecycleResumeEffect and
 * checks (the polling below runs only while STARTED instead).
 */
@Composable
fun rememberKeyboardSetupTiles(): List<SetupTile> {
    val context = LocalContext.current
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    var enabled by remember { mutableStateOf(UncachedInputMethodManagerUtils.isThisImeEnabled(context, imm)) }
    var selected by remember { mutableStateOf(UncachedInputMethodManagerUtils.isThisImeCurrent(context, imm)) }
    // only while the screen is in front: nothing runs when the user is away in Android's settings
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                enabled = withContext(Dispatchers.IO) { UncachedInputMethodManagerUtils.isThisImeEnabled(context, imm) }
                selected = withContext(Dispatchers.IO) { UncachedInputMethodManagerUtils.isThisImeCurrent(context, imm) }
                delay(500)
            }
        }
    }

    return listOf(
        SetupTile(
            id = "enable", name = "Turn on", title = "Turn on", icon = AppIcons.Keyboard,
            state = if (enabled) "On" else "Off",
            summary = "Android lets a keyboard type only once you turn it on. Tap to open Android's keyboard list.",
            done = enabled, required = true,
            onClick = {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addCategory(Intent.CATEGORY_DEFAULT))
            }
        ),
        SetupTile(
            id = "select", name = "Use Keyboard", title = "Use Keyboard", icon = AppIcons.CheckCircle,
            state = if (selected) "Chosen" else "Not chosen",
            summary = "Choose Keyboard as the keyboard you type with. Tap to open Android's keyboard picker.",
            done = selected, required = true,
            onClick = { imm.showInputMethodPicker() }
        )
    )
}

/**
 * The guide: the tiles, then Start (Done when [reopened] from Settings), which waits for the required ones; both end
 * in [close]. The Surface takes every touch, so the settings behind it stay out of reach; its ground is the app's.
 * Ported from WelcomeScreen of
 * https://github.com/213YaZ786/Language/blob/main/app/src/main/java/com/yaz/language/feature/welcome/WelcomeScreen.kt#WelcomeScreen
 * not ported: onLanguages (no Languages tile).
 * Beside SetupScreen of
 * https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/app/setup/SetupScreen.kt#SetupScreen
 * not ported: awaitTransition (the flow that returns to the app once enabled: the tiles turn done by themselves),
 * SetupLinks (FlorisBoard's privacy policy and source code links: none in Keyboard's guide), hasRestoredBackup, the
 * Welcome, NotificationPermission and FinishUp steps.
 */
@Composable
fun WelcomeWizard(reopened: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    val steps = rememberKeyboardSetupTiles()
    // The tile whose long press is open (its id), "" when none.
    var explained by rememberSaveable { mutableStateOf("") }
    // both required tiles done = enabled and selected: the launcher icon goes (SystemBroadcastReceiver.hideAppIconOnceSetUp),
    // unless the user turned "Show app icon" on themselves
    val ready = steps.requiredDone()
    LaunchedEffect(ready) {
        if (ready) SystemBroadcastReceiver.hideAppIconOnceSetUp(ctx)
    }

    Surface(
        modifier = Modifier.fillMaxSize().glassGround(LocalGlass.current, MaterialTheme.colorScheme.background),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            WelcomeContent(steps, reopened, close) { explained = it.id }
        }
    }

    steps.firstOrNull { it.id == explained }?.let { tile ->
        SetupTileDialog(tile, onDismiss = { explained = "" })
    }
}

/**
 * What the guide draws from the tiles it is given: header, steps, footer with the one button, as Language splits it
 * from WelcomeScreen. The title is "Welcome to Keyboard" (setup_welcome_title with the app's name), in the accent.
 * Ported from WelcomeContent of
 * https://github.com/213YaZ786/Language/blob/main/app/src/main/java/com/yaz/language/feature/welcome/WelcomeScreen.kt#WelcomeContent
 * The icon at the top is Keyboard's LoadingMark, the keycap pressed and let go (running; the user, 2026-11-07).
 */
@Composable
internal fun WelcomeContent(tiles: List<SetupTile>, reopened: Boolean, onStart: () -> Unit, onExplain: (SetupTile) -> Unit = {}) {
    val steps = tiles
    val appName = stringResource(LocalContext.current.applicationInfo.labelRes)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(40.dp))
            LoadingMark(size = 120.dp)
            Spacer(Modifier.height(20.dp))
            // Every title in the accent (the user, 2026-10-31), as Modules' SettingsSectionTitle.
            Text(
                stringResource(R.string.setup_welcome_title, appName),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(steps.leftText(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            SetupTileGrid(steps, onExplain)

            Spacer(Modifier.height(32.dp))
            val ready = steps.requiredDone()
            BoldButton(filled = true, enabled = ready, onClick = onStart) { Text(if (reopened) "Done" else "Start") }
            if (!ready) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Still needed: " + steps.requiredLeft().joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard(reopened = false, close = {})
        }
    }
}
