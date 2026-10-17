package helium314.keyboard.latin.core.update

/** What the app does at launch for a newer release (see [startFor]). */
internal enum class Start { NOTHING, ASK, STAGE }

/**
 * Off does nothing; Install with an APK Android lets the app install is
 * downloaded and kept for later; Notify, or an Install that cannot go ahead,
 * asks in the dialog. Same choices as before the install waited for the user
 * to leave the app.
 */
internal fun startFor(mode: UpdateMode, hasApk: Boolean, canInstall: Boolean): Start = when {
    mode == UpdateMode.OFF -> Start.NOTHING
    mode == UpdateMode.INSTALL && hasApk && canInstall -> Start.STAGE
    else -> Start.ASK
}

/**
 * When a downloaded update goes in, and when Android's own confirmation is
 * shown: a plain state, no Android in it, so it is tested on the JVM.
 *
 * Sources: the app counts as left on ProcessLifecycleOwner's ON_STOP and as
 * back on its ON_START
 * (https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner);
 * the installer answers STATUS_PENDING_USER_ACTION when the install still
 * needs the user, whatever setRequireUserAction asked
 * (https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)).
 */
internal class UpdateGate {
    private var ready = false
    private var confirmHeld = false

    /** A verified APK is waiting. */
    @Synchronized
    fun staged() {
        ready = true
    }

    /** The waiting update is dropped (mode changed, file gone). */
    @Synchronized
    fun dropped() {
        ready = false
        confirmHeld = false
    }

    /**
     * Whether to commit the waiting update now: only with the app out of
     * sight and not busy. Once true it is not true again for the same update.
     */
    @Synchronized
    fun installNow(foreground: Boolean, busy: Boolean): Boolean {
        if (!ready || foreground || busy) return false
        ready = false
        return true
    }

    /**
     * Android still wants the reader's confirmation. True when the app is in
     * front and shows it now; false when it is held for the next [started],
     * since an activity is never started from the background.
     */
    @Synchronized
    fun pendingUserAction(foreground: Boolean): Boolean {
        if (foreground) return true
        confirmHeld = true
        return false
    }

    /** The app is back in front: true once if a confirmation was held. */
    @Synchronized
    fun started(): Boolean {
        val show = confirmHeld
        confirmHeld = false
        return show
    }
}
