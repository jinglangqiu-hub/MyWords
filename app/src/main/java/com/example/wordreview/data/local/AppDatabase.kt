package com.example.wordreview.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.wordreview.data.local.dao.ImportBatchDao
import com.example.wordreview.data.local.dao.MemoryStateDao
import com.example.wordreview.data.local.dao.ReviewMemorySnapshotDao
import com.example.wordreview.data.local.dao.ReviewProgressDao
import com.example.wordreview.data.local.dao.ReviewResultDao
import com.example.wordreview.data.local.dao.ReviewSessionDao
import com.example.wordreview.data.local.dao.ReviewSessionWordDao
import com.example.wordreview.data.local.dao.WordDao
import com.example.wordreview.data.local.dao.WordLibraryDao
import com.example.wordreview.data.local.entity.ImportBatchEntity
import com.example.wordreview.data.local.entity.MemoryStateEntity
import com.example.wordreview.data.local.entity.ReviewMemorySnapshotEntity
import com.example.wordreview.data.local.entity.ReviewProgressEntity
import com.example.wordreview.data.local.entity.ReviewResultEntity
import com.example.wordreview.data.local.entity.ReviewSessionEntity
import com.example.wordreview.data.local.entity.ReviewSessionWordEntity
import com.example.wordreview.data.local.entity.WordEntity
import com.example.wordreview.data.local.entity.LibraryWordEntity
import com.example.wordreview.data.local.entity.WordLibraryEntity

@Database(
    entities = [
        WordEntity::class,
        MemoryStateEntity::class,
        ImportBatchEntity::class,
        ReviewSessionEntity::class,
        ReviewResultEntity::class,
        ReviewSessionWordEntity::class,
        ReviewProgressEntity::class,
        ReviewMemorySnapshotEntity::class,
        WordLibraryEntity::class,
        LibraryWordEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wordDao(): WordDao
    abstract fun wordLibraryDao(): WordLibraryDao
    abstract fun memoryStateDao(): MemoryStateDao
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun reviewSessionDao(): ReviewSessionDao
    abstract fun reviewResultDao(): ReviewResultDao
    abstract fun reviewSessionWordDao(): ReviewSessionWordDao
    abstract fun reviewProgressDao(): ReviewProgressDao
    abstract fun reviewMemorySnapshotDao(): ReviewMemorySnapshotDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE review_sessions ADD COLUMN status TEXT NOT NULL DEFAULT 'completed'")
                db.execSQL("ALTER TABLE review_sessions ADD COLUMN currentStage TEXT NOT NULL DEFAULT 'choice'")
                db.execSQL("ALTER TABLE review_sessions ADD COLUMN choiceRounds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE review_sessions ADD COLUMN choiceMistakes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE review_sessions ADD COLUMN spellingRounds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE review_sessions ADD COLUMN spellingMistakes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE review_sessions ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS review_session_words (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        wordId INTEGER NOT NULL,
                        position INTEGER NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES review_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(wordId) REFERENCES words(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_review_session_words_sessionId ON review_session_words(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_review_session_words_wordId ON review_session_words(wordId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS review_progress (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        stage TEXT NOT NULL,
                        roundIndex INTEGER NOT NULL,
                        currentIndex INTEGER NOT NULL,
                        roundWordIds TEXT NOT NULL,
                        wrongWordIds TEXT NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES review_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_review_progress_sessionId_stage ON review_progress(sessionId, stage)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS review_memory_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        wordId INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        existed INTEGER NOT NULL,
                        easeFactor REAL,
                        intervalDays INTEGER,
                        dueAt INTEGER,
                        lapseCount INTEGER,
                        successStreak INTEGER,
                        lastReviewedAt INTEGER,
                        FOREIGN KEY(sessionId) REFERENCES review_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(wordId) REFERENCES words(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_review_memory_snapshots_sessionId ON review_memory_snapshots(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_review_memory_snapshots_wordId ON review_memory_snapshots(wordId)")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS word_libraries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        normalizedName TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_word_libraries_normalizedName ON word_libraries(normalizedName)")
                db.execSQL(
                    """
                    INSERT INTO word_libraries(id, name, normalizedName, createdAt, updatedAt)
                    VALUES(1, '默认词库', '默认词库',
                           CAST(strftime('%s','now') AS INTEGER) * 1000,
                           CAST(strftime('%s','now') AS INTEGER) * 1000)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS library_words (
                        libraryId INTEGER NOT NULL,
                        wordId INTEGER NOT NULL,
                        displayTerm TEXT NOT NULL,
                        sourceLang TEXT NOT NULL,
                        targetLang TEXT NOT NULL,
                        meaningText TEXT NOT NULL,
                        addedAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(libraryId, wordId),
                        FOREIGN KEY(libraryId) REFERENCES word_libraries(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(wordId) REFERENCES words(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_library_words_wordId ON library_words(wordId)")
                db.execSQL(
                    """
                    INSERT INTO library_words(libraryId, wordId, displayTerm, sourceLang, targetLang, meaningText, addedAt, updatedAt)
                    SELECT 1, id, displayTerm, sourceLang, targetLang, meaningText, createdAt, updatedAt FROM words
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE import_batches ADD COLUMN libraryId INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_import_batches_libraryId ON import_batches(libraryId)")
                db.execSQL("ALTER TABLE review_sessions ADD COLUMN libraryId INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_review_sessions_libraryId ON review_sessions(libraryId)")
            }
        }

        private val DATABASE_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                db.execSQL(
                    "INSERT INTO word_libraries(id, name, normalizedName, createdAt, updatedAt) VALUES(1, '默认词库', '默认词库', ?, ?)",
                    arrayOf(now, now)
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "word_review.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .addCallback(DATABASE_CALLBACK)
                    .build()
                    .also { instance = it }
            }
    }
}
