package helium314.keyboard.latin.core.common

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replaces this file with [text] in one step, or not at all.
 *
 * A plain writeText truncates the file first and fills it after. If the app
 * is killed between the two, what is left is an empty or half written file,
 * the next launch fails to parse it, and the store falls back to its default:
 * for the accounts file that is an empty list, every followed account gone.
 * Writing beside the file, syncing, then moving over it leaves either the old
 * content or the new one, never a mix.
 */
internal fun File.writeTextAtomically(text: String) {
    val temp = File(parentFile, "$name.tmp")
    FileOutputStream(temp).use { out ->
        out.write(text.toByteArray())
        out.fd.sync()
    }
    Files.move(
        temp.toPath(),
        toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE
    )
}
