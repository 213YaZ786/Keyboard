package helium314.keyboard.latin.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicWriteTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `replaces the content and leaves no temporary file`() {
        val file = File(folder.root, "accounts.json").apply { writeText("[\"old\"]") }

        file.writeTextAtomically("[\"new\"]")

        assertEquals("[\"new\"]", file.readText())
        assertFalse(File(folder.root, "accounts.json.tmp").exists())
    }

    @Test
    fun `creates the file when it does not exist yet`() {
        val file = File(folder.root, "settings.json")

        file.writeTextAtomically("{}")

        assertEquals("{}", file.readText())
    }
}
