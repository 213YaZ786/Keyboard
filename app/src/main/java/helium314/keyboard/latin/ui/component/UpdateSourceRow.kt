package helium314.keyboard.latin.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import helium314.keyboard.latin.core.update.StoreLink

/**
 * "Update by itself": whether the app updates itself or leaves it to Store
 * (the "self" setting of StoreLink, kept in the kit's "updates"
 * preferences). Without Store the switch is on and cannot be turned off;
 * with Store it starts off, and the user's choice is kept.
 *
 * Each app places it in its own Settings, with its other rows (Settings
 * screens are not shared); it is a plain row, so it sits in any list.
 *
 * Source: the switch row of Calculator's SettingsScreen (SwitchRow over
 * SettingRow: ListItem, GlassSwitch, haptic toggle, the whole row taps) and
 * the lifecycle observer of ProcessLifecycleOwner used by Updates.arm
 * (https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner),
 * so Store installed meanwhile shows when the user comes back to the app.
 */
@Composable
fun UpdateSourceRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var present by remember { mutableStateOf(StoreLink.present(context)) }
    var own by remember { mutableStateOf(StoreLink.selfUpdates(context)) }
    DisposableEffect(Unit) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                present = StoreLink.present(context)
                own = StoreLink.selfUpdates(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val haptics = rememberHaptics()
    val change = { on: Boolean ->
        haptics.toggle(on)
        StoreLink.setSelfUpdates(context, on)
        own = on
    }
    // Without Store the app always updates itself: the switch says so and is locked.
    val checked = !present || own
    ListItem(
        headlineContent = { Text("Update by itself", color = MaterialTheme.colorScheme.onSurface) },
        supportingContent = when {
            !present -> "Store is not installed"
            !own -> "Store updates this app"
            else -> null
        }?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent = { GlassSwitch(checked = checked, onCheckedChange = change, enabled = present) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.clickable(enabled = present, role = Role.Switch) { change(!checked) }
    )
}
