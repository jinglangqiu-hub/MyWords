package com.example.wordreview.data.local.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "words",
    indices = [Index(value = ["normalizedTerm"], unique = true)]
)
data class WordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val normalizedTerm: String,
    val displayTerm: String,
    val sourceLang: String,
    val targetLang: String,
    val meaningText: String,
    val spellingDisabled: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "word_libraries",
    indices = [Index(value = ["normalizedName"], unique = true)]
)
data class WordLibraryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "library_words",
    primaryKeys = ["libraryId", "wordId"],
    foreignKeys = [
        ForeignKey(
            entity = WordLibraryEntity::class,
            parentColumns = ["id"],
            childColumns = ["libraryId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["wordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["wordId"])]
)
data class LibraryWordEntity(
    val libraryId: Long,
    val wordId: Long,
    val displayTerm: String,
    val sourceLang: String,
    val targetLang: String,
    val meaningText: String,
    val addedAt: Long,
    val updatedAt: Long
)

data class WordLibrarySummary(
    val id: Long,
    val name: String,
    val wordCount: Int,
    val updatedAt: Long,
    val reviewableCount: Int = 0,
    val startedCount: Int = 0,
    val learnedCount: Int = 0
)

data class LibraryWordEntry(
    val wordId: Long,
    val normalizedTerm: String,
    val displayTerm: String,
    val sourceLang: String,
    val targetLang: String,
    val meaningText: String,
    val spellingDisabled: Boolean,
    val addedAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "memory_states",
    foreignKeys = [
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["wordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["wordId", "type"], unique = true)]
)
data class MemoryStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wordId: Long,
    val type: String,
    val easeFactor: Double = 2.5,
    val intervalDays: Int = 0,
    val dueAt: Long = 0L,
    val lapseCount: Int = 0,
    val successStreak: Int = 0,
    val lastReviewedAt: Long? = null
)

@Entity(tableName = "import_batches", indices = [Index(value = ["libraryId"])])
data class ImportBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val importedAt: Long,
    val totalRows: Int,
    val newCount: Int,
    val existingCount: Int,
    val updatedMeaningCount: Int,
    val failedRowCount: Int,
    @ColumnInfo(defaultValue = "1") val libraryId: Long = 1L
)

@Entity(tableName = "review_sessions", indices = [Index(value = ["libraryId"])])
data class ReviewSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val plannedCount: Int,
    val actualCount: Int,
    val status: String = "active",
    val currentStage: String = "choice",
    val choiceRounds: Int = 0,
    val choiceMistakes: Int = 0,
    val spellingRounds: Int = 0,
    val spellingMistakes: Int = 0,
    val updatedAt: Long = 0L,
    @ColumnInfo(defaultValue = "1") val libraryId: Long = 1L
)

@Entity(
    tableName = "review_session_words",
    foreignKeys = [
        ForeignKey(
            entity = ReviewSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["wordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"]), Index(value = ["wordId"])]
)
data class ReviewSessionWordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val wordId: Long,
    val position: Int
)

@Entity(
    tableName = "review_progress",
    foreignKeys = [
        ForeignKey(
            entity = ReviewSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId", "stage"], unique = true)]
)
data class ReviewProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val stage: String,
    val roundIndex: Int,
    val currentIndex: Int,
    val roundWordIds: String,
    val wrongWordIds: String
)

@Entity(
    tableName = "review_memory_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = ReviewSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["wordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"]), Index(value = ["wordId"])]
)
data class ReviewMemorySnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val wordId: Long,
    val type: String,
    val existed: Boolean,
    val easeFactor: Double?,
    val intervalDays: Int?,
    val dueAt: Long?,
    val lapseCount: Int?,
    val successStreak: Int?,
    val lastReviewedAt: Long?
)

@Entity(
    tableName = "review_results",
    foreignKeys = [
        ForeignKey(
            entity = ReviewSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["wordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"]), Index(value = ["wordId"])]
)
data class ReviewResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val wordId: Long,
    val stage: String,
    val roundIndex: Int,
    val isCorrect: Boolean,
    val answer: String?,
    val correctAnswer: String,
    val createdAt: Long
)
