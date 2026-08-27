package com.example.wordreview.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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
import kotlinx.coroutines.flow.Flow

@Dao
interface WordDao {
    @Query("SELECT * FROM words WHERE normalizedTerm = :normalizedTerm LIMIT 1")
    suspend fun getByNormalizedTerm(normalizedTerm: String): WordEntity?

    @Query("SELECT * FROM words WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): WordEntity?

    @Insert
    suspend fun insert(word: WordEntity): Long

    @Update
    suspend fun update(word: WordEntity)

    @Query("DELETE FROM words WHERE NOT EXISTS (SELECT 1 FROM library_words WHERE library_words.wordId = words.id)")
    suspend fun deleteOrphanWords(): Int
}

@Dao
interface WordLibraryDao {
    @Query(
        """
        SELECT l.id, l.name, COUNT(lw.wordId) AS wordCount, l.updatedAt
        FROM word_libraries l
        LEFT JOIN library_words lw ON lw.libraryId = l.id
        GROUP BY l.id
        ORDER BY l.updatedAt DESC, l.name COLLATE NOCASE ASC
        """
    )
    fun observeSummaries(): Flow<List<WordLibrarySummary>>

    @Query("SELECT * FROM word_libraries ORDER BY updatedAt DESC, name COLLATE NOCASE ASC")
    suspend fun getAll(): List<WordLibraryEntity>

    @Query("SELECT * FROM word_libraries WHERE id = :libraryId LIMIT 1")
    suspend fun getById(libraryId: Long): WordLibraryEntity?

    @Query("SELECT * FROM word_libraries WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun getByNormalizedName(normalizedName: String): WordLibraryEntity?

    @Insert
    suspend fun insert(library: WordLibraryEntity): Long

    @Update
    suspend fun update(library: WordLibraryEntity)

    @Query("DELETE FROM word_libraries WHERE id = :libraryId")
    suspend fun delete(libraryId: Long)

    @Query("SELECT COUNT(*) FROM word_libraries")
    suspend fun countLibraries(): Int

    @Query("SELECT COUNT(*) FROM library_words WHERE libraryId = :libraryId")
    suspend fun countWords(libraryId: Long): Int

    @Query(
        """
        SELECT lw.wordId AS wordId, w.normalizedTerm AS normalizedTerm,
               lw.displayTerm AS displayTerm, lw.sourceLang AS sourceLang,
               lw.targetLang AS targetLang, lw.meaningText AS meaningText,
               w.spellingDisabled AS spellingDisabled, lw.addedAt AS addedAt,
               lw.updatedAt AS updatedAt
        FROM library_words lw
        JOIN words w ON w.id = lw.wordId
        WHERE lw.libraryId = :libraryId
        ORDER BY lw.updatedAt DESC
        """
    )
    fun observeWords(libraryId: Long): Flow<List<LibraryWordEntry>>

    @Query(
        """
        SELECT lw.wordId AS wordId, w.normalizedTerm AS normalizedTerm,
               lw.displayTerm AS displayTerm, lw.sourceLang AS sourceLang,
               lw.targetLang AS targetLang, lw.meaningText AS meaningText,
               w.spellingDisabled AS spellingDisabled, lw.addedAt AS addedAt,
               lw.updatedAt AS updatedAt
        FROM library_words lw
        JOIN words w ON w.id = lw.wordId
        WHERE lw.libraryId = :libraryId
          AND (w.normalizedTerm LIKE '%' || :query || '%' OR lw.meaningText LIKE '%' || :query || '%')
        ORDER BY lw.updatedAt DESC
        """
    )
    fun searchWords(libraryId: Long, query: String): Flow<List<LibraryWordEntry>>

    @Query(
        """
        SELECT lw.wordId AS wordId, w.normalizedTerm AS normalizedTerm,
               lw.displayTerm AS displayTerm, lw.sourceLang AS sourceLang,
               lw.targetLang AS targetLang, lw.meaningText AS meaningText,
               w.spellingDisabled AS spellingDisabled, lw.addedAt AS addedAt,
               lw.updatedAt AS updatedAt
        FROM library_words lw
        JOIN words w ON w.id = lw.wordId
        WHERE lw.libraryId = :libraryId
        ORDER BY lw.updatedAt DESC
        """
    )
    suspend fun getWords(libraryId: Long): List<LibraryWordEntry>

    @Query(
        """
        SELECT lw.wordId AS wordId, w.normalizedTerm AS normalizedTerm,
               lw.displayTerm AS displayTerm, lw.sourceLang AS sourceLang,
               lw.targetLang AS targetLang, lw.meaningText AS meaningText,
               w.spellingDisabled AS spellingDisabled, lw.addedAt AS addedAt,
               lw.updatedAt AS updatedAt
        FROM library_words lw
        JOIN words w ON w.id = lw.wordId
        WHERE lw.libraryId = :libraryId AND lw.wordId = :wordId
        LIMIT 1
        """
    )
    suspend fun getWord(libraryId: Long, wordId: Long): LibraryWordEntry?

    @Query("SELECT * FROM library_words WHERE libraryId = :libraryId AND wordId = :wordId LIMIT 1")
    suspend fun getMembership(libraryId: Long, wordId: Long): LibraryWordEntity?

    @Insert
    suspend fun insertMembership(membership: LibraryWordEntity)

    @Update
    suspend fun updateMembership(membership: LibraryWordEntity)

    @Query("UPDATE word_libraries SET updatedAt = :updatedAt WHERE id = :libraryId")
    suspend fun touch(libraryId: Long, updatedAt: Long)
}

@Dao
interface MemoryStateDao {
    @Query("SELECT * FROM memory_states WHERE wordId = :wordId AND type = :type LIMIT 1")
    suspend fun getState(wordId: Long, type: String): MemoryStateEntity?

    @Insert
    suspend fun insert(state: MemoryStateEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(state: MemoryStateEntity): Long

    @Update
    suspend fun update(state: MemoryStateEntity)

    @Query("DELETE FROM memory_states WHERE wordId = :wordId AND type = :type")
    suspend fun deleteState(wordId: Long, type: String)

    @Query("SELECT * FROM memory_states WHERE type = :type")
    suspend fun getStatesByType(type: String): List<MemoryStateEntity>

    @Query("SELECT * FROM memory_states WHERE dueAt <= :now AND type = :type")
    suspend fun getDueStates(now: Long, type: String): List<MemoryStateEntity>

    @Query("DELETE FROM memory_states")
    suspend fun deleteAll(): Int
}

@Dao
interface ImportBatchDao {
    @Insert
    suspend fun insert(batch: ImportBatchEntity): Long

    @Query("SELECT * FROM import_batches ORDER BY importedAt DESC LIMIT 1")
    suspend fun getLatestImport(): ImportBatchEntity?

    @Query("SELECT * FROM import_batches WHERE libraryId = :libraryId ORDER BY importedAt DESC LIMIT 1")
    suspend fun getLatestImport(libraryId: Long): ImportBatchEntity?

    @Query("DELETE FROM import_batches WHERE libraryId = :libraryId")
    suspend fun deleteByLibrary(libraryId: Long)
}

@Dao
interface ReviewSessionDao {
    @Insert
    suspend fun insert(session: ReviewSessionEntity): Long

    @Update
    suspend fun update(session: ReviewSessionEntity)

    @Query("SELECT * FROM review_sessions ORDER BY startedAt DESC LIMIT 1")
    suspend fun getLatestSession(): ReviewSessionEntity?

    @Query("SELECT * FROM review_sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ReviewSessionEntity?

    @Query("SELECT * FROM review_sessions WHERE status = 'active' ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveSession(): ReviewSessionEntity?

    @Query("SELECT * FROM review_sessions WHERE libraryId = :libraryId AND status = 'active' ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveSession(libraryId: Long): ReviewSessionEntity?

    @Query("DELETE FROM review_sessions WHERE libraryId = :libraryId")
    suspend fun deleteByLibrary(libraryId: Long)

    @Query("UPDATE review_sessions SET status = 'abandoned', endedAt = :now, updatedAt = :now WHERE status = 'active'")
    suspend fun abandonAllActive(now: Long): Int
}

@Dao
interface ReviewResultDao {
    @Insert
    suspend fun insert(result: ReviewResultEntity): Long

    @Query("SELECT * FROM review_results WHERE sessionId = :sessionId")
    suspend fun getResultsBySession(sessionId: Long): List<ReviewResultEntity>

    @Query("SELECT * FROM review_results WHERE sessionId = :sessionId AND stage = :stage AND roundIndex = :roundIndex AND wordId = :wordId LIMIT 1")
    suspend fun getAnswer(sessionId: Long, stage: String, roundIndex: Int, wordId: Long): ReviewResultEntity?

    @Query("DELETE FROM review_results WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)
}

@Dao
interface ReviewSessionWordDao {
    @Insert
    suspend fun insertAll(words: List<ReviewSessionWordEntity>)

    @Query("SELECT * FROM review_session_words WHERE sessionId = :sessionId ORDER BY position ASC")
    suspend fun getBySession(sessionId: Long): List<ReviewSessionWordEntity>
}

@Dao
interface ReviewProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(progress: ReviewProgressEntity): Long

    @Update
    suspend fun update(progress: ReviewProgressEntity)

    @Query("SELECT * FROM review_progress WHERE sessionId = :sessionId AND stage = :stage LIMIT 1")
    suspend fun get(sessionId: Long, stage: String): ReviewProgressEntity?

    @Query("SELECT * FROM review_progress WHERE sessionId = :sessionId")
    suspend fun getBySession(sessionId: Long): List<ReviewProgressEntity>

    @Query("DELETE FROM review_progress WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)

    @Query("DELETE FROM review_progress")
    suspend fun deleteAll()
}

@Dao
interface ReviewMemorySnapshotDao {
    @Insert
    suspend fun insertAll(snapshots: List<ReviewMemorySnapshotEntity>)

    @Query("SELECT * FROM review_memory_snapshots WHERE sessionId = :sessionId")
    suspend fun getBySession(sessionId: Long): List<ReviewMemorySnapshotEntity>

    @Query("DELETE FROM review_memory_snapshots WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)

    @Query("DELETE FROM review_memory_snapshots")
    suspend fun deleteAll()
}
