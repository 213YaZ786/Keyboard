// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

/**
 * The Network switch of the settings: off, nothing of the app touches the network (the update kit is not called, the
 * dictionary download is hidden). Read where a network feature starts, never cached.
 */
object KeyboardNetwork {

    fun enabled(context: Context): Boolean =
        context.prefs().getBoolean(Settings.PREF_NETWORK, Defaults.PREF_NETWORK)

    /**
     * Whether Android sees a connection that reaches the internet, to say "No connection" before a download is tried.
     * https://developer.android.com/develop/connectivity/network-ops/reading-network-state
     * (ConnectivityManager.getActiveNetwork, NetworkCapabilities.NET_CAPABILITY_INTERNET).
     */
    fun online(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
