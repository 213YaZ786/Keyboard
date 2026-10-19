package helium314.keyboard.latin.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL

/**
 * A dictionary is fetched only from codeberg.org over HTTPS, a redirect is followed only to codeberg.org, at most three
 * times, the body is capped, and a file whose SHA-256 is not known is not kept. Same protocol as Mushaf's
 * NetRedirectTest: the rule is checked as code, hostile samples included
 * (https://github.com/213YaZ786/Mushaf/blob/main/app/src/test/java/org/mushaf/app/core/net/NetRedirectTest.kt),
 * and the update kit's UpdatesTest (fromRepo).
 */
class DictionaryRepoTest {

    private val start = URL("${DictionaryRepo.BASE}dictionaries/main_fr.dict")

    private fun follow(location: String?, hops: Int = 0) = DictionaryRepo.nextHop(start, location, hops)

    private fun refused(block: () -> Unit) {
        val e = assertThrows(DownloadException::class.java) { block() }
        assertEquals(DownloadFailure.REFUSED, e.failure)
    }

    // ---- the files the list can name

    @Test
    fun aLinkOfTheRepoIsAFile() {
        val main = DictionaryRepo.fromLink("${DictionaryRepo.BASE}dictionaries/main_pt_br.dict")
        assertEquals("main_pt_br", main?.name)
        assertEquals("${DictionaryRepo.BASE}dictionaries/main_pt_br.dict", main?.url)
        assertEquals("emoji_es_419", DictionaryRepo.fromLink("${DictionaryRepo.BASE}emoji_cldr_signal_dictionaries/emoji_es_419.dict")?.name)
        assertEquals("main_kab__experimental", DictionaryRepo.fromLink("${DictionaryRepo.BASE}dictionaries/main_kab__experimental.dict")?.name)
    }

    @Test
    fun everyEntryOfHeliBoardsListIsAFile() {
        // the links getKnownDictionariesForLocale builds from assets/dictionaries_in_dict_repo.txt
        val lines = File("src/main/assets/dictionaries_in_dict_repo.txt").readLines().filter { it.isNotBlank() }
        assertTrue(lines.size > 200)
        for (line in lines) {
            val link = if (line.startsWith("cldr,"))
                "${DictionaryRepo.BASE}emoji_cldr_signal_dictionaries/emoji_${line.substringAfter("cldr,")}.dict"
            else "${DictionaryRepo.BASE}dictionaries/$line.dict"
            assertNotNull(line, DictionaryRepo.fromLink(link))
        }
    }

    @Test
    fun anythingElseIsNotAFile() {
        for (link in listOf(
            "http://codeberg.org/Helium314/aosp-dictionaries/raw/branch/main/dictionaries/main_fr.dict",
            "https://evil.example/Helium314/aosp-dictionaries/raw/branch/main/dictionaries/main_fr.dict",
            "${DictionaryRepo.BASE}dictionaries/../main_empty.dict",
            "${DictionaryRepo.BASE}dictionaries/sub/main_fr.dict",
            "${DictionaryRepo.BASE}dictionaries/main_fr.dict?x=1",
            "${DictionaryRepo.BASE}dictionaries/main_fr.dict#x",
            "${DictionaryRepo.BASE}dictionaries/main.fr.dict",
            "${DictionaryRepo.BASE}dictionaries/.dict",
            "${DictionaryRepo.BASE}dictionaries/${"a".repeat(65)}.dict",
            "${DictionaryRepo.BASE}scripts/main_fr.dict",
            "${DictionaryRepo.BASE}dictionaries/main_fr.zip",
            "${DictionaryRepo.BASE}dictionaries/main_fr",
            "${DictionaryRepo.BASE}dictionaries/main_fr%2e.dict",
            "${DictionaryRepo.BASE}dictionaries/main fr.dict",
            "",
            "main_fr"
        )) assertNull(link, DictionaryRepo.fromLink(link))
    }

    // ---- the host

    @Test
    fun onlyHttpsToCodebergItselfPasses() {
        assertEquals(start, DictionaryRepo.checked(start))
        assertEquals("codeberg.org", DictionaryRepo.checked(URL("https://CODEBERG.ORG/x")).host.lowercase())
        assertEquals(443, DictionaryRepo.checked(URL("https://codeberg.org:443/x")).port)
    }

    @Test
    fun anotherHostSchemeUserOrPortIsRefused() {
        for (url in listOf(
            "http://codeberg.org/x",
            "ftp://codeberg.org/x",
            "https://evil.example/x",
            "https://codeberg.org.evil.example/x",
            "https://evilcodeberg.org/",
            "https://x.codeberg.org/",
            "https://codeberg.org@evil.example/x",
            "https://user@codeberg.org/x",
            "https://user:pw@codeberg.org/x",
            "https://codeberg.org:8443/x",
            "https://codeberg.org:80/x",
            "https://[::1]/",
            "https://127.0.0.1/",
            "https://evil.example/codeberg.org"
        )) refused { DictionaryRepo.checked(URL(url)) }
    }

    // ---- the redirects

    @Test
    fun aRedirectWithinCodebergIsFollowed() {
        assertEquals(URL("https://codeberg.org/other/path"), follow("/other/path"))
        assertEquals(URL("https://codeberg.org/Helium314/aosp-dictionaries/raw/branch/main/dictionaries/b"), follow("b"))
        assertEquals(URL("https://codeberg.org/x?sig=1"), follow("https://codeberg.org/x?sig=1"))
        assertEquals("codeberg.org", follow("HTTPS://CODEBERG.ORG/x").host.lowercase())
    }

    @Test
    fun aRedirectElsewhereIsRefused() {
        for (location in listOf(
            "https://evil.example/x",
            "https://codeberg.org.evil.example/x",
            "https://codeberg.org@evil.example/x",
            "https://media.codeberg.org/x",
            "//evil.example/x",
            "http://codeberg.org/x",
            "ftp://codeberg.org/x",
            "file:///data/data/com.yaz.keyboard/x"
        )) refused { follow(location) }
        assertThrows(IOException::class.java) { follow("javascript:alert(1)") }
        assertThrows(IOException::class.java) { follow("https://") }
    }

    @Test
    fun aRedirectWithNoPlaceIsRefused() {
        refused { follow(null) }
        refused { follow("") }
        refused { follow("   ") }
    }

    @Test
    fun atMostThreeRedirectsAreFollowed() {
        for (hops in 0..2) assertEquals(URL("https://codeberg.org/x"), follow("https://codeberg.org/x", hops))
        refused { follow("https://codeberg.org/x", DictionaryRepo.MAX_REDIRECTS) }
        refused { follow("https://codeberg.org/x", 4) }
    }

    @Test
    fun aVeryLongLocationDoesNotCrashTheCheck() {
        assertEquals("codeberg.org", follow("https://codeberg.org/" + "a".repeat(100_000)).host)
        refused { follow("https://" + "a".repeat(100_000) + ".example/") }
    }

    // ---- the body and its hash

    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun theBodyIsCopiedWhole_andHashed() {
        val out = ByteArrayOutputStream()
        val total = ArrayList<Long>()
        val hash = DictionaryRepo.copyCapped(ByteArrayInputStream("abc".toByteArray()), out, 3) { total.add(it) }
        assertEquals(abc, hash)
        assertEquals("abc", out.toString())
        assertEquals(listOf(3L), total)
        assertEquals(empty, DictionaryRepo.copyCapped(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), 10))
    }

    @Test
    fun oneByteOverTheCapStopsTheCopy() {
        val e = assertThrows(DownloadException::class.java) {
            DictionaryRepo.copyCapped(ByteArrayInputStream(ByteArray(4)), ByteArrayOutputStream(), 3)
        }
        assertEquals(DownloadFailure.TOO_LARGE, e.failure)
        // an endless stream is cut at the cap, not read to its end
        val endless = object : java.io.InputStream() {
            var read = 0L
            override fun read() = 1.also { read++ }
            override fun read(b: ByteArray, off: Int, len: Int) = len.also { read += len }
        }
        assertThrows(DownloadException::class.java) {
            DictionaryRepo.copyCapped(endless, ByteArrayOutputStream(), 20L * 1024 * 1024)
        }
        assertTrue(endless.read <= 20L * 1024 * 1024 + 64 * 1024)
    }

    @Test
    fun theCapIsTwentyMegabytes() {
        assertEquals(20L * 1024 * 1024, DictionaryRepo.MAX_BYTES)
    }

    @Test
    fun aCancelledCopyStops() {
        assertThrows(IllegalStateException::class.java) {
            DictionaryRepo.copyCapped(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), 100, { throw IllegalStateException("cancelled") })
        }
    }

    @Test
    fun onlyAKnownHashIsAccepted() {
        val known = setOf(abc)
        assertTrue(DictionaryRepo.accepts(abc, known))
        assertTrue(DictionaryRepo.accepts(abc.uppercase(), known))
        assertFalse(DictionaryRepo.accepts(empty, known))
        assertFalse(DictionaryRepo.accepts("", known))
        assertFalse(DictionaryRepo.accepts(abc, emptySet()))
        assertFalse(DictionaryRepo.accepts(abc.dropLast(1), known))
    }

    @Test
    fun aHashLineIsSixtyFourHexDigits() {
        assertEquals(abc, DictionaryRepo.parseHash(abc))
        assertEquals(abc, DictionaryRepo.parseHash("  ${abc.uppercase()}  # main_fr"))
        assertNull(DictionaryRepo.parseHash("# SHA256 hashes of dictionaries"))
        assertNull(DictionaryRepo.parseHash(""))
        assertNull(DictionaryRepo.parseHash("   "))
        assertNull(DictionaryRepo.parseHash(abc.dropLast(1)))
        assertNull(DictionaryRepo.parseHash(abc + "0"))
        assertNull(DictionaryRepo.parseHash("g" + abc.drop(1)))
    }

    @Test
    fun bothHashFilesHoldOnlyHashesAndComments() {
        for (name in listOf("known_dict_hashes.txt", "known_dict_hashes_keyboard.txt")) {
            val lines = File("src/main/assets/$name").readLines()
            val bad = lines.filter { it.isNotBlank() && !it.startsWith("#") && DictionaryRepo.parseHash(it) == null }
            assertEquals(name, emptyList<String>(), bad)
        }
        // HeliBoard's own list is the base, the app's file only adds to it
        assertTrue(File("src/main/assets/known_dict_hashes.txt").readLines().count { DictionaryRepo.parseHash(it) != null } > 100)
    }

    // A data check in the protocol of Mushaf's PrivacyTest (rules checked as code on the shipped files)
    // https://github.com/213YaZ786/Mushaf/tree/main/app/src/test/java/org/mushaf/app/core/net
    @Test
    fun theAppsHashFileHoldsWhatHeliBoardsDoesNot() {
        val ours = File("src/main/assets/known_dict_hashes_keyboard.txt").readLines()
        val hashes = ours.mapNotNull { DictionaryRepo.parseHash(it) }
        assertEquals("a hash twice", hashes.size, hashes.toSet().size)
        val upstream = File("src/main/assets/known_dict_hashes.txt").readLines().mapNotNull { DictionaryRepo.parseHash(it) }.toSet()
        assertTrue("a hash HeliBoard's list already holds", hashes.none { it in upstream })
        for (name in listOf("emoji_fr", "emoji_ar", "emoji_kab", "addon_ml_zz__experimental")) assertTrue(name, ours.any { it.endsWith("# $name") })
    }

    // ---- the network is reached from these two places only

    // A source scan like NetRedirectTest.noConnectionIsOpenedOutsideNet in Mushaf
    @Test
    fun noConnectionIsOpenedOutsideTheTwoClients() {
        val others = File("src/main").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") && "openConnection(" in it.readText() }
            .map { it.name }.sorted().toList()
        assertEquals(listOf("DictionaryRepo.kt", "Updates.kt"), others)
        assertFalse("instanceFollowRedirects = true" in File("src/main/java/helium314/keyboard/latin/core/net/DictionaryRepo.kt").readText())
    }
}
