package helium314.keyboard.latin.core.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Plain JUnit 4 on the JVM, as UpdateGateTest and UpdatesTest: the names and the certificate Store is
// looked for by, with no Android in them. The cases follow the debug-name rule of the phone kit's
// Sisters (core/dial/Sisters.kt: ".debug" names count in a debug build only) and the digest form
// PackageManager.hasSigningCertificate takes with CERT_INPUT_SHA256
// (https://developer.android.com/reference/android/content/pm/PackageManager#hasSigningCertificate(java.lang.String,%20byte[],%20int)).
class StoreLinkTest {

    @Test
    fun `a release build looks for Store's release package only`() {
        assertEquals(listOf("com.yaz.store"), StoreLink.packages(debug = false))
    }

    @Test
    fun `a debug build looks for the debug Store after the release one`() {
        assertEquals(listOf("com.yaz.store", "com.yaz.store.debug"), StoreLink.packages(debug = true))
    }

    @Test
    fun `the door is the store package's own authority`() {
        assertEquals("com.yaz.store.door", StoreLink.authority("com.yaz.store"))
        assertEquals("com.yaz.store.debug.door", StoreLink.authority("com.yaz.store.debug"))
    }

    @Test
    fun `the certificate is a SHA-256 digest of 32 bytes`() {
        val digest = StoreLink.certificate()
        assertEquals(32, digest.size)
        assertEquals(0xf0, digest[0].toInt() and 0xff)
        assertEquals(0x1b, digest[31].toInt() and 0xff)
    }

    @Test
    fun `hex reads two digits a byte, upper or lower case`() {
        assertArrayEquals(byteArrayOf(0, 1, 127, -1), StoreLink.hexBytes("00017fff"))
        assertArrayEquals(byteArrayOf(-86, -69), StoreLink.hexBytes("AAbb"))
        assertTrue(StoreLink.hexBytes("").isEmpty())
    }

    @Test
    fun `a name that is not Store's is not looked for`() {
        assertFalse("com.yaz.store.evil" in StoreLink.packages(debug = true))
        assertFalse("com.yaz.store.debug" in StoreLink.packages(debug = false))
    }
}
