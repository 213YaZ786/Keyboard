// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import helium314.keyboard.latin.dictionary.Dictionary

/**
 * The words of a language with their frequencies, the data FlorisBoard's StatisticalGlideTypingClassifier
 * gets from its NlpManager (setWordData: getListOfWords and getFrequencyForWord,
 * https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/StatisticalGlideTypingClassifier.kt).
 * Here they come from HeliBoard's main dictionary, read word by word with
 * BinaryDictionary.getNextWordProperty until its token returns 0 (the loop of ExpandableBinaryDictionary
 * and Ver4DictDecoder, AOSP LatinIME,
 * https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/java/src/com/android/inputmethod/latin/makedict/Ver4DictDecoder.java),
 * see Dictionary.forEachWord.
 */
class GlideWords(val words: List<String>, private val frequencies: Map<String, Int>) {
    // Source: Floris' NlpManager.getFrequencyForWord
    fun frequencyOf(word: String): Int = frequencies[word] ?: 0

    companion object {
        /** The words kept, the most frequent first. */
        const val MAX_WORDS = 100_000

        /**
         * Reads every word of [dictionary] (heavy: call it off the main thread).
         * Source: Floris' setWordData, with the top [MAX_WORDS] words by frequency.
         */
        fun build(dictionary: Dictionary, skipOffensive: Boolean, maxWords: Int = MAX_WORDS): GlideWords {
            val frequencies = HashMap<String, Int>()
            dictionary.forEachWord(skipOffensive) { word, probability ->
                if (word.isNotEmpty() && probability >= 0) {
                    val old = frequencies[word]
                    if (old == null || old < probability) frequencies[word] = probability
                }
            }
            val sorted = frequencies.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            val kept = if (sorted.size > maxWords) sorted.subList(0, maxWords) else sorted
            val words = kept.map { it.key }
            return GlideWords(words, kept.associate { it.key to it.value })
        }
    }
}
