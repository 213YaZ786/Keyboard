package helium314.keyboard.latin.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Plain JUnit 4 on the JVM, as UpdatesTest. The cases follow what
// https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner
// and PackageInstaller.SessionParams.setRequireUserAction say happens: ON_STOP
// when the user leaves, ON_START when they come back, STATUS_PENDING_USER_ACTION
// when Android still asks.
class UpdateGateTest {

    @Test
    fun `a ready update goes in when the app is left and not busy`() {
        val gate = UpdateGate()
        gate.staged()
        assertTrue(gate.installNow(foreground = false, busy = false))
    }

    @Test
    fun `nothing goes in while the user is in the app`() {
        val gate = UpdateGate()
        gate.staged()
        assertFalse(gate.installNow(foreground = true, busy = false))
        // Still waiting: the next stop installs it.
        assertTrue(gate.installNow(foreground = false, busy = false))
    }

    @Test
    fun `a busy app waits for the next stop`() {
        val gate = UpdateGate()
        gate.staged()
        assertFalse(gate.installNow(foreground = false, busy = true))
        assertTrue(gate.installNow(foreground = false, busy = false))
    }

    @Test
    fun `no update waiting installs nothing, and one is installed once`() {
        val gate = UpdateGate()
        assertFalse(gate.installNow(foreground = false, busy = false))
        gate.staged()
        assertTrue(gate.installNow(foreground = false, busy = false))
        assertFalse(gate.installNow(foreground = false, busy = false))
    }

    @Test
    fun `a dropped update is not installed`() {
        val gate = UpdateGate()
        gate.staged()
        gate.dropped()
        assertFalse(gate.installNow(foreground = false, busy = false))
    }

    @Test
    fun `a confirmation asked out of sight is shown at the next start, once`() {
        val gate = UpdateGate()
        assertFalse(gate.pendingUserAction(foreground = false))
        assertTrue(gate.started())
        assertFalse(gate.started())
    }

    @Test
    fun `a confirmation asked while the app is in front is shown at once and not held`() {
        val gate = UpdateGate()
        assertTrue(gate.pendingUserAction(foreground = true))
        assertFalse(gate.started())
    }

    @Test
    fun `a start with nothing held shows nothing`() {
        assertFalse(UpdateGate().started())
    }

    @Test
    fun `a dropped update forgets its held confirmation`() {
        val gate = UpdateGate()
        gate.pendingUserAction(foreground = false)
        gate.dropped()
        assertFalse(gate.started())
    }

    // Store's route, in the same protocol as the tests above (plain inputs, plain answers). The cases
    // follow what Google Play's In-App Updates API and GrapheneOS AppStore do: the store updates the app,
    // the app only asks (https://developer.android.com/guide/playcore/in-app-updates,
    // https://github.com/GrapheneOS/AppStore), and ProcessLifecycleOwner's ON_STOP for "left"
    // (https://developer.android.com/reference/androidx/lifecycle/ProcessLifecycleOwner).

    @Test
    fun `the setting defaults to on without Store and off with it`() {
        assertTrue(selfOn(chosen = null, storePresent = false))
        assertFalse(selfOn(chosen = null, storePresent = true))
    }

    @Test
    fun `the user's choice is kept whether Store is there or not`() {
        assertTrue(selfOn(chosen = true, storePresent = true))
        assertFalse(selfOn(chosen = false, storePresent = true))
        assertTrue(selfOn(chosen = true, storePresent = false))
        assertFalse(selfOn(chosen = false, storePresent = false))
    }

    @Test
    fun `Store installed later turns the own updater off unless the user turned it on`() {
        // Read at each use: same stored value (none), Store absent then present.
        assertEquals(Route.OWN, routeFor(selfOn(null, storePresent = false), storePresent = false))
        assertEquals(Route.STORE, routeFor(selfOn(null, storePresent = true), storePresent = true))
        assertEquals(Route.OWN, routeFor(selfOn(true, storePresent = true), storePresent = true))
    }

    @Test
    fun `Store's route needs the own updater off and Store present, else the app's own runs`() {
        assertEquals(Route.STORE, routeFor(selfOn = false, storePresent = true))
        assertEquals(Route.OWN, routeFor(selfOn = true, storePresent = true))
        assertEquals(Route.OWN, routeFor(selfOn = true, storePresent = false))
        // Off, then Store uninstalled: nobody else to update the app.
        assertEquals(Route.OWN, routeFor(selfOn = false, storePresent = false))
    }

    @Test
    fun `only Install mode on Store's route tells Store`() {
        assertTrue(callsStore(UpdateMode.INSTALL, Route.STORE))
        assertFalse(callsStore(UpdateMode.NOTIFY, Route.STORE))
        assertFalse(callsStore(UpdateMode.OFF, Route.STORE))
        assertFalse(callsStore(UpdateMode.INSTALL, Route.OWN))
    }

    @Test
    fun `Store is told the app was left only when out of sight and not busy`() {
        val gate = UpdateGate()
        assertTrue(gate.storeLeaves(foreground = false, busy = false))
        assertFalse(gate.storeLeaves(foreground = true, busy = false))
        assertFalse(gate.storeLeaves(foreground = false, busy = true))
        assertFalse(gate.storeLeaves(foreground = true, busy = true))
    }

    @Test
    fun `busy blocks the call to Store as it blocks the commit`() {
        val gate = UpdateGate()
        gate.staged()
        assertEquals(gate.installNow(foreground = false, busy = true), gate.storeLeaves(foreground = false, busy = true))
        assertEquals(gate.installNow(foreground = false, busy = false), gate.storeLeaves(foreground = false, busy = false))
    }

    @Test
    fun `the own path still commits once and Store's path needs no staged file`() {
        val gate = UpdateGate()
        gate.staged()
        assertTrue(gate.installNow(foreground = false, busy = false))
        assertFalse(gate.installNow(foreground = false, busy = false))
        assertFalse(UpdateGate().installNow(foreground = false, busy = false))
        assertTrue(UpdateGate().storeLeaves(foreground = false, busy = false))
    }

    @Test
    fun `modes keep their behaviour at launch`() {
        assertEquals(Start.NOTHING, startFor(UpdateMode.OFF, hasApk = true, canInstall = true))
        assertEquals(Start.ASK, startFor(UpdateMode.NOTIFY, hasApk = true, canInstall = true))
        assertEquals(Start.STAGE, startFor(UpdateMode.INSTALL, hasApk = true, canInstall = true))
        assertEquals(Start.ASK, startFor(UpdateMode.INSTALL, hasApk = false, canInstall = true))
        assertEquals(Start.ASK, startFor(UpdateMode.INSTALL, hasApk = true, canInstall = false))
    }
}
