package com.example.wordreview.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.wordreview.data.local.entity.ImportBatchEntity
import com.example.wordreview.data.local.entity.MemoryStateEntity
import com.example.wordreview.data.local.entity.ReviewMemorySnapshotEntity
import com.example.wordreview.data.local.entity.ReviewProgressEntity
import com.example.wordreview.data.local.entity.ReviewResultEntity
import com.example.wordreview.data.local.entity.ReviewSessionEntity
import com.example.wordreview.data.local.entity.ReviewSessionWordEntity
import com.example.wordreview.data.local.entity.WordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WordDao {
    @Query("SELECT * FROM words ORDER BY updatedAt DESC")
    fun observeAllWords(): Flow<List<WordEntity>>

    @Query(
        """
        SELECT * FROM words
        WHERE normalizedTerm LIKE '%' || :query || '%'
           OR meaningText LIKE '%' || :query || '%'
        ORDER BY updatedAt DESC
        """
    )
    fun searchWords(query: String): Flow<List<WordEntity>>

    @Query("SELECT * FROM words ORDER BY updatedAt DESC")
    suspend fun getAllWords(): List<WordEntity>

    @Query("SELECT * FROM words WHERE normalizedTerm = :normalizedTerm LIMIT 1")
    suspend fun getByNormalizedTerm(normalizedTerm: String): WordEntity?

    @Query("SELECT * FROM words WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): WordEntity?

    @Insert
    suspend fun insert(word: WordEntity): Long

    @Update
    suspend fun update(word: WordEntity)

    @Query("SELECT COUNT(*) FROM words")
    suspend fun countWords(): Int
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
}

@Dao
interface ImportBatchDao {
    @Insert
    suspend fun insert(batch: ImportBatchEntity): Long

    @Query("SELECT * FROM import_batches ORDER BY importedAt DESC LIMIT 1")
    suspend fun getLatestImport(): ImportBatchEntity?
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
}

@Dao
interface ReviewMemorySnapshotDao {
    @Insert
    suspend fun insertAll(snapshots: List<ReviewMemorySnapshotEntity>)

    @Query("SELECT * FROM review_memory_snapshots WHERE sessionId = :sessionId")
    suspend fun getBySession(sessionId: Long): List<ReviewMemorySnapshotEntity>

    @Query("DELETE FROM review_memory_snapshots WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)
}
