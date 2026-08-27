package com.example.wordreview.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.wordreview.data.local.AppDatabase
import com.example.wordreview.data.local.entity.MemoryStateEntity
import com.example.wordreview.data.local.entity.ReviewMemorySnapshotEntity
import com.example.wordreview.data.local.entity.ReviewProgressEntity
import com.example.wordreview.data.local.entity.ReviewSessionEntity
import com.example.wordreview.data.local.entity.WordEntity
import com.example.wordreview.domain.MEMORY_RECOGNITION
import com.example.wordreview.domain.ReviewSummary
import com.example.wordreview.domain.STAGE_CHOICE
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewMemoryResetTest {
    private lateinit var db: AppDatabase

    @Before
    fun createDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun resetMemoryClearsLearningStateAndActiveProgressButKeepsWords() = runBlocking {
        val wordId = db.wordDao().insert(
            WordEntity(
                normalizedTerm = "accept",
                displayTerm = "accept",
                sourceLang = "英语",
                targetLang = "中文",
                meaningText = "接受",
                spellingDisabled = true,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        db.memoryStateDao().insert(
            MemoryStateEntity(
                wordId = wordId,
                type = MEMORY_RECOGNITION,
                intervalDays = 9,
                dueAt = 99L,
                lapseCount = 2,
                successStreak = 4
            )
        )
        val sessionId = db.reviewSessionDao().insert(
            ReviewSessionEntity(
                startedAt = 1L,
                plannedCount = 1,
                actualCount = 1,
                status = "active",
                updatedAt = 1L
            )
        )
        db.reviewProgressDao().insert(
            ReviewProgressEntity(
                sessionId = sessionId,
                stage = "choice",
                roundIndex = 1,
                currentIndex = 0,
                roundWordIds = wordId.toString(),
                wrongWordIds = ""
            )
        )
        db.reviewMemorySnapshotDao().insertAll(
            listOf(
                ReviewMemorySnapshotEntity(
                    sessionId = sessionId,
                    wordId = wordId,
                    type = MEMORY_RECOGNITION,
                    existed = true,
                    easeFactor = 2.5,
                    intervalDays = 9,
                    dueAt = 99L,
                    lapseCount = 2,
                    successStreak = 4,
                    lastReviewedAt = 50L
                )
            )
        )

        val result = ReviewRepository(db).resetMemory()

        assertEquals(1, result.clearedMemoryStateCount)
        assertEquals(1, result.abandonedSessionCount)
        assertNull(db.memoryStateDao().getState(wordId, MEMORY_RECOGNITION))
        assertNull(db.reviewSessionDao().getActiveSession())
        assertEquals(emptyList<ReviewProgressEntity>(), db.reviewProgressDao().getBySession(sessionId))
        assertEquals(emptyList<ReviewMemorySnapshotEntity>(), db.reviewMemorySnapshotDao().getBySession(sessionId))
        assertNotNull(db.wordDao().getById(wordId))
        assertEquals(true, db.wordDao().getById(wordId)?.spellingDisabled)
    }

    @Test
    fun saveAnswerIsIdempotentForTheSameQuestion() = runBlocking {
        val wordId = insertWord()
        val sessionId = insertSession()
        val repository = ReviewRepository(db)

        repeat(2) {
            repository.saveAnswer(
                sessionId = sessionId,
                wordId = wordId,
                stage = STAGE_CHOICE,
                roundIndex = 1,
                isCorrect = true,
                answer = "接受",
                correctAnswer = "接受"
            )
        }

        assertEquals(1, db.reviewResultDao().getResultsBySession(sessionId).size)
        assertEquals(1, db.memoryStateDao().getState(wordId, MEMORY_RECOGNITION)?.successStreak)
    }

    @Test
    fun finishSessionRemovesResumeOnlyData() = runBlocking {
        val wordId = insertWord()
        val sessionId = insertSession()
        db.reviewProgressDao().insert(
            ReviewProgressEntity(
                sessionId = sessionId,
                stage = STAGE_CHOICE,
                roundIndex = 1,
                currentIndex = 0,
                roundWordIds = wordId.toString(),
                wrongWordIds = ""
            )
        )
        db.reviewMemorySnapshotDao().insertAll(
            listOf(
                ReviewMemorySnapshotEntity(
                    sessionId = sessionId,
                    wordId = wordId,
                    type = MEMORY_RECOGNITION,
                    existed = false,
                    easeFactor = null,
                    intervalDays = null,
                    dueAt = null,
                    lapseCount = null,
                    successStreak = null,
                    lastReviewedAt = null
                )
            )
        )

        ReviewRepository(db).finishSession(sessionId, ReviewSummary(1, 1, 0, 0, 0))

        assertEquals("completed", db.reviewSessionDao().getById(sessionId)?.status)
        assertEquals(emptyList<ReviewProgressEntity>(), db.reviewProgressDao().getBySession(sessionId))
        assertEquals(emptyList<ReviewMemorySnapshotEntity>(), db.reviewMemorySnapshotDao().getBySession(sessionId))
    }

    private suspend fun insertWord(): Long = db.wordDao().insert(
        WordEntity(
            normalizedTerm = "accept",
            displayTerm = "accept",
            sourceLang = "英语",
            targetLang = "中文",
            meaningText = "接受",
            createdAt = 1L,
            updatedAt = 1L
        )
    )

    private suspend fun insertSession(): Long = db.reviewSessionDao().insert(
        ReviewSessionEntity(
            startedAt = 1L,
            plannedCount = 1,
            actualCount = 1,
            status = "active",
            updatedAt = 1L
        )
    )
}
