package com.example.wordreview.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.wordreview.data.local.entity.LibraryWordEntity
import com.example.wordreview.data.local.entity.MemoryStateEntity
import com.example.wordreview.data.local.entity.WordEntity
import com.example.wordreview.data.local.entity.WordLibraryEntity
import com.example.wordreview.domain.MEMORY_RECOGNITION
import com.example.wordreview.domain.MEMORY_SPELLING
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryProgressTest {
    private lateinit var db: AppDatabase

    @Before fun createDatabase() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun closeDatabase() = db.close()

    @Test fun progressTracksBothStagesSharedMemoryAndReset() = runBlocking {
        val first = library("first")
        val second = library("second")
        val word = word("apple")
        add(first, word)
        add(second, word)
        val flow = db.wordLibraryDao().observeSummaries()
        assertEquals(0, flow.first().first().startedCount)
        db.memoryStateDao().insert(MemoryStateEntity(wordId = word, type = MEMORY_RECOGNITION, successStreak = 1, lastReviewedAt = 1))
        assertEquals(1, flow.first().first().startedCount)
        assertEquals(0, flow.first().first().learnedCount)

        val update = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { flow.first { summaries -> summaries.all { it.learnedCount == 1 } } }
        }
        db.memoryStateDao().insert(MemoryStateEntity(wordId = word, type = MEMORY_SPELLING, successStreak = 1, lastReviewedAt = 1))
        assertEquals(listOf(1, 1), update.await().map { it.learnedCount })
        db.memoryStateDao().deleteAll()
        assertEquals(listOf(0, 0), flow.first().map { it.startedCount })
        assertEquals(listOf(0, 0), flow.first().map { it.learnedCount })
    }

    @Test fun emptyLibrariesBlankMeaningsAndSpellingExemptionHaveConsistentTotals() = runBlocking {
        val id = library("library")
        var summary = db.wordLibraryDao().observeSummaries().first().single()
        assertEquals(0, summary.reviewableCount)
        assertEquals(0, summary.learnedCount)
        val skipped = word("phrase", disabled = true)
        add(id, skipped)
        add(id, word("blank"), meaning = "  ")
        db.memoryStateDao().insert(MemoryStateEntity(wordId = skipped, type = MEMORY_RECOGNITION, successStreak = 1, lastReviewedAt = 1))
        summary = db.wordLibraryDao().observeSummaries().first().single()
        assertEquals(2, summary.wordCount)
        assertEquals(1, summary.reviewableCount)
        assertEquals(1, summary.startedCount)
        assertEquals(1, summary.learnedCount)
    }

    private suspend fun library(name: String) = db.wordLibraryDao().insert(
        WordLibraryEntity(name = name, normalizedName = name, createdAt = 1, updatedAt = 1)
    )

    private suspend fun word(term: String, disabled: Boolean = false) = db.wordDao().insert(
        WordEntity(normalizedTerm = term, displayTerm = term, sourceLang = "en", targetLang = "zh",
            meaningText = "释义", spellingDisabled = disabled, createdAt = 1, updatedAt = 1)
    )

    private suspend fun add(library: Long, word: Long, meaning: String = "释义") =
        db.wordLibraryDao().insertMembership(LibraryWordEntity(library, word, "word", "en", "zh", meaning, 1, 1))
}
