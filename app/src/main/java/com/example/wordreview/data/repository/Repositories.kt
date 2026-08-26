package com.example.wordreview.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.example.wordreview.data.local.AppDatabase
import com.example.wordreview.data.local.entity.ImportBatchEntity
import com.example.wordreview.data.local.entity.MemoryStateEntity
import com.example.wordreview.data.local.entity.ReviewMemorySnapshotEntity
import com.example.wordreview.data.local.entity.ReviewProgressEntity
import com.example.wordreview.data.local.entity.ReviewResultEntity
import com.example.wordreview.data.local.entity.ReviewSessionEntity
import com.example.wordreview.data.local.entity.ReviewSessionWordEntity
import com.example.wordreview.data.local.entity.WordEntity
import com.example.wordreview.data.local.entity.WordLibraryEntity
import com.example.wordreview.data.local.entity.WordLibraryEntryEntity
import com.example.wordreview.data.local.entity.WordLibrarySummary
import com.example.wordreview.domain.CsvParser
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
import java.nio.charset.Charset

class WordRepository(private val db: AppDatabase) {
    fun observeAllWords(): Flow<List<WordEntity>> = db.wordDao().observeAllWords()
    fun searchWords(query: String): Flow<List<WordEntity>> = db.wordDao().searchWords(query)
    suspend fun countWords(): Int = db.wordDao().countWords()
    suspend fun getWord(id: Long): WordEntity? = db.wordDao().getById(id)
    suspend fun allWords(): List<WordEntity> = db.wordDao().getAllWords()
    suspend fun latestImport(): ImportBatchEntity? = db.importBatchDao().getLatestImport()
    fun observeLibraries(): Flow<List<WordLibrarySummary>> = db.wordLibraryDao().observeLibraries()
    fun observeLibraryWords(libraryId: Long): Flow<List<WordEntity>> = db.wordLibraryDao().observeWords(libraryId)
    suspend fun libraryWords(libraryId: Long): List<WordEntity> = db.wordLibraryDao().getWords(libraryId)

    suspend fun updateMeaning(wordId: Long, meaning: String) {
        val word = db.wordDao().getById(wordId) ?: return
        db.wordDao().update(word.copy(meaningText = meaning.trim(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun appendMeaning(wordId: Long, meaning: String) {
        val word = db.wordDao().getById(wordId) ?: return
        val (merged, changed) = WordNormalizer.mergeMeaning(word.meaningText, meaning)
        if (changed) db.wordDao().update(word.copy(meaningText = merged, updatedAt = System.currentTimeMillis()))
    }

    suspend fun setSpellingDisabled(wordId: Long, disabled: Boolean) {
        val word = db.wordDao().getById(wordId) ?: return
        db.wordDao().update(word.copy(spellingDisabled = disabled, updatedAt = System.currentTimeMillis()))
    }

    suspend fun memoryStates(wordId: Long): Pair<MemoryStateEntity?, MemoryStateEntity?> =
        db.memoryStateDao().getState(wordId, MEMORY_RECOGNITION) to
            db.memoryStateDao().getState(wordId, MEMORY_SPELLING)
}

class ImportRepository(private val db: AppDatabase, private val contentResolver: ContentResolver) {
    suspend fun importGoogleTranslateCsv(uri: Uri, requestedLibraryName: String): ImportResult {
        val text = contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charset.forName("UTF-8"))
        } ?: error("Cannot open selected file")

        val rows = CsvParser.parse(text)
        var totalRows = 0
        var newCount = 0
        var existingCount = 0
        var addedToLibraryCount = 0
        var updatedMeaningCount = 0
        var failedRowCount = 0
        val now = System.currentTimeMillis()
        val fileName = displayName(uri)
        val libraryName = requestedLibraryName.trim().ifBlank {
            fileName.substringBeforeLast('.').trim().ifBlank { "未命名词库" }
        }
        var libraryId = 0L

        db.withTransaction {
            val existingLibrary = db.wordLibraryDao().getByName(libraryName)
            libraryId = existingLibrary?.id ?: db.wordLibraryDao().insert(
                WordLibraryEntity(name = libraryName, createdAt = now, updatedAt = now)
            )
            if (libraryId <= 0L) {
                libraryId = db.wordLibraryDao().getByName(libraryName)?.id
                    ?: error("Cannot create word library")
            }
            rows.forEach { row ->
                totalRows++
                if (row.size < 4) {
                    failedRowCount++
                    return@forEach
                }
                val sourceLang = row[0].trim()
                val targetLang = row[1].trim()
                val displayTerm = row[2].trim()
                val translation = row[3].trim()
                if (displayTerm.isBlank() || translation.isBlank()) {
                    failedRowCount++
                    return@forEach
                }
                val normalizedTerm = WordNormalizer.normalizeTerm(displayTerm)
                val existing = db.wordDao().getByNormalizedTerm(normalizedTerm)
                val wordId = if (existing == null) {
                    val insertedId = db.wordDao().insert(
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
                    db.memoryStateDao().insert(MemoryStateEntity(wordId = insertedId, type = MEMORY_RECOGNITION, dueAt = 0L))
                    db.memoryStateDao().insert(MemoryStateEntity(wordId = insertedId, type = MEMORY_SPELLING, dueAt = 0L))
                    newCount++
                    insertedId
                } else {
                    existingCount++
                    val (merged, changed) = WordNormalizer.mergeMeaning(existing.meaningText, translation)
                    if (changed) {
                        db.wordDao().update(existing.copy(meaningText = merged, updatedAt = now))
                        updatedMeaningCount++
                    }
                    existing.id
                }
                if (db.wordLibraryDao().addEntry(WordLibraryEntryEntity(libraryId, wordId, now)) != -1L) {
                    addedToLibraryCount++
                }
            }
            db.wordLibraryDao().getById(libraryId)?.let {
                db.wordLibraryDao().update(it.copy(updatedAt = now))
            }
            db.importBatchDao().insert(
                ImportBatchEntity(
                    fileName = fileName,
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
        return ImportResult(
            libraryId,
            libraryName,
            totalRows,
            newCount,
            existingCount,
            addedToLibraryCount,
            updatedMeaningCount,
            failedRowCount
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
    data class SavedProgress(
        val roundIndex: Int,
        val currentIndex: Int,
        val roundWordIds: List<Long>,
        val wrongWordIds: List<Long>
    )

    data class ActiveSession(
        val sessionId: Long,
        val libraryId: Long?,
        val currentStage: String,
        val words: List<ReviewWord>,
        val choiceProgress: SavedProgress?,
        val spellingProgress: SavedProgress?,
        val summary: ReviewSummary
    )

    suspend fun activeSession(): ActiveSession? =
        db.reviewSessionDao().getActiveSession()?.let { loadActiveSession(it) }

    suspend fun startSession(libraryId: Long, plannedCount: Int): ActiveSession {
        val words = db.wordLibraryDao().getWords(libraryId).filter { it.meaningText.trim().isNotBlank() }
        val states = db.memoryStateDao().getStatesByType(MEMORY_RECOGNITION)
        val picked = ReviewScheduler.pick(words, states, plannedCount, System.currentTimeMillis())
        val now = System.currentTimeMillis()
        var sessionId = 0L
        db.withTransaction {
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
            val next = MemoryUpdater.next(current, wordId, type, isCorrect, now)
            if (current == null) db.memoryStateDao().insert(next) else db.memoryStateDao().update(next)
        }
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
            db.reviewSessionDao().update(
                session.copy(status = "abandoned", endedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            )
        }
    }

    suspend fun finishSession(sessionId: Long, summary: ReviewSummary) {
        val session = db.reviewSessionDao().getById(sessionId) ?: return
        db.reviewSessionDao().update(
            session.copy(
                status = "completed",
                endedAt = System.currentTimeMillis(),
                choiceRounds = summary.choiceRounds,
                choiceMistakes = summary.choiceMistakes,
                spellingRounds = summary.spellingRounds,
                spellingMistakes = summary.spellingMistakes,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun hasAnswer(sessionId: Long, stage: String, roundIndex: Int, wordId: Long): Boolean =
        db.reviewResultDao().getAnswer(sessionId, stage, roundIndex, wordId) != null

    private suspend fun loadActiveSession(session: ReviewSessionEntity): ActiveSession {
        val wordIds = db.reviewSessionWordDao().getBySession(session.id).map { it.wordId }
        val wordsById = db.wordDao().getAllWords().associateBy { it.id }
        val reviewWords = wordIds.mapNotNull { wordsById[it]?.toReviewWord() }
        return ActiveSession(
            sessionId = session.id,
            libraryId = session.libraryId,
            currentStage = session.currentStage,
            words = reviewWords,
            choiceProgress = db.reviewProgressDao().get(session.id, STAGE_CHOICE)?.toSavedProgress(),
            spellingProgress = db.reviewProgressDao().get(session.id, STAGE_SPELLING)?.toSavedProgress(),
            summary = ReviewSummary(
                actualCount = session.actualCount,
                choiceRounds = session.choiceRounds,
                choiceMistakes = session.choiceMistakes,
                spellingRounds = session.spellingRounds,
                spellingMistakes = session.spellingMistakes
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
