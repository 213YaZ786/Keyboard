/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.gesture

import java.text.Normalizer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Port of FlorisBoard's glide typing classifier, constants and maths unchanged:
 * https://github.com/florisboard/florisboard/blob/main/app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/StatisticalGlideTypingClassifier.kt
 * (Apache-2.0). The classifier follows Etienne Desticourt's write up
 * https://github.com/AnySoftKeyboard/AnySoftKeyboard/pull/1870 (SHARK2: a location channel and a
 * shape channel compared with the ideal path of each word).
 *
 * Adapted from Floris:
 * - keys are [GlideKey] (made from HeliBoard's Keyboard) instead of TextKey, words and their
 *   frequencies are given by the caller instead of NlpManager, no Subtype, no Context;
 * - the gesture is given as plain floats instead of GlideTypingGesture.Detector.Position;
 * - suggestions come with their confidence ([GlideCandidate]) so several languages can be merged;
 * - Android's LruCache and SparseArrayCompat are replaced by LinkedHashMap and HashMap (plain JVM);
 * - Gesture grows its arrays up to MAX_SIZE instead of allocating 500 floats twice each time
 *   (a word's ideal gesture is built for every remaining word at every update);
 * - the Pruner finds the first and last keys of a word the way generateIdealGestures does (lower
 *   case, then the key of the letter itself, then its base letter), Floris looked only at the
 *   base letter and skipped words starting with a capital;
 * - pruneByLength builds a word's ideal gesture only when its length is not cached yet (same
 *   words kept, Floris cached the length of the first ideal gesture for both variants too);
 * - a frequency of 0 counts as 1, so words of frequency 0 are still ordered by their match.
 */

/** A letter key of the keyboard: its code (lower case) and the centre and size of its visible area. */
data class GlideKey(val code: Int, val centerX: Float, val centerY: Float, val width: Float, val height: Float)

/** A word proposed for a gesture. The smaller the confidence, the better the match. */
data class GlideCandidate(val word: String, val confidence: Float)

/**
 * Classifies gestures by comparing them with an "ideal gesture".
 *
 * Not thread safe: use it from one thread at a time.
 */
class StatisticalGlideTypingClassifier {
    private val gesture = Gesture()
    private var keysByCharacter: HashMap<Int, GlideKey> = HashMap()
    private var words: List<String> = emptyList()
    private var frequencyOf: (String) -> Int = { 0 }
    private var keys: List<GlideKey> = emptyList()
    private var pruner: Pruner? = null
    private var wordsSet = false
    private val prunerCache = LruMap<List<GlideKey>, Pruner>(PRUNER_CACHE_SIZE)

    val ready: Boolean
        get() = wordsSet && pruner != null

    /**
     * The minimum distance between points to be added to a gesture.
     */
    private var distanceThresholdSquared = 0

    companion object {
        /**
         * Describes the allowed length variance in a gesture. If a gesture is too long or too short, it is immediately
         * discarded to save cycles.
         */
        private const val PRUNING_LENGTH_THRESHOLD = 8.42

        /**
         * describes the number of points to sample a gesture at, i.e the resolution.
         */
        private const val SAMPLING_POINTS: Int = 200

        /**
         * Standard deviation of the distribution of distances between the shapes of two gestures
         * representing the same word. It's expressed for normalized gestures and is therefore
         * independent of the keyboard or key size.
         */
        private const val SHAPE_STD = 22.08f

        /**
         * Standard deviation of the distribution of distances between the locations of two gestures
         * representing the same word. It's expressed as a factor of key radius as it's applied to
         * un-normalized gestures and is therefore dependent on the size of the keys/keyboard.
         */
        private const val LOCATION_STD = 0.5109f

        /**
         * This is a very small cache that caches suggestions, so that they aren't recalculated e.g when releasing
         * a pointer when the suggestions were already calculated. Avoids a lot of micro pauses.
         */
        private const val SUGGESTION_CACHE_SIZE = 5

        /**
         * For multiple layouts, the pruner is cached.
         */
        private const val PRUNER_CACHE_SIZE = 5
    }

    // Source: Floris' addGesturePoint
    fun addGesturePoint(x: Float, y: Float) {
        if (!gesture.isEmpty) {
            val dx = gesture.getLastX() - x
            val dy = gesture.getLastY() - y

            if (dx * dx + dy * dy > distanceThresholdSquared) {
                gesture.addPoint(x, y)
            }
        } else {
            gesture.addPoint(x, y)
        }
    }

    // Source: Floris' setLayout (the word data part is [setWordData])
    fun setLayout(keyViews: List<GlideKey>) {
        // stop duplicate calls
        if (keys == keyViews && pruner != null) {
            return
        }
        suggestionCache.clear()
        keysByCharacter.clear()
        keyViews.forEach { keysByCharacter[it.code] = it }
        keys = keyViews.toList()
        distanceThresholdSquared = (keyViews.firstOrNull()?.let { (it.width / 4).toInt() } ?: 0)
        distanceThresholdSquared *= distanceThresholdSquared
        initializePruner()
    }

    // Source: Floris' setWordData (the words come from the caller, see GlideWords)
    fun setWordData(words: List<String>, frequencyOf: (String) -> Int) {
        this.words = words
        this.frequencyOf = frequencyOf
        wordsSet = true
        suggestionCache.clear()
        prunerCache.clear()
        initializePruner()
    }

    /**
     * Exists because Pruner requires both word data and layout are initialized,
     * however we don't know what order they're initialized in.
     */
    // Source: Floris' initializePruner
    private fun initializePruner() {
        if (!wordsSet || keys.isEmpty()) {
            pruner = null
            return
        }
        val cached = prunerCache.get(keys)
        pruner = if (cached == null) {
            Pruner(PRUNING_LENGTH_THRESHOLD, words, keysByCharacter).also { prunerCache.put(keys, it) }
        } else {
            cached
        }
    }

    // Source: Floris' initGestureFromPointerData
    fun initGestureFromPoints(xs: IntArray, ys: IntArray, size: Int) {
        for (i in 0 until size) {
            addGesturePoint(xs[i].toFloat(), ys[i].toFloat())
        }
    }

    private val suggestionCache = LruMap<Pair<Gesture, Int>, List<GlideCandidate>>(SUGGESTION_CACHE_SIZE)

    // Source: Floris' getSuggestions
    fun getSuggestions(maxSuggestionCount: Int): List<String> = getCandidates(maxSuggestionCount).map { it.word }

    // Source: Floris' getSuggestions, with the confidence kept
    fun getCandidates(maxSuggestionCount: Int): List<GlideCandidate> {
        if (gesture.isEmpty || !ready) return emptyList()
        return when (val cached = suggestionCache.get(Pair(gesture, maxSuggestionCount))) {
            null -> {
                val suggestions = unCachedGetSuggestions(maxSuggestionCount)
                suggestionCache.put(Pair(gesture.clone(), maxSuggestionCount), suggestions)
                suggestions
            }
            else -> cached
        }
    }

    // Source: Floris' unCachedGetSuggestions
    private fun unCachedGetSuggestions(maxSuggestionCount: Int): List<GlideCandidate> {
        val pruner = this.pruner ?: return emptyList()
        val candidates = arrayListOf<String>()
        val candidateWeights = arrayListOf<Float>()
        val key = keys.firstOrNull() ?: return emptyList()
        val radius = min(key.height, key.width)
        var remainingWords = pruner.pruneByExtremities(gesture, this.keys)
        val userGesture = gesture.resample(SAMPLING_POINTS)
        val normalizedUserGesture: Gesture = userGesture.normalizeByBoxSide()
        remainingWords = pruner.pruneByLength(gesture, remainingWords, keysByCharacter, keys)

        for (i in remainingWords.indices) {
            val word = remainingWords[i]
            val idealGestures = Gesture.generateIdealGestures(word, keysByCharacter)

            for (idealGesture in idealGestures) {
                val wordGesture = idealGesture.resample(SAMPLING_POINTS)
                val normalizedGesture: Gesture = wordGesture.normalizeByBoxSide()
                val shapeDistance = calcShapeDistance(normalizedGesture, normalizedUserGesture)
                val locationDistance = calcLocationDistance(wordGesture, userGesture)
                val shapeProbability = calcGaussianProbability(shapeDistance, 0.0f, SHAPE_STD)
                val locationProbability = calcGaussianProbability(locationDistance, 0.0f, LOCATION_STD * radius)
                val frequency = max(frequencyOf(word), 1).toFloat()
                val confidence = 1.0f / (shapeProbability * locationProbability * frequency)

                var candidateDistanceSortedIndex = 0
                var duplicateIndex = Int.MAX_VALUE

                while (candidateDistanceSortedIndex < candidateWeights.size
                    && candidateWeights[candidateDistanceSortedIndex] <= confidence
                ) {
                    if (candidates[candidateDistanceSortedIndex].contentEquals(word)) duplicateIndex =
                        candidateDistanceSortedIndex
                    candidateDistanceSortedIndex++
                }
                if (candidateDistanceSortedIndex < maxSuggestionCount && candidateDistanceSortedIndex <= duplicateIndex) {
                    if (duplicateIndex < Int.MAX_VALUE) {
                        candidateWeights.removeAt(duplicateIndex)
                        candidates.removeAt(duplicateIndex)
                    }
                    candidateWeights.add(candidateDistanceSortedIndex, confidence)
                    candidates.add(candidateDistanceSortedIndex, word)
                    if (candidateWeights.size > maxSuggestionCount) {
                        candidateWeights.removeAt(maxSuggestionCount)
                        candidates.removeAt(maxSuggestionCount)
                    }
                }
            }
        }

        return candidates.indices.map { GlideCandidate(candidates[it], candidateWeights[it]) }
    }

    // Source: Floris' clear
    fun clear() {
        gesture.clear()
    }

    // Source: Floris' calcLocationDistance
    private fun calcLocationDistance(gesture1: Gesture, gesture2: Gesture): Float {
        var totalDistance = 0.0f
        for (i in 0 until SAMPLING_POINTS) {
            val x1 = gesture1.getX(i)
            val x2 = gesture2.getX(i)
            val y1 = gesture1.getY(i)
            val y2 = gesture2.getY(i)
            val distance = abs(x1 - x2) + abs(y1 - y2)
            totalDistance += distance
        }
        return totalDistance / SAMPLING_POINTS / 2
    }

    // Source: Floris' calcGaussianProbability
    private fun calcGaussianProbability(value: Float, mean: Float, standardDeviation: Float): Float {
        val factor = 1.0 / (standardDeviation * sqrt(2 * PI))
        val exponent = ((value - mean) / standardDeviation).toDouble().pow(2.0)
        val probability = factor * exp(-1.0 / 2 * exponent)
        return probability.toFloat()
    }

    // Source: Floris' calcShapeDistance
    private fun calcShapeDistance(gesture1: Gesture, gesture2: Gesture): Float {
        var distance: Float
        var totalDistance = 0.0f
        for (i in 0 until SAMPLING_POINTS) {
            val x1 = gesture1.getX(i)
            val x2 = gesture2.getX(i)
            val y1 = gesture1.getY(i)
            val y2 = gesture2.getY(i)
            distance = Gesture.distance(x1, y1, x2, y2)
            totalDistance += distance
        }
        return totalDistance
    }

    // Source: Floris' StatisticalGlideTypingClassifier.Pruner
    class Pruner(
        /**
         * The length difference between a user gesture and a word gesture above which a word will
         * be pruned.
         */
        private val lengthThreshold: Double,
        words: List<String>,
        keysByCharacter: Map<Int, GlideKey>,
    ) {

        /** A tree that provides fast access to words based on their first and last letter.  */
        private val wordTree = Collections.synchronizedMap(HashMap<Pair<Int, Int>, ArrayList<String>>())

        /**
         * Finds the words whose start and end letter are closest to the start and end points of the
         * user gesture.
         *
         * @param userGesture The current user gesture.
         * @param keys The keys on the keyboard.
         * @return A list of likely words.
         */
        fun pruneByExtremities(
            userGesture: Gesture,
            keys: Iterable<GlideKey>,
        ): ArrayList<String> {
            val remainingWords = ArrayList<String>()
            val startX = userGesture.getFirstX()
            val startY = userGesture.getFirstY()
            val endX = userGesture.getLastX()
            val endY = userGesture.getLastY()
            val startKeys = findNClosestKeys(startX, startY, 2, keys)
            val endKeys = findNClosestKeys(endX, endY, 2, keys)
            for (startKey in startKeys) {
                for (endKey in endKeys) {
                    val keyPair = Pair(startKey, endKey)
                    val wordsForKeys = synchronized(wordTree) { wordTree[keyPair] }
                    if (wordsForKeys != null) {
                        remainingWords.addAll(wordsForKeys)
                    }
                }
            }
            return remainingWords
        }

        /**
         * Finds the words whose ideal gesture length is within a certain threshold of the user
         * gesture's length.
         *
         * @param userGesture The current user gesture.
         * @param words A list of words to consider.
         * @return A list of words that remained after pruning the input list by length.
         */
        fun pruneByLength(
            userGesture: Gesture,
            words: ArrayList<String>,
            keysByCharacter: Map<Int, GlideKey>,
            keys: List<GlideKey>,
        ): ArrayList<String> {
            val remainingWords = ArrayList<String>()

            val key = keys.firstOrNull() ?: return arrayListOf()
            val radius = min(key.height, key.width)
            val userLength = userGesture.getLength()
            for (word in words) {
                val wordIdealLength = getCachedIdealLength(word, keysByCharacter)
                if (abs(userLength - wordIdealLength) < lengthThreshold * radius) {
                    remainingWords.add(word)
                }
            }
            return remainingWords
        }

        private val cachedIdealLength = ConcurrentHashMap<String, Float>()
        private fun getCachedIdealLength(word: String, keysByCharacter: Map<Int, GlideKey>): Float {
            return cachedIdealLength.getOrPut(word) {
                Gesture.generateIdealGestures(word, keysByCharacter).first().getLength()
            }
        }

        companion object {
            private fun getFirstKeyLastKey(
                word: String,
                keysByCharacter: Map<Int, GlideKey>,
            ): Pair<Int, Int>? {
                if (word.isEmpty()) return null
                val firstKey = Gesture.keyForLetter(word[0], keysByCharacter)
                val lastKey = Gesture.keyForLetter(word[word.length - 1], keysByCharacter)
                return if (firstKey != null && lastKey != null) {
                    firstKey.code to lastKey.code
                } else {
                    null
                }
            }

            /**
             * Finds a chosen number of keys closest to a given point on the keyboard.
             *
             * @param x X coordinate of the point.
             * @param y Y coordinate of the point.
             * @param n The number of keys to return.
             * @param keys The keys of the keyboard.
             * @return A list of the n closest keys.
             */
            private fun findNClosestKeys(
                x: Float, y: Float, n: Int, keys: Iterable<GlideKey>
            ): Iterable<Int> {
                val keyDistances = HashMap<GlideKey, Float>()
                for (key in keys) {
                    val distance = Gesture.distance(
                        key.centerX,
                        key.centerY,
                        x,
                        y
                    )
                    keyDistances[key] = distance
                }

                return keyDistances.entries.sortedWith { c1, c2 -> c1.value.compareTo(c2.value) }.take(n)
                    .map { it.key.code }
            }
        }

        init {
            synchronized(wordTree) {
                for (word in words) {
                    val keyPair = getFirstKeyLastKey(word, keysByCharacter)
                    keyPair?.let {
                        wordTree.getOrPut(keyPair) { arrayListOf() }.add(word)
                    }
                }
            }
        }
    }

    // Source: Floris' StatisticalGlideTypingClassifier.Gesture
    class Gesture(
        private var xs: FloatArray = FloatArray(INITIAL_SIZE),
        private var ys: FloatArray = FloatArray(INITIAL_SIZE),
        private var size: Int = 0,
    ) {
        companion object {
            // TODO: Find out optimal max size (Floris)
            private const val MAX_SIZE = 500
            private const val INITIAL_SIZE = 16

            /** The key of a letter: its own key, else the key of its base letter ("e" for "é"). */
            fun keyForLetter(c: Char, keysByCharacter: Map<Int, GlideKey>): GlideKey? {
                val lc = Character.toLowerCase(c)
                return keysByCharacter[lc.code]
                    // Try finding the base character instead, e.g., the "e" key instead of "é"
                    ?: keysByCharacter[Normalizer.normalize(lc.toString(), Normalizer.Form.NFD)[0].code]
            }

            fun generateIdealGestures(word: String, keysByCharacter: Map<Int, GlideKey>): List<Gesture> {
                val idealGesture = Gesture()
                val idealGestureWithLoops = Gesture()
                var previousLetter = '\u0000'
                var hasLoops = false

                // Add points for each key
                for (c in word) {
                    val lc = Character.toLowerCase(c)
                    val key = keyForLetter(c, keysByCharacter) ?: continue

                    // We adda little loop on  the key for duplicate letters
                    // so that we can differentiate words like pool and poll, lull and lul, etc...
                    if (previousLetter == lc) {
                        // bottom right
                        idealGestureWithLoops.addPoint(
                            key.centerX + key.width / 4.0f,
                            key.centerY + key.height / 4.0f
                        )
                        // top right
                        idealGestureWithLoops.addPoint(
                            key.centerX + key.width / 4.0f,
                            key.centerY - key.height / 4.0f
                        )
                        // top left
                        idealGestureWithLoops.addPoint(
                            key.centerX - key.width / 4.0f,
                            key.centerY - key.height / 4.0f
                        )
                        // bottom left
                        idealGestureWithLoops.addPoint(
                            key.centerX - key.width / 4.0f,
                            key.centerY + key.height / 4.0f
                        )
                        hasLoops = true

                        idealGesture.addPoint(
                            key.centerX,
                            key.centerY
                        )
                    } else {
                        idealGesture.addPoint(
                            key.centerX,
                            key.centerY
                        )
                        idealGestureWithLoops.addPoint(
                            key.centerX,
                            key.centerY
                        )
                    }
                    previousLetter = lc
                }
                return when (hasLoops) {
                    true -> listOf(idealGesture, idealGestureWithLoops)
                    false -> listOf(idealGesture)
                }
            }

            fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
                return sqrt((x1 - x2).pow(2) + (y1 - y2).pow(2))
            }
        }

        val isEmpty: Boolean
            get() = size == 0

        fun addPoint(x: Float, y: Float) {
            if (size >= MAX_SIZE) {
                return
            }
            if (size == xs.size) {
                val newSize = min(max(xs.size * 2, INITIAL_SIZE), MAX_SIZE)
                xs = xs.copyOf(newSize)
                ys = ys.copyOf(newSize)
            }
            xs[size] = x
            ys[size] = y
            size += 1
        }

        /**
         * Resamples the gesture into a new gesture with the chosen number of points by oversampling
         * it.
         *
         * @param numPoints The number of points that the new gesture will have. Must be superior to
         * the number of points in the current gesture.
         * @return An oversampled copy of the gesture.
         */
        fun resample(numPoints: Int): Gesture {
            val interpointDistance = (getLength() / numPoints)
            val resampledGesture = Gesture()
            if (size == 0) return resampledGesture
            resampledGesture.addPoint(xs[0], ys[0])
            var lastX = xs[0]
            var lastY = ys[0]
            var newX: Float
            var newY: Float
            var cumulativeError = 0.0f

            // otherwise nothing happens if size is only 1:
            if (this.size == 1) {
                for (i in 0 until numPoints) {
                    resampledGesture.addPoint(xs[0], ys[0])
                }
            }

            for (i in 0 until size - 1) {
                // We calculate the unit vector from the two points we're between in the actual
                // gesture
                var dx = xs[i + 1] - xs[i]
                var dy = ys[i + 1] - ys[i]
                val norm = sqrt(dx.pow(2.0f) + dy.pow(2.0f))
                dx /= norm
                dy /= norm

                // The number of evenly sampled points that fit between the two actual points
                var numNewPoints = norm / interpointDistance

                // The number of point that'd fit between the two actual points is often not round,
                // which means we'll get an increasingly large error as we resample the gesture
                // and round down that number. To compensate for this we keep track of the error
                // and add additional points when it gets too large.
                cumulativeError += numNewPoints - numNewPoints.toInt()
                if (cumulativeError > 1) {
                    numNewPoints = (numNewPoints.toInt() + cumulativeError.toInt()).toFloat()
                    cumulativeError %= 1
                }
                for (j in 0 until numNewPoints.toInt()) {
                    newX = lastX + dx * interpointDistance
                    newY = lastY + dy * interpointDistance
                    lastX = newX
                    lastY = newY
                    resampledGesture.addPoint(newX, newY)
                }
            }
            return resampledGesture
        }

        fun normalizeByBoxSide(): Gesture {
            val normalizedGesture = Gesture()

            var maxX = -1.0f
            var maxY = -1.0f
            var minX = 10000.0f
            var minY = 10000.0f

            for (i in 0 until size) {
                maxX = max(xs[i], maxX)
                maxY = max(ys[i], maxY)
                minX = min(xs[i], minX)
                minY = min(ys[i], minY)
            }

            val width = maxX - minX
            val height = maxY - minY
            val longestSide = max(max(width, height), 0.00001f)

            val centroidX = (width / 2 + minX) / longestSide
            val centroidY = (height / 2 + minY) / longestSide

            for (i in 0 until size) {
                val x = xs[i] / longestSide - centroidX
                val y = ys[i] / longestSide - centroidY
                normalizedGesture.addPoint(x, y)
            }

            return normalizedGesture
        }

        fun getFirstX(): Float = if (size > 0) xs[0] else 0f
        fun getFirstY(): Float = if (size > 0) ys[0] else 0f
        fun getLastX(): Float = if (size > 0) xs[size - 1] else 0f
        fun getLastY(): Float = if (size > 0) ys[size - 1] else 0f

        fun getLength(): Float {
            var length = 0f
            for (i in 1 until size) {
                val previousX = xs[i - 1]
                val previousY = ys[i - 1]
                val currentX = xs[i]
                val currentY = ys[i]
                length += distance(previousX, previousY, currentX, currentY)
            }

            return length
        }

        fun clear() {
            this.size = 0
        }

        fun getX(i: Int): Float = if (i in 0 until size) xs[i] else 0f
        fun getY(i: Int): Float = if (i in 0 until size) ys[i] else 0f

        fun clone(): Gesture {
            return Gesture(xs.copyOf(size.coerceAtLeast(1)), ys.copyOf(size.coerceAtLeast(1)), size)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Gesture

            if (this.size != other.size) return false

            for (i in 0 until size) {
                if (xs[i] != other.xs[i] || ys[i] != other.ys[i]) return false
            }

            return true
        }

        override fun hashCode(): Int {
            var result = size
            for (i in 0 until size) {
                result = 31 * result + xs[i].hashCode()
                result = 31 * result + ys[i].hashCode()
            }
            return result
        }
    }
}

/**
 * A small least recently used map.
 * Source: the access-ordered LinkedHashMap recipe of the Java documentation
 * (https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/LinkedHashMap.html#removeEldestEntry(java.util.Map.Entry)),
 * in place of Floris' androidx LruCache.
 */
internal class LruMap<K, V>(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<K, V>(maxEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    }

    fun get(key: K): V? = map[key]
    fun put(key: K, value: V) {
        map[key] = value
    }

    fun clear() = map.clear()
}
