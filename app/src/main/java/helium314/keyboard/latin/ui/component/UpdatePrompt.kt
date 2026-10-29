package helium314.keyboard.latin.ui.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.core.update.Release
import helium314.keyboard.latin.core.update.Route
import helium314.keyboard.latin.core.update.Start
import helium314.keyboard.latin.core.update.StoreLink
import helium314.keyboard.latin.core.update.UpdateMode
import helium314.keyboard.latin.core.update.Updates
import helium314.keyboard.latin.core.update.callsStore
import helium314.keyboard.latin.core.update.startFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Once per launch of the process, not on every return to the screen. */
private var checkedThisLaunch = false

private enum class Step { ASK, DOWNLOADING, FAILED }

/**
 * Asks GitHub once, when the app opens, whether a newer version is out, and
 * says so in a dialog. [mode] NOTIFY offers the release page; INSTALL
 * downloads and checks it without a dialog, then installs it when the user
 * leaves the app and [busy] is false (see Updates; UpdatedReceiver says what
 * follows). [busy] is the app's own check, true while a call, a sending or a
 * playback must not be cut; it reads the app's state, never a composition's.
 * Nothing is shown when the app is up to date or GitHub cannot be reached.
 * When Store updates the app instead (UpdateSourceRow, StoreLink), GitHub is
 * not asked and nothing is shown: Install mode only tells Store.
 */
@Composable
fun UpdatePrompt(mode: UpdateMode, currentVersion: String, busy: () -> Boolean = { false }) {
    // Off or Notify chosen after a download: nothing installs behind the user's back.
    LaunchedEffect(mode) { if (mode != UpdateMode.INSTALL) Updates.drop() }
    if (mode == UpdateMode.OFF) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<Release?>(null) }
    var step by remember { mutableStateOf(Step.ASK) }

    LaunchedEffect(Unit) {
        if (checkedThisLaunch) return@LaunchedEffect
        checkedThisLaunch = true
        // Store updates this app (see StoreLink): nothing is fetched or shown here; in Install mode
        // the app tells Store it opened, and later that it was left (Updates.arm, Updates.leave).
        // Read off the main thread: the check asks PackageManager.
        val route = withContext(Dispatchers.IO) { StoreLink.route(context) }
        if (route == Route.STORE) {
            if (callsStore(mode, route)) Updates.arm(context, busy)
            return@LaunchedEffect
        }
        val newer = Updates.latest()?.takeIf { Updates.isNewer(it.version, currentVersion) } ?: return@LaunchedEffect
        // Install mode asks nothing: the update downloads by itself and goes
        // in when the user leaves. The dialog shows only when installing
        // cannot go ahead, or failed.
        when (startFor(mode, newer.apk != null, Updates.canInstall(context))) {
            Start.STAGE -> {
                Updates.arm(context, busy)
                if (!Updates.stage(context, newer)) {
                    step = Step.FAILED
                    release = newer
                }
            }
            Start.ASK -> release = newer
            Start.NOTHING -> Unit
        }
    }

    val shown = release ?: return
    val openPage = {
        release = null
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(shown.page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    ZoneAlertDialog(
        onDismissRequest = { if (step != Step.DOWNLOADING) release = null },
        title = { Text("New version available") },
        text = {
            when (step) {
                Step.ASK -> Text("Keyboard ${shown.version}")
                Step.DOWNLOADING -> Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LoadingMark(size = 28.dp)
                    Text("Downloading")
                }
                Step.FAILED -> Text("The update could not be installed.")
            }
        },
        confirmButton = {
            when {
                step == Step.DOWNLOADING -> Unit
                mode == UpdateMode.NOTIFY || step == Step.FAILED || shown.apk == null ->
                    QuietButton(onClick = openPage) { Text("Download") }
                else -> QuietButton(onClick = {
                    if (!Updates.canInstall(context)) {
                        Updates.allowInstalls(context)
                    } else {
                        step = Step.DOWNLOADING
                        scope.launch {
                            Updates.arm(context, busy)
                            if (Updates.stage(context, shown)) release = null else step = Step.FAILED
                        }
                    }
                }) { Text("Install") }
            }
        },
        dismissButton = {
            if (step != Step.DOWNLOADING) QuietButton(onClick = { release = null }) { Text("Later") }
        }
    )
}
