package com.example.wordreview.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.example.wordreview.data.local.AppDatabase
import com.example.wordreview.data.local.entity.ImportBatchEntity
import com.example.wordreview.data.local.entity.LibraryWordEntity
import com.example.wordreview.data.local.entity.LibraryWordEntry
import com.example.wordreview.data.local.entity.MemoryStateEntity
import com.example.wordreview.data.local.entity.ReviewMemorySnapshotEntity
import com.example.wordreview.data.local.entity.ReviewProgressEntity
import com.example.wordreview.data.local.entity.ReviewResultEntity
import com.example.wordreview.data.local.entity.ReviewSessionEntity
import com.example.wordreview.data.local.entity.ReviewSessionWordEntity
import com.example.wordreview.data.local.entity.WordEntity
import com.example.wordreview.data.local.entity.WordLibraryEntity
import com.example.wordreview.data.local.entity.WordLibrarySummary
import com.example.wordreview.domain.CsvParser
import com.example.wordreview.domain.ChoiceOptionGenerator
import com.example.wordreview.domain.ChoiceOption
import com.example.wordreview.domain.ImportResult
import com.example.wordreview.domain.MEMORY_RECOGNITION
import com.example.wordreview.domain.MEMORY_SPELLING
import com.example.wordreview.domain.MemoryUpdater
import com.example.wordreview.domain.ReviewSummary
import com.example.wordreview.domain.ReviewScheduler
import com.example.wordreview.domain.ReviewWord
import com.example.wordreview.domain.STAGE_CHOICE
import com.example.wordreview.domain.STAGE_SPELLING
import com.example.wordreview.domain.WordNormalizer
import com.example.wordreview.domain.toReviewWord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class WordRepository(private val db: AppDatabase) {
    fun observeLibraries(): Flow<List<WordLibrarySummary>> = db.wordLibraryDao().observeSummaries()
    fun observeWords(libraryId: Long): Flow<List<LibraryWordEntry>> = db.wordLibraryDao().observeWords(libraryId)
    fun searchWords(libraryId: Long, query: String): Flow<List<LibraryWordEntry>> =
        db.wordLibraryDao().searchWords(libraryId, query)

    suspend fun libraries(): List<WordLibraryEntity> = db.wordLibraryDao().getAll()
    suspend fun getLibrary(libraryId: Long): WordLibraryEntity? = db.wordLibraryDao().getById(libraryId)
    suspend fun countWords(libraryId: Long): Int = db.wordLibraryDao().countWords(libraryId)
    suspend fun getWord(libraryId: Long, wordId: Long): LibraryWordEntry? = db.wordLibraryDao().getWord(libraryId, wordId)
    suspend fun allWords(libraryId: Long): List<LibraryWordEntry> = db.wordLibraryDao().getWords(libraryId)
    suspend fun latestImport(): ImportBatchEntity? = db.importBatchDao().getLatestImport()
    suspend fun latestImport(libraryId: Long): ImportBatchEntity? = db.importBatchDao().getLatestImport(libraryId)

    suspend fun createLibrary(name: String): Long {
        val clean = validateLibraryName(name)
        val normalized = normalizeLibraryName(clean)
        require(db.wordLibraryDao().getByNormalizedName(normalized) == null) { "词库名称已存在。" }
        val now = System.currentTimeMillis()
        return db.wordLibraryDao().insert(
            WordLibraryEntity(name = clean, normalizedName = normalized, createdAt = now, updatedAt = now)
        )
    }

    suspend fun renameLibrary(libraryId: Long, name: String) {
        val library = db.wordLibraryDao().getById(libraryId) ?: error("词库不存在。")
        val clean = validateLibraryName(name)
        val normalized = normalizeLibraryName(clean)
        val duplicate = db.wordLibraryDao().getByNormalizedName(normalized)
        require(duplicate == null || duplicate.id == libraryId) { "词库名称已存在。" }
        db.wordLibraryDao().update(library.copy(name = clean, normalizedName = normalized, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteLibrary(libraryId: Long) {
        db.withTransaction {
            require(db.wordLibraryDao().countLibraries() > 1) { "至少需要保留一个词库。" }
            require(db.reviewSessionDao().getActiveSession(libraryId) == null) { "该词库有未完成的复习，请先继续或跳过。" }
            require(db.wordLibraryDao().getById(libraryId) != null) { "词库不存在。" }
            db.reviewSessionDao().deleteByLibrary(libraryId)
            db.importBatchDao().deleteByLibrary(libraryId)
            db.wordLibraryDao().delete(libraryId)
            db.wordDao().deleteOrphanWords()
        }
    }

    suspend fun updateMeaning(libraryId: Long, wordId: Long, meaning: String) {
        val membership = db.wordLibraryDao().getMembership(libraryId, wordId) ?: return
        val now = System.currentTimeMillis()
        db.withTransaction {
            db.wordLibraryDao().updateMembership(membership.copy(meaningText = meaning.trim(), updatedAt = now))
            db.wordLibraryDao().touch(libraryId, now)
        }
    }

    suspend fun setSpellingDisabled(wordId: Long, disabled: Boolean) {
        val word = db.wordDao().getById(wordId) ?: return
        db.wordDao().update(word.copy(spellingDisabled = disabled, updatedAt = System.currentTimeMillis()))
    }

    suspend fun memoryStates(wordId: Long): Pair<MemoryStateEntity?, MemoryStateEntity?> =
        db.memoryStateDao().getState(wordId, MEMORY_RECOGNITION) to
            db.memoryStateDao().getState(wordId, MEMORY_SPELLING)

    private fun validateLibraryName(name: String): String =
        name.trim().also { require(it.isNotBlank()) { "词库名称不能为空。" } }

    private fun normalizeLibraryName(name: String): String = name.trim().lowercase(Locale.ROOT)
}

class ImportRepository(private val db: AppDatabase, private val contentResolver: ContentResolver) {
    suspend fun importCsv(libraryId: Long, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        require(db.wordLibraryDao().getById(libraryId) != null) { "词库不存在。" }
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法打开所选文件。")
        val decoded = CsvParser.decode(bytes)
        val parsed = CsvParser.parse(decoded.text)
        var totalRows = parsed.issues.size
        var newCount = 0
        var existingCount = 0
        var updatedMeaningCount = 0
        var skippedHeaderCount = 0
        val failureReasons = linkedMapOf<String, Int>()
        val failureLines = mutableListOf<Int>()
        val now = System.currentTimeMillis()

        parsed.issues.forEach { issue ->
            failureReasons[issue.reason] = (failureReasons[issue.reason] ?: 0) + 1
            if (failureLines.size < 10) failureLines += issue.lineNumber
        }

        fun fail(line: Int, reason: String) {
            failureReasons[reason] = (failureReasons[reason] ?: 0) + 1
            if (failureLines.size < 10) failureLines += line
        }

        db.withTransaction {
            parsed.rows.forEachIndexed { index, csvRow ->
                val row = csvRow.fields
                if (index == 0 && CsvParser.isRecognizedHeader(row)) {
                    skippedHeaderCount++
                    return@forEachIndexed
                }
                totalRows++
                if (row.size < 4) {
                    fail(csvRow.lineNumber, "列数不足")
                    return@forEachIndexed
                }
                val sourceLang = row[0].trim()
                val targetLang = row[1].trim()
                val displayTerm = row[2].trim()
                val translation = row[3].trim()
                if (displayTerm.isBlank() || translation.isBlank()) {
                    fail(csvRow.lineNumber, if (displayTerm.isBlank()) "英文为空" else "释义为空")
                    return@forEachIndexed
                }
                val normalizedTerm = WordNormalizer.normalizeTerm(displayTerm)
                val existing = db.wordDao().getByNormalizedTerm(normalizedTerm)
                val wordId = if (existing == null) {
                    val createdId = db.wordDao().insert(
                        WordEntity(
                            normalizedTerm = normalizedTerm,
                            displayTerm = displayTerm,
                            sourceLang = sourceLang,
                            targetLang = targetLang,
                            meaningText = translation,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                    db.memoryStateDao().insert(MemoryStateEntity(wordId = createdId, type = MEMORY_RECOGNITION, dueAt = 0L))
                    db.memoryStateDao().insert(MemoryStateEntity(wordId = createdId, type = MEMORY_SPELLING, dueAt = 0L))
                    createdId
                } else {
                    existing.id
                }
                val membership = db.wordLibraryDao().getMembership(libraryId, wordId)
                if (membership == null) {
                    db.wordLibraryDao().insertMembership(
                        LibraryWordEntity(libraryId, wordId, displayTerm, sourceLang, targetLang, translation, now, now)
                    )
                    newCount++
                } else {
                    existingCount++
                    val (merged, changed) = WordNormalizer.mergeMeaning(membership.meaningText, translation)
                    if (changed) {
                        db.wordLibraryDao().updateMembership(membership.copy(meaningText = merged, updatedAt = now))
                        updatedMeaningCount++
                    }
                }
            }
            db.wordLibraryDao().touch(libraryId, now)
            val failedRowCount = failureReasons.values.sum()
            db.importBatchDao().insert(
                ImportBatchEntity(
                    fileName = displayName(uri),
                    importedAt = now,
                    totalRows = totalRows,
                    newCount = newCount,
                    existingCount = existingCount,
                    updatedMeaningCount = updatedMeaningCount,
                    failedRowCount = failedRowCount,
                    libraryId = libraryId
                )
            )
        }
        ImportResult(
            totalRows, newCount, existingCount, updatedMeaningCount, failureReasons.values.sum(),
            skippedHeaderCount, failureReasons, failureLines.distinct(), decoded.encodingName
        )
    }

    private fun displayName(uri: Uri): String {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && it.moveToFirst()) return it.getString(index)
        }
        return uri.lastPathSegment ?: "selected.csv"
    }
}

class ReviewRepository(private val db: AppDatabase) {
    data class MemoryResetResult(
        val clearedMemoryStateCount: Int,
        val abandonedSessionCount: Int
    )

    data class SavedProgress(
        val roundIndex: Int,
        val currentIndex: Int,
        val roundWordIds: List<Long>,
        val wrongWordIds: List<Long>
    )

    data class ActiveSession(
        val sessionId: Long,
        val libraryId: Long,
        val currentStage: String,
        val words: List<ReviewWord>,
        val choiceProgress: SavedProgress?,
        val spellingProgress: SavedProgress?,
        val summary: ReviewSummary,
        val choiceDistractors: Map<Long, List<ChoiceOption>> = emptyMap(),
        val spellingWordIds: Set<Long> = emptySet()
    )

    suspend fun activeSession(): ActiveSession? =
        db.reviewSessionDao().getActiveSession()?.let { loadActiveSession(it) }

    suspend fun startSession(libraryId: Long, plannedCount: Int): ActiveSession {
        require(db.wordLibraryDao().getById(libraryId) != null) { "词库不存在。" }
        val words = db.wordLibraryDao().getWords(libraryId).filter { it.meaningText.trim().isNotBlank() }
        val states = db.memoryStateDao().getStatesByType(MEMORY_RECOGNITION) +
            db.memoryStateDao().getStatesByType(MEMORY_SPELLING)
        val picked = ReviewScheduler.pick(words, states, plannedCount, System.currentTimeMillis())
        require(picked.isNotEmpty()) { "当前词库没有新词或到期单词，请到期后再来复习。" }
        val distractors = withContext(Dispatchers.Default) {
            ChoiceOptionGenerator.prepareDistractors(picked, words)
        }
        require(picked.all { distractors[it.id].orEmpty().isNotEmpty() }) {
            "部分单词找不到无歧义的干扰项，请补充释义差异较大的单词后再复习。"
        }
        val now = System.currentTimeMillis()
        var sessionId = 0L
        db.withTransaction {
            require(db.reviewSessionDao().getActiveSession() == null) { "已有未完成的复习。" }
            sessionId = db.reviewSessionDao().insert(
                ReviewSessionEntity(
                    startedAt = now,
                    plannedCount = plannedCount,
                    actualCount = picked.size,
                    status = "active",
                    currentStage = STAGE_CHOICE,
                    updatedAt = now,
                    libraryId = libraryId
                )
            )
            db.reviewSessionWordDao().insertAll(
                picked.mapIndexed { index, word ->
                    ReviewSessionWordEntity(sessionId = sessionId, wordId = word.id, position = index)
                }
            )
            db.reviewMemorySnapshotDao().insertAll(makeSnapshots(sessionId, picked.map { it.id }))
            saveProgressEntity(
                sessionId = sessionId,
                stage = STAGE_CHOICE,
                progress = SavedProgress(1, 0, picked.map { it.id }, emptyList())
            )
        }
        return loadActiveSession(db.reviewSessionDao().getById(sessionId) ?: error("Session was not created"))
            .copy(choiceDistractors = distractors)
    }

    suspend fun saveProgress(sessionId: Long, stage: String, progress: SavedProgress) {
        db.withTransaction {
            saveProgressEntity(sessionId, stage, progress)
            val session = db.reviewSessionDao().getById(sessionId) ?: return@withTransaction
            db.reviewSessionDao().update(session.copy(currentStage = stage, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun saveSessionState(sessionId: Long, stage: String, summary: ReviewSummary) {
        val session = db.reviewSessionDao().getById(sessionId) ?: return
        db.reviewSessionDao().update(
            session.copy(
                currentStage = stage,
                choiceRounds = summary.choiceRounds,
                choiceMistakes = summary.choiceMistakes,
                spellingRounds = summary.spellingRounds,
                spellingMistakes = summary.spellingMistakes,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun saveAnswer(
        sessionId: Long,
        wordId: Long,
        stage: String,
        roundIndex: Int,
        isCorrect: Boolean,
        answer: String?,
        correctAnswer: String
    ) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val session = db.reviewSessionDao().getById(sessionId)
            if (session?.status != "active") return@withTransaction
            if (db.reviewResultDao().getAnswer(sessionId, stage, roundIndex, wordId) != null) {
                return@withTransaction
            }
            db.reviewResultDao().insert(
                ReviewResultEntity(
                    sessionId = sessionId,
                    wordId = wordId,
                    stage = stage,
                    roundIndex = roundIndex,
                    isCorrect = isCorrect,
                    answer = answer,
                    correctAnswer = correctAnswer,
                    createdAt = now
                )
            )
            val type = if (stage == "choice") MEMORY_RECOGNITION else MEMORY_SPELLING
            val current = db.memoryStateDao().getState(wordId, type)
            val alreadyReviewed = db.reviewResultDao().hasOtherRoundAnswer(sessionId, stage, roundIndex, wordId)
            // Immediate correction is practice, not another day of retention.
            // Count a lapse once per session and keep its short review interval.
            val next = if (alreadyReviewed && current != null) current.copy(lastReviewedAt = now)
                else MemoryUpdater.next(current, wordId, type, isCorrect, now)
            if (current == null) db.memoryStateDao().insert(next) else db.memoryStateDao().update(next)
        }
    }

    suspend fun resetMemory(): MemoryResetResult {
        val now = System.currentTimeMillis()
        var clearedMemoryStateCount = 0
        var abandonedSessionCount = 0
        db.withTransaction {
            abandonedSessionCount = db.reviewSessionDao().abandonAllActive(now)
            db.reviewProgressDao().deleteAll()
            db.reviewMemorySnapshotDao().deleteAll()
            clearedMemoryStateCount = db.memoryStateDao().deleteAll()
        }
        return MemoryResetResult(clearedMemoryStateCount, abandonedSessionCount)
    }

    suspend fun restartActiveSession(): ActiveSession? {
        val session = db.reviewSessionDao().getActiveSession() ?: return null
        db.withTransaction {
            rollbackMemory(session.id)
            db.reviewResultDao().deleteBySession(session.id)
            db.reviewProgressDao().deleteBySession(session.id)
            val wordIds = db.reviewSessionWordDao().getBySession(session.id).map { it.wordId }
            saveProgressEntity(session.id, STAGE_CHOICE, SavedProgress(1, 0, wordIds, emptyList()))
            db.reviewSessionDao().update(
                session.copy(
                    endedAt = null,
                    status = "active",
                    currentStage = STAGE_CHOICE,
                    choiceRounds = 0,
                    choiceMistakes = 0,
                    spellingRounds = 0,
                    spellingMistakes = 0,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        return activeSession()
    }

    suspend fun skipActiveSession() {
        val session = db.reviewSessionDao().getActiveSession() ?: return
        db.withTransaction {
            rollbackMemory(session.id)
            db.reviewResultDao().deleteBySession(session.id)
            db.reviewProgressDao().deleteBySession(session.id)
            db.reviewMemorySnapshotDao().deleteBySession(session.id)
            db.reviewSessionDao().update(
                session.copy(status = "abandoned", endedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            )
        }
    }

    suspend fun finishSession(sessionId: Long, summary: ReviewSummary) {
        db.withTransaction {
            val session = db.reviewSessionDao().getById(sessionId) ?: return@withTransaction
            val now = System.currentTimeMillis()
            db.reviewSessionDao().update(
                session.copy(
                    status = "completed",
                    endedAt = now,
                    choiceRounds = summary.choiceRounds,
                    choiceMistakes = summary.choiceMistakes,
                    spellingRounds = summary.spellingRounds,
                    spellingMistakes = summary.spellingMistakes,
                    updatedAt = now
                )
            )
            db.reviewProgressDao().deleteBySession(sessionId)
            db.reviewMemorySnapshotDao().deleteBySession(sessionId)
        }
    }

    suspend fun hasAnswer(sessionId: Long, stage: String, roundIndex: Int, wordId: Long): Boolean =
        db.reviewResultDao().getAnswer(sessionId, stage, roundIndex, wordId) != null

    private suspend fun loadActiveSession(session: ReviewSessionEntity): ActiveSession {
        val wordIds = db.reviewSessionWordDao().getBySession(session.id).map { it.wordId }
        val wordsById = db.wordLibraryDao().getWords(session.libraryId).associateBy { it.wordId }
        val reviewWords = wordIds.mapNotNull { wordsById[it]?.toReviewWord() }
        val results = db.reviewResultDao().getResultsBySession(session.id)
        // Use the pre-session snapshot: answering choice must not change which
        // spelling memories were due when this session began (also on resume).
        val spellingSnapshots = db.reviewMemorySnapshotDao().getBySession(session.id)
            .filter { it.type == MEMORY_SPELLING }.associateBy { it.wordId }
        val spellingIds = reviewWords.filterNot { it.spellingDisabled }.filter { word ->
            val snapshot = spellingSnapshots[word.id]
            snapshot == null || !snapshot.existed || snapshot.lastReviewedAt == null ||
                (snapshot.dueAt ?: 0L) <= session.startedAt
        }.map { it.id }.toSet()
        return ActiveSession(
            sessionId = session.id,
            libraryId = session.libraryId,
            currentStage = session.currentStage,
            words = reviewWords,
            spellingWordIds = spellingIds,
            choiceProgress = db.reviewProgressDao().get(session.id, STAGE_CHOICE)?.toSavedProgress(),
            spellingProgress = db.reviewProgressDao().get(session.id, STAGE_SPELLING)?.toSavedProgress(),
            summary = ReviewSummary(
                actualCount = session.actualCount,
                choiceRounds = session.choiceRounds,
                choiceMistakes = results.count { it.stage == STAGE_CHOICE && !it.isCorrect },
                spellingRounds = session.spellingRounds,
                spellingMistakes = results.count { it.stage == STAGE_SPELLING && !it.isCorrect }
            )
        )
    }

    private suspend fun makeSnapshots(sessionId: Long, wordIds: List<Long>): List<ReviewMemorySnapshotEntity> {
        val snapshots = mutableListOf<ReviewMemorySnapshotEntity>()
        wordIds.forEach { wordId ->
            listOf(MEMORY_RECOGNITION, MEMORY_SPELLING).forEach { type ->
                val state = db.memoryStateDao().getState(wordId, type)
                snapshots += ReviewMemorySnapshotEntity(
                    sessionId = sessionId,
                    wordId = wordId,
                    type = type,
                    existed = state != null,
                    easeFactor = state?.easeFactor,
                    intervalDays = state?.intervalDays,
                    dueAt = state?.dueAt,
                    lapseCount = state?.lapseCount,
                    successStreak = state?.successStreak,
                    lastReviewedAt = state?.lastReviewedAt
                )
            }
        }
        return snapshots
    }

    private suspend fun rollbackMemory(sessionId: Long) {
        db.reviewMemorySnapshotDao().getBySession(sessionId).forEach { snapshot ->
            if (!snapshot.existed) {
                db.memoryStateDao().deleteState(snapshot.wordId, snapshot.type)
                return@forEach
            }
            val restored = MemoryStateEntity(
                wordId = snapshot.wordId,
                type = snapshot.type,
                easeFactor = snapshot.easeFactor ?: 2.5,
                intervalDays = snapshot.intervalDays ?: 0,
                dueAt = snapshot.dueAt ?: 0L,
                lapseCount = snapshot.lapseCount ?: 0,
                successStreak = snapshot.successStreak ?: 0,
                lastReviewedAt = snapshot.lastReviewedAt
            )
            val current = db.memoryStateDao().getState(snapshot.wordId, snapshot.type)
            if (current == null) {
                db.memoryStateDao().insert(restored)
            } else {
                db.memoryStateDao().update(restored.copy(id = current.id))
            }
        }
    }

    private suspend fun saveProgressEntity(sessionId: Long, stage: String, progress: SavedProgress) {
        val current = db.reviewProgressDao().get(sessionId, stage)
        val entity = ReviewProgressEntity(
            id = current?.id ?: 0,
            sessionId = sessionId,
            stage = stage,
            roundIndex = progress.roundIndex,
            currentIndex = progress.currentIndex,
            roundWordIds = progress.roundWordIds.joinToString(","),
            wrongWordIds = progress.wrongWordIds.joinToString(",")
        )
        if (current == null) db.reviewProgressDao().insert(entity) else db.reviewProgressDao().update(entity)
    }

    private fun ReviewProgressEntity.toSavedProgress(): SavedProgress =
        SavedProgress(roundIndex, currentIndex, roundWordIds.toIdList(), wrongWordIds.toIdList())

    private fun String.toIdList(): List<Long> =
        split(',').mapNotNull { it.trim().takeIf(String::isNotBlank)?.toLongOrNull() }
}
