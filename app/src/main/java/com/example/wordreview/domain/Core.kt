package com.example.wordreview.domain

import com.example.wordreview.data.local.entity.MemoryStateEntity
import com.example.wordreview.data.local.entity.WordEntity
import kotlin.math.max
import kotlin.random.Random

const val MEMORY_RECOGNITION = "recognition"
const val MEMORY_SPELLING = "spelling"
const val STAGE_CHOICE = "choice"
const val STAGE_SPELLING = "spelling"
private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L

data class ImportResult(
    val libraryId: Long,
    val libraryName: String,
    val totalRows: Int,
    val newCount: Int,
    val existingCount: Int,
    val addedToLibraryCount: Int,
    val updatedMeaningCount: Int,
    val failedRowCount: Int
)

data class ReviewWord(
    val id: Long,
    val normalizedTerm: String,
    val displayTerm: String,
    val meaningText: String,
    val spellingDisabled: Boolean
)

data class ChoiceQuestion(
    val word: ReviewWord,
    val options: List<String>
)

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
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val field = StringBuilder()
        val row = mutableListOf<String>()
        var inQuotes = false
        var i = 0
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
                    if (row.any { it.isNotBlank() }) rows += row.toList()
                    row.clear()
                }
                else -> field.append(c)
            }
            i++
        }
        row += field.toString()
        if (row.any { it.isNotBlank() }) rows += row
        return rows
    }
}

object ReviewScheduler {
    fun pick(words: List<WordEntity>, states: List<MemoryStateEntity>, count: Int, now: Long): List<ReviewWord> {
        val stateByWord = states.associateBy { it.wordId }
        return words
            .sortedWith(
                compareByDescending<WordEntity> { word ->
                    val state = stateByWord[word.id]
                    val dueBoost = if (state == null || state.dueAt <= now) 1_000_000L else 0L
                    dueBoost + (state?.lapseCount ?: 0) * 10_000L - (state?.successStreak ?: 0) * 1_000L
                }.thenBy { it.updatedAt }
            )
            .take(count)
            .map { it.toReviewWord() }
    }
}

object ChoiceOptionGenerator {
    fun makeQuestion(word: ReviewWord, allWords: List<WordEntity>): ChoiceQuestion? {
        val correctMeaning = word.meaningText.trim()
        if (correctMeaning.isBlank()) return null
        val distractors = allWords
            .map { it.meaningText.trim() }
            .filter { it.isNotBlank() && it != correctMeaning }
            .distinct()
            .shuffled()
            .take(2)
        if (distractors.size < 2) return null
        return ChoiceQuestion(word, (distractors + correctMeaning).shuffled())
    }
}

object MemoryUpdater {
    fun next(state: MemoryStateEntity?, wordId: Long, type: String, isCorrect: Boolean, now: Long): MemoryStateEntity {
        val base = state ?: MemoryStateEntity(wordId = wordId, type = type)
        return if (isCorrect) {
            val streak = base.successStreak + 1
            val nextInterval = when (streak) {
                1 -> 1
                2 -> 3
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

fun WordEntity.toReviewWord(): ReviewWord =
    ReviewWord(id, normalizedTerm, displayTerm, meaningText, spellingDisabled)

fun randomSessionId(): Long = Random.nextLong(1, Long.MAX_VALUE)
