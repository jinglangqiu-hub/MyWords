package com.example.wordreview.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate2To3_placesExistingDataInDefaultLibrary() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                """INSERT INTO words(id, normalizedTerm, displayTerm, sourceLang, targetLang, meaningText, spellingDisabled, createdAt, updatedAt)
                   VALUES(7, 'apple', 'Apple', '英语', '中文', '苹果', 0, 10, 20)"""
            )
            execSQL(
                """INSERT INTO memory_states(id, wordId, type, easeFactor, intervalDays, dueAt, lapseCount, successStreak, lastReviewedAt)
                   VALUES(8, 7, 'recognition', 2.5, 3, 100, 1, 2, 50)"""
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 3, true, AppDatabase.MIGRATION_2_3).apply {
            query("SELECT name FROM word_libraries WHERE id = 1").use {
                assertEquals(true, it.moveToFirst())
                assertEquals("默认词库", it.getString(0))
            }
            query("SELECT libraryId, meaningText FROM library_words WHERE wordId = 7").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(1L, it.getLong(0))
                assertEquals("苹果", it.getString(1))
            }
            query("SELECT lapseCount, successStreak FROM memory_states WHERE wordId = 7").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(1, it.getInt(0))
                assertEquals(2, it.getInt(1))
            }
            close()
        }
    }

    companion object { private const val TEST_DB = "migration-test" }
}
