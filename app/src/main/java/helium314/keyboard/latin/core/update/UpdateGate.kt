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

// Source of Route, selfOn, routeFor and callsStore: Google Play's In-App Updates API, where the
// store updates the app and the app only asks it (https://developer.android.com/guide/playcore/in-app-updates),
// and GrapheneOS AppStore, where only the store updates apps (https://github.com/GrapheneOS/AppStore).
// Written as plain functions like startFor below.

/** Which updater runs: the app's own, or Store's (see [routeFor]). */
internal enum class Route { OWN, STORE }

/**
 * The "self" setting: the user's choice when there is one, else true
 * without Store and false with it. Read at each use, so installing Store
 * later turns the app's own updater off unless the user turned it on.
 * Same shape as [startFor]: a plain function of its inputs, tested on the JVM.
 */
internal fun selfOn(chosen: Boolean?, storePresent: Boolean): Boolean = chosen ?: !storePresent

/** Store updates the app only when its own updater is off and Store is there; else today's behaviour. */
internal fun routeFor(selfOn: Boolean, storePresent: Boolean): Route =
    if (!selfOn && storePresent) Route.STORE else Route.OWN

/** Whether the app tells Store when it opens and when it is left: Install mode, on Store's route. */
internal fun callsStore(mode: UpdateMode, route: Route): Boolean = mode == UpdateMode.INSTALL && route == Route.STORE

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
     * Whether to tell Store now that the app is left, in place of committing
     * a staged update: the same condition as [installNow], out of sight and
     * not busy, but nothing is staged here, so there is no state to spend.
     * Source: ProcessLifecycleOwner's ON_STOP, as [installNow]
     * (https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner).
     */
    fun storeLeaves(foreground: Boolean, busy: Boolean): Boolean = !foreground && !busy

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
