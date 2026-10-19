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

    @Test
    fun `modes keep their behaviour at launch`() {
        assertEquals(Start.NOTHING, startFor(UpdateMode.OFF, hasApk = true, canInstall = true))
        assertEquals(Start.ASK, startFor(UpdateMode.NOTIFY, hasApk = true, canInstall = true))
        assertEquals(Start.STAGE, startFor(UpdateMode.INSTALL, hasApk = true, canInstall = true))
        assertEquals(Start.ASK, startFor(UpdateMode.INSTALL, hasApk = false, canInstall = true))
        assertEquals(Start.ASK, startFor(UpdateMode.INSTALL, hasApk = true, canInstall = false))
    }
}
