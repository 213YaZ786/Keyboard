// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

/**
 * Whether the keyboard's window is on screen, set by LatinIME.onWindowShown / onWindowHidden
 * (https://developer.android.com/reference/android/inputmethodservice/InputMethodService#onWindowShown()).
 * The update kit installs when the user leaves the settings and the app is not busy (Updates.arm's `busy`): replacing
 * the app while the keyboard is up would cut the user's typing, so an update waits until the window is hidden.
 */
object InputWindowState {
    @Volatile
    var shown = false
}
