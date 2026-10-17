// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import helium314.keyboard.latin.core.link.LinkCleaner

/**
 * What the clipboard shows and pastes. Pure, no Android, so the unit tests run it on the JVM.
 *
 * Keyboard: sensitive items and pasted links.
 */
object ClipboardPrivacy {

    /**
     * How long a sensitive item stays in the history: FlorisBoard's historyAutoCleanSensitiveAfter, 20 s
     * (https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/app/AppPrefs.kt).
     */
    const val SENSITIVE_LIFETIME_MILLIS = 20_000L

    /**
     * The text a card or the suggestion chip shows: [mask] for a sensitive item, as FlorisBoard's
     * ClipboardItem.displayText, which gives R.string.clipboard__sensitive_clip_content
     * (https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/ime/clipboard/provider/ClipboardDatabase.kt).
     */
    fun shown(text: String?, sensitive: Boolean, mask: String): String? = if (sensitive) mask else text

    /**
     * Whether a sensitive item copied at [timeStamp] has to leave the history at [now]: FlorisBoard's
     * ClipboardManager.enforceExpiryDate, `creationTimestampMs < now - historyAutoCleanSensitiveAfter`
     * (https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/ime/clipboard/ClipboardManager.kt).
     */
    fun isExpired(sensitive: Boolean, timeStamp: Long, now: Long): Boolean =
        sensitive && timeStamp < now - SENSITIVE_LIFETIME_MILLIS

    /** The wait until a sensitive item copied at [timeStamp] expires, at least 1 ms (for the Handler that removes it). */
    fun delayUntilExpiry(timeStamp: Long, now: Long): Long =
        (timeStamp + SENSITIVE_LIFETIME_MILLIS - now).coerceAtLeast(1L)

    // Every address of the text, as Modules' links kit PastedText.URL (core/link/PastedText.kt)
    private val URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    // The sentence punctuation and closing wrappers a copied line drags behind its address, as PastedText.CLOSERS
    private const val CLOSERS = ")>]},.;:!?\"'»›"

    /**
     * What is pasted for [text]: each address in it goes through the links kit's LinkCleaner.clean, as
     * PastedText.query does for the one it finds (a detour unwrapped, tracking parameters dropped); the words
     * around it and the punctuation after it stay. The history keeps what was copied.
     */
    fun textForPaste(text: String, cleanLinks: Boolean): String {
        if (!cleanLinks || !text.contains("://")) return text
        return URL.replace(text) { match ->
            val address = match.value.trimEnd(*CLOSERS.toCharArray())
            LinkCleaner.clean(address) + match.value.substring(address.length)
        }
    }
}
