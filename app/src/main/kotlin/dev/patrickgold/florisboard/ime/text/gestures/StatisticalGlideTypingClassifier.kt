/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.text.gestures

import android.content.Context
import androidx.collection.LruCache
import androidx.collection.SparseArrayCompat
import androidx.collection.set
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.keyboard.codePointCaseAndBaseVariants
import dev.patrickgold.florisboard.lib.lowercase
import dev.patrickgold.florisboard.lib.uppercase
import dev.patrickgold.florisboard.nlpManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.text.Normalizer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Classifies gestures by comparing them with an "ideal gesture".
 *
 * Check out Étienne Desticourt's excellent write up at https://github.com/AnySoftKeyboard/AnySoftKeyboard/pull/1870
 */
class StatisticalGlideTypingClassifier(context: Context) : GlideTypingClassifier {
    private val nlpManager by context.nlpManager()

    private val gesture = Gesture()
    private var keyIndex = KeyIndex.Empty
    private var words = emptyList<String>()
    private var keys = emptyList<GlideTypingKey>()
    private lateinit var pruner: Pruner
    private var wordDataSubtype: Subtype? = null
    private var wordDataRevision = -1L
    private var layoutSubtype: Subtype? = null
    val ready: Boolean
        get() = keys.isNotEmpty() &&
            wordDataSubtype == layoutSubtype &&
            wordDataSubtype != null &&
            ::pruner.isInitialized
    private val prunerCache = LruCache<PrunerCacheKey, Pruner>(PRUNER_CACHE_SIZE)

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
         * For multiple subtypes, the pruner is cached.
         */
        private const val PRUNER_CACHE_SIZE = 5
        private const val MAX_WORD_TOKENIZATIONS = 32

        private fun geometryWord(word: String): String {
            return Normalizer.normalize(word, Normalizer.Form.NFC)
        }

        private fun isGeometryIgnorable(codePoint: Int): Boolean {
            return when (Character.getType(codePoint)) {
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt(),
                Character.FORMAT.toInt(),
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt() -> true
                else -> false
            }
        }

        internal fun buildKeyIndex(keys: List<GlideTypingKey>, subtype: Subtype): KeyIndex {
            return KeyIndex.build(keys, subtype)
        }
    }

    internal class KeyIndex private constructor(
        private val directByCharacter: SparseArrayCompat<GlideTypingKey>,
        private val aliasesByCharacter: SparseArrayCompat<GlideTypingKey>,
        private val directTokens: Map<Int, List<OutputToken>>,
        private val aliasTokens: Map<Int, List<OutputToken>>,
    ) {
        private data class OutputToken(val text: String, val key: GlideTypingKey)

        fun keyForCodePoint(codePoint: Int): GlideTypingKey? {
            directByCharacter[codePoint]?.let { return it }
            aliasesByCharacter[codePoint]?.let { return it }
            val variants = codePointCaseAndBaseVariants(codePoint)
            variants.forEach { variant ->
                directByCharacter[variant]?.let { return it }
            }
            variants.forEach { variant ->
                aliasesByCharacter[variant]?.let { return it }
            }
            return null
        }

        fun tokenize(word: String): List<List<GlideTypingKey>> {
            val text = geometryWord(word)
            if (text.isEmpty()) return emptyList()
            val memo = hashMapOf<Int, List<List<GlideTypingKey>>>()

            fun tokenizeAt(index: Int): List<List<GlideTypingKey>> {
                if (index >= text.length) return listOf(emptyList())
                memo[index]?.let { return it }

                val codePoint = text.codePointAt(index)
                val nextIndex = index + Character.charCount(codePoint)
                val directMatches = directTokens[codePoint]
                    .orEmpty()
                    .filter { text.startsWith(it.text, index) }
                val matches = buildList {
                    addAll(directMatches)
                    addAll(
                        aliasTokens[codePoint]
                            .orEmpty()
                            .filter {
                                it.text.codePointCount(0, it.text.length) > 1 &&
                                    text.startsWith(it.text, index)
                            },
                    )
                    val hasExactDirectKey = directMatches.any { it.text.length == nextIndex - index }
                    if (!hasExactDirectKey) {
                        keyForCodePoint(codePoint)?.let { key ->
                            add(OutputToken(text.substring(index, nextIndex), key))
                        }
                    }
                }.distinct()

                val tokenizations = if (matches.isEmpty()) {
                    // Contractions and composed scripts may contain non-key punctuation, format
                    // characters or marks. Bridge those, but never invent geometry for an
                    // unavailable letter, digit or symbol.
                    if (isGeometryIgnorable(codePoint)) tokenizeAt(nextIndex) else emptyList()
                } else {
                    val branches = matches.map { match ->
                        tokenizeAt(index + match.text.length).map { suffix ->
                            listOf(match.key) + suffix
                        }
                    }
                    buildList {
                        var branchIndex = 0
                        while (size < MAX_WORD_TOKENIZATIONS) {
                            var added = false
                            for (branch in branches) {
                                if (branchIndex < branch.size) {
                                    add(branch[branchIndex])
                                    added = true
                                }
                                if (size >= MAX_WORD_TOKENIZATIONS) break
                            }
                            if (!added) break
                            branchIndex += 1
                        }
                    }.distinct()
                }
                return tokenizations.take(MAX_WORD_TOKENIZATIONS).also { memo[index] = it }
            }

            return tokenizeAt(0).filter { it.isNotEmpty() }
        }

        companion object {
            val Empty = KeyIndex(SparseArrayCompat(), SparseArrayCompat(), emptyMap(), emptyMap())

            fun build(keys: List<GlideTypingKey>, subtype: Subtype): KeyIndex {
                val directByCharacter = SparseArrayCompat<GlideTypingKey>()
                val directTokens = keys.mapNotNull { key ->
                    val output = geometryWord(key.output)
                    output.takeIf(String::isNotEmpty)?.let { OutputToken(it, key) }
                }
                directTokens.forEach { token ->
                    if (
                        token.text.codePointCount(0, token.text.length) == 1 &&
                        directByCharacter.indexOfKey(token.text.codePointAt(0)) < 0
                    ) {
                        directByCharacter[token.text.codePointAt(0)] = token.key
                    }
                }

                val directOutputs = directTokens.mapTo(hashSetOf(), OutputToken::text)
                val localeAliasClaims = linkedMapOf<String, GlideTypingKey?>()
                val fallbackAliasClaims = linkedMapOf<String, GlideTypingKey?>()
                fun claim(
                    claims: MutableMap<String, GlideTypingKey?>,
                    alias: String,
                    key: GlideTypingKey,
                ) {
                    if (alias.isEmpty() || alias in directOutputs || alias == key.output) return
                    if (!claims.containsKey(alias)) {
                        claims[alias] = key
                    } else if (claims[alias] != key) {
                        claims[alias] = null
                    }
                }
                directTokens.forEach { token ->
                    sequenceOf(
                        token.text.lowercase(subtype.primaryLocale),
                        token.text.uppercase(subtype.primaryLocale),
                    ).forEach { alias ->
                        claim(localeAliasClaims, geometryWord(alias), token.key)
                    }
                    if (token.text.codePointCount(0, token.text.length) == 1) {
                        codePointCaseAndBaseVariants(token.text.codePointAt(0)).forEach { variant ->
                            claim(
                                fallbackAliasClaims,
                                String(Character.toChars(variant)),
                                token.key,
                            )
                        }
                    }
                }
                val aliasClaims = fallbackAliasClaims
                    .filterKeys { it !in localeAliasClaims }
                    .toMutableMap()
                    .apply { putAll(localeAliasClaims) }

                val aliasesByCharacter = SparseArrayCompat<GlideTypingKey>()
                val aliasTokens = aliasClaims.mapNotNull { (text, key) ->
                    key?.let { OutputToken(text, it) }
                }
                aliasTokens.forEach { token ->
                    if (token.text.codePointCount(0, token.text.length) == 1) {
                        aliasesByCharacter[token.text.codePointAt(0)] = token.key
                    }
                }
                val tokenOrder = compareByDescending<OutputToken> { it.text.length }.thenBy { it.key.id }
                return KeyIndex(
                    directByCharacter = directByCharacter,
                    aliasesByCharacter = aliasesByCharacter,
                    directTokens = directTokens.groupBy { it.text.codePointAt(0) }
                        .mapValues { (_, tokens) -> tokens.sortedWith(tokenOrder) },
                    aliasTokens = aliasTokens.groupBy { it.text.codePointAt(0) }
                        .mapValues { (_, tokens) -> tokens.sortedWith(tokenOrder) },
                )
            }
        }
    }

    override fun addGesturePoint(position: GlideTypingGesture.Detector.Position) {
        if (!gesture.isEmpty) {
            val dx = gesture.getLastX() - position.x
            val dy = gesture.getLastY() - position.y

            if (dx * dx + dy * dy > distanceThresholdSquared) {
                gesture.addPoint(position.x, position.y)
            }
        } else {
            gesture.addPoint(position.x, position.y)
        }
    }

    override suspend fun setLayout(keys: List<GlideTypingKey>, subtype: Subtype) {
        val wordData = nlpManager.getGlideTypingWordData(subtype)
        val wordsChanged =
            wordDataSubtype != subtype || wordDataRevision != wordData.revision
        val layoutChanged = layoutSubtype != subtype || this.keys != keys
        if (!wordsChanged && !layoutChanged) return

        if (wordsChanged) {
            words = wordData.value
            wordDataSubtype = subtype
            wordDataRevision = wordData.revision
        }
        if (layoutChanged) {
            keyIndex = buildKeyIndex(keys, subtype)
            this.keys = keys.toList()
            layoutSubtype = subtype
            distanceThresholdSquared = (keys.firstOrNull()?.width?.div(4) ?: 0f).toInt()
            distanceThresholdSquared *= distanceThresholdSquared
        }
        lruSuggestionCache.evictAll()
        if (keys.isNotEmpty() && wordDataSubtype == layoutSubtype) {
            initializePruner()
        }
    }

    /**
     * Exists because Pruner requires both word data and layout are initialized,
     * however we don't know what order they're initialized in.
     */
    private fun initializePruner() {
        val currentSubtype = this.layoutSubtype!!
        val cacheKey = PrunerCacheKey(currentSubtype, wordDataRevision, keys.toList())
        val cached = prunerCache.get(cacheKey)
        if (cached == null) {
            this.pruner = Pruner(
                lengthThreshold = PRUNING_LENGTH_THRESHOLD,
                words = this.words,
                keyIndex = keyIndex,
            )
            prunerCache.put(cacheKey, this.pruner)
        } else {
            this.pruner = cached
        }
    }

    private val lruSuggestionCache = LruCache<Pair<Gesture, Int>, List<String>>(SUGGESTION_CACHE_SIZE)

    override suspend fun getSuggestions(maxSuggestionCount: Int): List<String> {
        val cacheKey = Pair(this.gesture, maxSuggestionCount)
        return when (val cached = lruSuggestionCache.get(cacheKey)) {
            null -> {
                val suggestions = unCachedGetSuggestions(maxSuggestionCount)
                lruSuggestionCache.put(
                    Pair(this.gesture.clone(), maxSuggestionCount),
                    suggestions,
                )

                suggestions
            }
            else -> {
                cached
            }
        }
    }

    private suspend fun unCachedGetSuggestions(maxSuggestionCount: Int): List<String> {
        val candidates = arrayListOf<String>()
        val candidateWeights = arrayListOf<Float>()
        val key = keys.firstOrNull() ?: return listOf()
        val radius = min(key.height, key.width)
        var remainingWords = pruner.pruneByExtremities(gesture, this.keys)
        val userGesture = gesture.resample(SAMPLING_POINTS)
        val normalizedUserGesture: Gesture = userGesture.normalizeByBoxSide()
        remainingWords = pruner.pruneByLength(gesture, remainingWords, keys)

        for (i in remainingWords.indices) {
            currentCoroutineContext().ensureActive()
            val word = remainingWords[i]
            val idealGestures = Gesture.generateIdealGestures(word, keyIndex)

            for (idealGesture in idealGestures) {
                val wordGesture = idealGesture.resample(SAMPLING_POINTS)
                val normalizedGesture: Gesture = wordGesture.normalizeByBoxSide()
                val shapeDistance = calcShapeDistance(normalizedGesture, normalizedUserGesture)
                val locationDistance = calcLocationDistance(wordGesture, userGesture)
                val shapeProbability = calcGaussianProbability(shapeDistance, 0.0f, SHAPE_STD)
                val locationProbability = calcGaussianProbability(locationDistance, 0.0f, LOCATION_STD * radius)
                val frequency =
                    255f * nlpManager.getFrequencyForWord(layoutSubtype!!, word).toFloat()
                val confidence = 1.0f / (shapeProbability * locationProbability * frequency)

                insertRankedCandidate(
                    candidates,
                    candidateWeights,
                    word,
                    confidence,
                    maxSuggestionCount,
                )
            }
        }

        return candidates
    }

    override fun clear() {
        gesture.clear()
    }

    private data class PrunerCacheKey(
        val subtype: Subtype,
        val wordDataRevision: Long,
        val keys: List<GlideTypingKey>,
    )

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

    private fun calcGaussianProbability(value: Float, mean: Float, standardDeviation: Float): Float {
        val factor = 1.0 / (standardDeviation * sqrt(2 * PI))
        val exponent = ((value - mean) / standardDeviation).toDouble().pow(2.0)
        val probability = factor * exp(-1.0 / 2 * exponent)
        return probability.toFloat()
    }

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

    class Pruner internal constructor(
        /**
         * The length difference between a user gesture and a word gesture above which a word will
         * be pruned.
         */
        private val lengthThreshold: Double,
        words: List<String>,
        private val keyIndex: KeyIndex,
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
            keys: Iterable<GlideTypingKey>,
        ): ArrayList<String> {
            val remainingWords = linkedSetOf<String>()
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
            return ArrayList(remainingWords)
        }

        /**
         * Finds the words whose ideal gesture length is within a certain threshold of the user
         * gesture's length.
         *
         * @param userGesture The current user gesture.
         * @param words A list of words to consider.
         * @return A list of words that remained after pruning the input list by length.
         */
        suspend fun pruneByLength(
            userGesture: Gesture,
            words: ArrayList<String>,
            keys: List<GlideTypingKey>,
        ): ArrayList<String> {
            val remainingWords = ArrayList<String>()

            val key = keys.firstOrNull() ?: return arrayListOf()
            val radius = min(key.height, key.width)
            val userLength = userGesture.getLength()
            for (word in words) {
                currentCoroutineContext().ensureActive()
                val idealGestures = Gesture.generateIdealGestures(word, keyIndex)
                for (wordIdealLength in getCachedIdealLengths(word, idealGestures)) {
                    if (abs(userLength - wordIdealLength) < lengthThreshold * radius) {
                        remainingWords.add(word)
                        break
                    }
                }
            }
            return remainingWords
        }

        private val cachedIdealLengths = ConcurrentHashMap<String, List<Float>>()
        private fun getCachedIdealLengths(word: String, idealGestures: List<Gesture>): List<Float> {
            return cachedIdealLengths.getOrPut(word) { idealGestures.map(Gesture::getLength) }
        }

        companion object {
            private fun getFirstKeyLastKeys(
                word: String,
                keyIndex: KeyIndex,
            ): Set<Pair<Int, Int>> {
                return keyIndex.tokenize(word).mapTo(linkedSetOf()) { keys ->
                    keys.first().id to keys.last().id
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
                x: Float, y: Float, n: Int, keys: Iterable<GlideTypingKey>
            ): Iterable<Int> {
                return keys.sortedBy { key ->
                    Gesture.distance(key.centerX, key.centerY, x, y)
                }.take(n).map(GlideTypingKey::id)
            }
        }

        init {
            synchronized(wordTree) {
                for (word in words) {
                    getFirstKeyLastKeys(word, keyIndex).forEach { keyPair ->
                        wordTree.getOrPut(keyPair) { arrayListOf() }.add(word)
                    }
                }
            }
        }
    }

    class Gesture(
        private val xs: FloatArray = FloatArray(MAX_SIZE),
        private val ys: FloatArray = FloatArray(MAX_SIZE),
        private var size: Int = 0,
    ) {
        companion object {
            // TODO: Find out optimal max size
            private const val MAX_SIZE = 500

            internal fun generateIdealGestures(
                word: String,
                keyIndex: KeyIndex,
            ): List<Gesture> {
                return keyIndex.tokenize(word).flatMap { wordKeys ->
                    val idealGesture = Gesture()
                    val idealGestureWithLoops = Gesture()
                    var previousKey: GlideTypingKey? = null
                    var hasLoops = false

                    wordKeys.forEach { key ->
                        // Add a little loop on a repeated physical key so that words such as
                        // "pool" and "poll" can still be distinguished.
                        if (previousKey == key) {
                            idealGestureWithLoops.addPoint(
                                key.centerX + key.width / 4.0f,
                                key.centerY + key.height / 4.0f,
                            )
                            idealGestureWithLoops.addPoint(
                                key.centerX + key.width / 4.0f,
                                key.centerY - key.height / 4.0f,
                            )
                            idealGestureWithLoops.addPoint(
                                key.centerX - key.width / 4.0f,
                                key.centerY - key.height / 4.0f,
                            )
                            idealGestureWithLoops.addPoint(
                                key.centerX - key.width / 4.0f,
                                key.centerY + key.height / 4.0f,
                            )
                            hasLoops = true
                        }
                        idealGesture.addPoint(key.centerX, key.centerY)
                        idealGestureWithLoops.addPoint(key.centerX, key.centerY)
                        previousKey = key
                    }
                    if (hasLoops) listOf(idealGesture, idealGestureWithLoops) else listOf(idealGesture)
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
            resampledGesture.addPoint(xs[0], ys[0])
            var lastX = xs[0]
            var lastY = ys[0]
            var newX: Float
            var newY: Float
            var cumulativeError = 0.0f

            // otherwise nothing happens if size is only 1:
            if (this.size == 1) {
                for (i in 0 until SAMPLING_POINTS) {
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

        fun getFirstX(): Float = xs.getOrElse(0) { 0f }
        fun getFirstY(): Float = ys.getOrElse(0) { 0f }
        fun getLastX(): Float = xs.getOrElse(size - 1) { 0f }
        fun getLastY(): Float = ys.getOrElse(size - 1) { 0f }

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

        fun getX(i: Int): Float = xs.getOrElse(i) { 0f }
        fun getY(i: Int): Float = ys.getOrElse(i) { 0f }

        fun clone(): Gesture {
            return Gesture(xs.clone(), ys.clone(), size)
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

internal fun insertRankedCandidate(
    candidates: MutableList<String>,
    weights: MutableList<Float>,
    word: String,
    weight: Float,
    limit: Int,
) {
    val duplicateIndex = candidates.indexOf(word)
    if (duplicateIndex >= 0) {
        if (weights[duplicateIndex] <= weight) return
        candidates.removeAt(duplicateIndex)
        weights.removeAt(duplicateIndex)
    }
    val insertionIndex = weights.binarySearch(weight).let { if (it < 0) -it - 1 else it }
    if (insertionIndex >= limit) return
    candidates.add(insertionIndex, word)
    weights.add(insertionIndex, weight)
    if (candidates.size > limit) {
        candidates.removeAt(limit)
        weights.removeAt(limit)
    }
}
