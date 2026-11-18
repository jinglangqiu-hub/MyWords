package com.example.wordreview.domain

import com.example.wordreview.data.local.entity.MemoryStateEntity
import com.example.wordreview.data.local.entity.LibraryWordEntry
import com.example.wordreview.data.local.entity.WordEntity
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

const val MEMORY_RECOGNITION = "recognition"
const val MEMORY_SPELLING = "spelling"
const val STAGE_CHOICE = "choice"
const val STAGE_SPELLING = "spelling"
private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L

data class ImportResult(
    val totalRows: Int,
    val newCount: Int,
    val existingCount: Int,
    val updatedMeaningCount: Int,
    val failedRowCount: Int,
    val skippedHeaderCount: Int = 0,
    val failureReasons: Map<String, Int> = emptyMap(),
    val failureLineNumbers: List<Int> = emptyList(),
    val encodingName: String = "UTF-8"
)

data class CsvRow(val lineNumber: Int, val fields: List<String>)
data class CsvIssue(val lineNumber: Int, val reason: String)
data class CsvParseResult(val rows: List<CsvRow>, val issues: List<CsvIssue>)
data class DecodedText(val text: String, val encodingName: String)

data class ReviewWord(
    val id: Long,
    val normalizedTerm: String,
    val displayTerm: String,
    val meaningText: String,
    val spellingDisabled: Boolean
)

data class ChoiceQuestion(
    val word: ReviewWord,
    val options: List<ChoiceOption>
)

data class ChoiceOption(val meaning: String, val displayTerm: String)

data class SpellingQuestion(
    val word: ReviewWord
)

data class ReviewSummary(
    val actualCount: Int,
    val choiceRounds: Int,
    val choiceMistakes: Int,
    val spellingRounds: Int,
    val spellingMistakes: Int
)

object WordNormalizer {
    fun normalizeTerm(input: String): String =
        input.trim().replace(Regex("\\s+"), " ").lowercase()

    fun mergeMeaning(current: String, incoming: String): Pair<String, Boolean> {
        val existing = current.split('；').map { it.trim() }.filter { it.isNotBlank() }
        val incomingParts = incoming.split('；').map { it.trim() }.filter { it.isNotBlank() }
        val merged = existing.toMutableList()
        var changed = false
        incomingParts.forEach { part ->
            if (merged.none { it == part }) {
                merged += part
                changed = true
            }
        }
        return merged.joinToString("；") to changed
    }
}

object CsvParser {
    fun decode(bytes: ByteArray): DecodedText {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return DecodedText(bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8), "UTF-8 BOM")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return DecodedText(bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE), "UTF-16LE")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return DecodedText(bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE), "UTF-16BE")
        }
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            DecodedText(utf8.decode(ByteBuffer.wrap(bytes)).toString(), "UTF-8")
        } catch (_: Exception) {
            DecodedText(bytes.toString(Charset.forName("GB18030")), "GB18030")
        }
    }

    fun parse(text: String): CsvParseResult {
        val rows = mutableListOf<CsvRow>()
        val issues = mutableListOf<CsvIssue>()
        val field = StringBuilder()
        val row = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        var lineNumber = 1
        var rowStartLine = 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && inQuotes && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    row += field.toString()
                    field.clear()
                }
                (c == '\n' || c == '\r') && !inQuotes -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += field.toString()
                    field.clear()
                    if (row.any { it.isNotBlank() }) rows += CsvRow(rowStartLine, row.toList())
                    row.clear()
                    lineNumber++
                    rowStartLine = lineNumber
                }
                else -> {
                    field.append(c)
                    if ((c == '\n' || c == '\r') && inQuotes) {
                        if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') field.append(text[++i])
                        lineNumber++
                    }
                }
            }
            i++
        }
        if (inQuotes) {
            issues += CsvIssue(rowStartLine, "引号未闭合")
        } else {
            row += field.toString()
            if (row.any { it.isNotBlank() }) rows += CsvRow(rowStartLine, row.toList())
        }
        return CsvParseResult(rows, issues)
    }

    fun isRecognizedHeader(fields: List<String>): Boolean {
        if (fields.size < 4) return false
        val normalized = fields.take(4).map { it.trim().lowercase() }
        val known = setOf(
            "source language", "target language", "source text", "target text",
            "源语言", "目标语言", "原文", "译文", "单词", "英文", "释义", "中文", "term", "word", "meaning"
        )
        val headerHits = normalized.count { it in known }
        return headerHits >= 2 && normalized[2] in setOf("source text", "原文", "单词", "英文", "term", "word")
    }
}

object ReviewScheduler {
    fun pick(words: List<LibraryWordEntry>, states: List<MemoryStateEntity>, count: Int, now: Long): List<ReviewWord> {
        val statesByWord = states.groupBy { it.wordId }
        fun relevantStates(word: LibraryWordEntry) = statesByWord[word.wordId].orEmpty()
            .filter { it.type == MEMORY_RECOGNITION || (!word.spellingDisabled && it.type == MEMORY_SPELLING) }
        // Due/new words come first. Within each group rotate by last exposure,
        // so a historic lapse cannot monopolize successive sessions.
        fun lastReviewed(word: LibraryWordEntry) = relevantStates(word).mapNotNull { it.lastReviewedAt }.maxOrNull()
        fun dueAt(word: LibraryWordEntry): Long {
            val relevant = relevantStates(word)
            val expected = if (word.spellingDisabled) 1 else 2
            return if (relevant.size < expected) 0L else relevant.minOf { it.dueAt }
        }
        return words
            .filter { lastReviewed(it) == null || dueAt(it) <= now }
            .sortedWith(
                compareBy<LibraryWordEntry> { lastReviewed(it) ?: Long.MIN_VALUE }
                    .thenBy { dueAt(it) }
                    .thenBy { it.addedAt }
                    .thenBy { it.wordId }
            )
            .take(count)
            .map { it.toReviewWord() }
    }
}

object ChoiceOptionGenerator {
    fun makeQuestion(word: ReviewWord, allWords: List<LibraryWordEntry>): ChoiceQuestion? {
        return makePreparedQuestion(word, findDistractors(word, prepareCandidates(allWords)))
    }

    fun prepareDistractors(
        words: List<ReviewWord>,
        allWords: List<LibraryWordEntry>
    ): Map<Long, List<ChoiceOption>> {
        val candidates = prepareCandidates(allWords)
        return words.distinctBy { it.id }.associate { word ->
            word.id to findDistractors(word, candidates)
        }
    }

    fun makePreparedQuestion(word: ReviewWord, distractors: List<ChoiceOption>): ChoiceQuestion? {
        val correctMeaning = word.meaningText.trim()
        if (correctMeaning.isBlank()) return null
        val distinctDistractors = distractors
            .filter { it.meaning.isNotBlank() && !meaningsOverlap(it.meaning, correctMeaning) }
            .distinctBy { meaningParts(it.meaning) }
            .take(2)
        if (distinctDistractors.isEmpty()) return null
        return ChoiceQuestion(word, (distinctDistractors + ChoiceOption(correctMeaning, word.displayTerm)).shuffled())
    }

    private fun prepareCandidates(allWords: List<LibraryWordEntry>): List<PreparedCandidate> =
        allWords.map { candidate ->
            PreparedCandidate(
                wordId = candidate.wordId,
                meaning = candidate.meaningText.trim(),
                displayTerm = candidate.displayTerm,
                normalizedTerm = candidate.normalizedTerm,
                familyRoots = familyRoots(candidate.normalizedTerm),
                meaningParts = meaningParts(candidate.meaningText),
                comparableTerm = ComparableTerm.from(candidate.normalizedTerm)
            )
        }

    private fun findDistractors(
        word: ReviewWord,
        candidates: List<PreparedCandidate>
    ): List<ChoiceOption> {
        val correctMeaning = word.meaningText.trim()
        if (correctMeaning.isBlank()) return emptyList()
        val targetTerm = ComparableTerm.from(word.normalizedTerm)
        val targetRoots = familyRoots(word.normalizedTerm)
        val targetMeanings = meaningParts(correctMeaning)
        val ranked = candidates
            .asSequence()
            .filter {
                it.wordId != word.id && it.meaning.isNotBlank() &&
                    it.comparableTerm.value != targetTerm.value &&
                    it.familyRoots.none { root -> root in targetRoots } &&
                    !meaningsOverlap(it.meaningParts, targetMeanings)
            }
            .groupBy { it.meaning }
            .map { (_, candidates) ->
                candidates
                    .map { candidate ->
                        RankedDistractor(
                            meaning = candidate.meaning,
                            displayTerm = candidate.displayTerm,
                            normalizedTerm = candidate.normalizedTerm,
                            score = similarityScore(targetTerm, candidate.comparableTerm)
                        )
                    }
                    .maxByOrNull { it.score }!!
            }
            .sortedWith(
                compareByDescending<RankedDistractor> { it.score }
                    .thenBy { it.normalizedTerm }
            )
        val selected = mutableListOf<ChoiceOption>()
        for (candidate in ranked) {
            if (selected.none { meaningsOverlap(it.meaning, candidate.meaning) }) {
                selected += ChoiceOption(candidate.meaning, candidate.displayTerm)
                if (selected.size == 2) break
            }
        }
        return selected
    }

    // Conservative local heuristics: CSV data has no dictionary sense IDs.
    // Reject likely inflections/derivations instead of using them as traps.
    private fun familyRoots(term: String): Set<String> {
        val value = comparisonTerm(term)
        val roots = mutableSetOf(value)
        if (value.length > 4 && value.endsWith("e")) roots += value.dropLast(1)
        if (value.length > 4 && value.endsWith("y")) roots += value.dropLast(1)
        val suffixes = listOf("ization", "isation", "ation", "ition", "ness", "ment", "able", "ible",
            "ical", "ally", "ing", "ied", "ies", "ity", "ive", "ous", "ful", "less", "ial", "ion", "ly", "ed", "es", "er", "or", "al", "ic", "s")
        for (suffix in suffixes) {
            if (!value.endsWith(suffix) || value.length - suffix.length < 3) continue
            val root = value.dropLast(suffix.length)
            roots += root
            roots += root + "e"
            if (suffix == "ation") {
                roots += root + "ate"
                roots += root + "at"
            }
            if (root.endsWith("i")) roots += root.dropLast(1) + "y"
            if (root.length > 3 && root.last() == root[root.lastIndex - 1]) roots += root.dropLast(1)
        }
        return roots
    }

    private val partOfSpeech = Regex("(?i)(?<![a-z])(?:adj|adv|noun|verb|adjective|adverb|pron|prep|conj|interj|vt|vi|n|v|a)\\s*\\.")
    private val annotation = Regex("[（(][^）)]*[）)]")
    private val meaningSeparator = Regex("[;；,，、/\\n。]")
    private fun meaningParts(meaning: String): Set<String> =
        partOfSpeech.replace(Normalizer.normalize(meaning, Normalizer.Form.NFKC).lowercase(Locale.ROOT), " ")
            .replace(annotation, " ")
            .split(meaningSeparator)
            .map { part -> part.filter { it.isLetterOrDigit() }.trimEnd('的', '地') }
            .filter { it.isNotEmpty() }.toSet()

    private fun meaningsOverlap(left: String, right: String): Boolean =
        meaningsOverlap(meaningParts(left), meaningParts(right))

    private fun meaningsOverlap(left: Set<String>, right: Set<String>): Boolean =
        left.any { a -> right.any { b ->
            a == b || (minOf(a.length, b.length) >= 2 && (a.contains(b) || b.contains(a)))
        } }

    private data class PreparedCandidate(
        val wordId: Long,
        val meaning: String,
        val displayTerm: String,
        val normalizedTerm: String,
        val familyRoots: Set<String>,
        val meaningParts: Set<String>,
        val comparableTerm: ComparableTerm
    )

    private data class ComparableTerm(val value: String, val bigrams: Set<String>) {
        companion object {
            fun from(term: String): ComparableTerm {
                val value = comparisonTerm(term)
                return ComparableTerm(value, bigrams(value))
            }
        }
    }

    private data class RankedDistractor(
        val meaning: String,
        val displayTerm: String,
        val normalizedTerm: String,
        val score: Int
    )

    /**
     * Scores visual spelling similarity. Edit distance is the main signal; shared
     * letter pairs, prefixes/suffixes and similar lengths break close matches.
     */
    private fun similarityScore(first: ComparableTerm, second: ComparableTerm): Int {
        val left = first.value
        val right = second.value
        if (left.isEmpty() || right.isEmpty()) return 0

        val longest = maxOf(left.length, right.length)
        val editSimilarity = (longest - damerauLevenshtein(left, right)) * 1_000 / longest
        val bigramSimilarity = diceCoefficient(first.bigrams, second.bigrams)
        val prefixSimilarity = commonPrefixLength(left, right) * 150 / longest
        val suffixSimilarity = commonSuffixLength(left, right) * 100 / longest
        val lengthSimilarity = (longest - kotlin.math.abs(left.length - right.length)) * 100 / longest
        val sameInitialBonus = if (left.first() == right.first()) 50 else 0

        return editSimilarity + bigramSimilarity * 4 + prefixSimilarity + suffixSimilarity +
            lengthSimilarity + sameInitialBonus
    }

    private fun comparisonTerm(term: String): String =
        term.lowercase().filter { it.isLetterOrDigit() }

    private fun bigrams(term: String): Set<String> =
        if (term.length < 2) setOf(term) else term.windowed(2).toSet()

    private fun diceCoefficient(left: Set<String>, right: Set<String>): Int {
        if (left.isEmpty() && right.isEmpty()) return 100
        return 200 * left.intersect(right).size / (left.size + right.size)
    }

    private fun commonPrefixLength(left: String, right: String): Int =
        left.zip(right).takeWhile { (a, b) -> a == b }.size

    private fun commonSuffixLength(left: String, right: String): Int =
        left.reversed().zip(right.reversed()).takeWhile { (a, b) -> a == b }.size

    private fun damerauLevenshtein(left: String, right: String): Int {
        val distances = Array(left.length + 1) { IntArray(right.length + 1) }
        for (i in 0..left.length) distances[i][0] = i
        for (j in 0..right.length) distances[0][j] = j

        for (i in 1..left.length) {
            for (j in 1..right.length) {
                val substitutionCost = if (left[i - 1] == right[j - 1]) 0 else 1
                var distance = minOf(
                    distances[i - 1][j] + 1,
                    distances[i][j - 1] + 1,
                    distances[i - 1][j - 1] + substitutionCost
                )
                if (i > 1 && j > 1 && left[i - 1] == right[j - 2] && left[i - 2] == right[j - 1]) {
                    distance = minOf(distance, distances[i - 2][j - 2] + 1)
                }
                distances[i][j] = distance
            }
        }
        return distances[left.length][right.length]
    }
}

object MemoryUpdater {
    fun next(state: MemoryStateEntity?, wordId: Long, type: String, isCorrect: Boolean, now: Long): MemoryStateEntity {
        val base = state ?: MemoryStateEntity(wordId = wordId, type = type)
        return if (isCorrect) {
            val streak = base.successStreak + 1
            val nextInterval = when (streak) {
                1 -> if (type == MEMORY_SPELLING && base.lapseCount > 0) 3 else 1
                2 -> if (type == MEMORY_SPELLING && base.lapseCount > 0) max(5, base.intervalDays * 2) else 3
                else -> max(5, (base.intervalDays * base.easeFactor).toInt())
            }
            base.copy(
                easeFactor = (base.easeFactor + 0.08).coerceAtMost(3.0),
                intervalDays = nextInterval,
                dueAt = now + nextInterval * ONE_DAY_MS,
                successStreak = streak,
                lastReviewedAt = now
            )
        } else {
            base.copy(
                easeFactor = (base.easeFactor - 0.2).coerceAtLeast(1.3),
                intervalDays = 1,
                dueAt = now + ONE_DAY_MS,
                lapseCount = base.lapseCount + 1,
                successStreak = 0,
                lastReviewedAt = now
            )
        }
    }
}

object SpellingRetryPolicy {
    fun nextRound(wrongIds: List<Long>): List<Long> = wrongIds.distinct()

}

fun WordEntity.toReviewWord(): ReviewWord =
    ReviewWord(id, normalizedTerm, displayTerm, meaningText, spellingDisabled)

fun LibraryWordEntry.toReviewWord(): ReviewWord =
    ReviewWord(wordId, normalizedTerm, displayTerm, meaningText, spellingDisabled)

fun randomSessionId(): Long = Random.nextLong(1, Long.MAX_VALUE)
