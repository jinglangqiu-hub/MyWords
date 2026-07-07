package com.example.wordreview.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.wordreview.data.local.AppDatabase
import com.example.wordreview.data.local.entity.ImportBatchEntity
import com.example.wordreview.data.local.entity.MemoryStateEntity
import com.example.wordreview.data.local.entity.WordEntity
import com.example.wordreview.data.repository.ImportRepository
import com.example.wordreview.data.repository.ReviewRepository
import com.example.wordreview.data.repository.WordRepository
import com.example.wordreview.domain.ChoiceOptionGenerator
import com.example.wordreview.domain.ChoiceQuestion
import com.example.wordreview.domain.ImportResult
import com.example.wordreview.domain.ReviewSummary
import com.example.wordreview.domain.ReviewWord
import com.example.wordreview.domain.STAGE_CHOICE
import com.example.wordreview.domain.STAGE_SPELLING
import com.example.wordreview.domain.SpellingQuestion
import com.example.wordreview.domain.WordNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val wordCount: Int = 0,
    val dueCount: Int = 0,
    val latestImport: ImportBatchEntity? = null,
    val showUnfinishedReviewDialog: Boolean = false,
    val reviewErrorMessage: String? = null
)

data class ImportState(
    val isImporting: Boolean = false,
    val result: ImportResult? = null,
    val errorMessage: String? = null
)

data class LibraryState(
    val query: String = "",
    val words: List<WordEntity> = emptyList()
)

data class DetailState(
    val word: WordEntity? = null,
    val recognition: MemoryStateEntity? = null,
    val spelling: MemoryStateEntity? = null,
    val editText: String = "",
    val message: String? = null
)

data class ReviewSetupState(
    val wordCount: Int = 0,
    val plannedCountInput: String = "30",
    val errorMessage: String? = null
)

data class ChoiceState(
    val roundIndex: Int = 1,
    val currentIndex: Int = 0,
    val totalInRound: Int = 0,
    val currentQuestion: ChoiceQuestion? = null,
    val selectedOption: String? = null,
    val isAnswered: Boolean = false,
    val isCorrect: Boolean? = null,
    val correctAnswer: String? = null
)

data class SpellingState(
    val roundIndex: Int = 1,
    val currentIndex: Int = 0,
    val totalInRound: Int = 0,
    val currentQuestion: SpellingQuestion? = null,
    val input: String = "",
    val isSubmitted: Boolean = false,
    val isCorrect: Boolean? = null,
    val correctTerm: String? = null
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.get(application)
    private val words = WordRepository(db)
    private val imports = ImportRepository(db, application.contentResolver)
    private val reviews = ReviewRepository(db)

    private val _home = MutableStateFlow(HomeState())
    val home: StateFlow<HomeState> = _home.asStateFlow()

    private val _importState = MutableStateFlow(ImportState())
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _detail = MutableStateFlow(DetailState())
    val detail: StateFlow<DetailState> = _detail.asStateFlow()

    private val _setup = MutableStateFlow(ReviewSetupState())
    val setup: StateFlow<ReviewSetupState> = _setup.asStateFlow()

    private val _choice = MutableStateFlow(ChoiceState())
    val choice: StateFlow<ChoiceState> = _choice.asStateFlow()

    private val _spelling = MutableStateFlow(SpellingState())
    val spelling: StateFlow<SpellingState> = _spelling.asStateFlow()

    private val _summary = MutableStateFlow(ReviewSummary(0, 0, 0, 0, 0))
    val summary: StateFlow<ReviewSummary> = _summary.asStateFlow()

    private var sessionId: Long = 0L
    private var reviewWords: List<ReviewWord> = emptyList()
    private var allWordsCache: List<WordEntity> = emptyList()
    private var choiceRoundWords: List<ReviewWord> = emptyList()
    private var choiceWrongWords: MutableList<ReviewWord> = mutableListOf()
    private var spellingRoundWords: List<ReviewWord> = emptyList()
    private var spellingWrongWords: MutableList<ReviewWord> = mutableListOf()

    init {
        viewModelScope.launch {
            words.observeAllWords().collectLatest { list ->
                allWordsCache = list
                _library.update { it.copy(words = filterWords(list, it.query)) }
                _home.update { it.copy(wordCount = list.size) }
                _setup.update { it.copy(wordCount = list.size) }
            }
        }
        refreshHome()
    }

    fun refreshHome() {
        viewModelScope.launch {
            _home.update {
                it.copy(
                    wordCount = words.countWords(),
                    latestImport = words.latestImport()
                )
            }
            _setup.update { it.copy(wordCount = words.countWords()) }
        }
    }

    fun openReview(onSetup: () -> Unit) {
        viewModelScope.launch {
            if (reviews.activeSession() == null) {
                _home.update { it.copy(reviewErrorMessage = null) }
                onSetup()
            } else {
                _home.update { it.copy(showUnfinishedReviewDialog = true, reviewErrorMessage = null) }
            }
        }
    }

    fun dismissUnfinishedReviewDialog() {
        _home.update { it.copy(showUnfinishedReviewDialog = false) }
    }

    fun continueUnfinishedReview(onChoiceReady: () -> Unit, onSpellingReady: () -> Unit) {
        viewModelScope.launch {
            val active = reviews.activeSession() ?: return@launch
            _home.update { it.copy(showUnfinishedReviewDialog = false) }
            if (!loadActiveSession(active)) {
                _home.update { it.copy(reviewErrorMessage = invalidChoiceSessionMessage()) }
                return@launch
            }
            if (active.currentStage == STAGE_SPELLING) onSpellingReady() else onChoiceReady()
        }
    }

    fun restartUnfinishedReview(onChoiceReady: () -> Unit) {
        viewModelScope.launch {
            val active = reviews.restartActiveSession() ?: return@launch
            _home.update { it.copy(showUnfinishedReviewDialog = false) }
            if (!loadActiveSession(active)) {
                _home.update { it.copy(reviewErrorMessage = invalidChoiceSessionMessage()) }
                return@launch
            }
            onChoiceReady()
        }
    }

    fun skipUnfinishedReview() {
        viewModelScope.launch {
            reviews.skipActiveSession()
            _home.update { it.copy(showUnfinishedReviewDialog = false) }
            refreshHome()
        }
    }

    fun importCsv(uri: Uri) {
        viewModelScope.launch {
            _importState.value = ImportState(isImporting = true)
            runCatching { imports.importGoogleTranslateCsv(uri) }
                .onSuccess { result ->
                    _importState.value = ImportState(result = result)
                    refreshHome()
                }
                .onFailure { error ->
                    _importState.value = ImportState(errorMessage = "导入失败：请选择有效的谷歌翻译收藏 CSV 文件。")
                    error.printStackTrace()
                }
        }
    }

    fun updateSearch(query: String) {
        _library.update { it.copy(query = query, words = filterWords(allWordsCache, query)) }
    }

    fun loadDetail(wordId: Long) {
        viewModelScope.launch {
            val word = words.getWord(wordId)
            val states = if (word != null) words.memoryStates(wordId) else null
            _detail.value = DetailState(
                word = word,
                recognition = states?.first,
                spelling = states?.second,
                editText = word?.meaningText.orEmpty()
            )
        }
    }

    fun updateDetailEdit(text: String) {
        _detail.update { it.copy(editText = text, message = null) }
    }

    fun saveMeaning() {
        val word = _detail.value.word ?: return
        viewModelScope.launch {
            words.updateMeaning(word.id, _detail.value.editText)
            loadDetail(word.id)
            _detail.update { it.copy(message = "释义已保存") }
        }
    }

    fun setSpellingDisabled(disabled: Boolean) {
        val word = _detail.value.word ?: return
        viewModelScope.launch {
            words.setSpellingDisabled(word.id, disabled)
            loadDetail(word.id)
        }
    }

    fun updatePlannedCount(input: String) {
        _setup.update { it.copy(plannedCountInput = input, errorMessage = null) }
    }

    fun startReview(onChoiceReady: () -> Unit) {
        viewModelScope.launch {
            val count = _setup.value.plannedCountInput.toIntOrNull()
            val allWords = words.allWords()
            val reviewableWords = allWords.filter { it.meaningText.trim().isNotBlank() }
            val reviewableTotal = reviewableWords.size
            when {
                reviewableTotal < 3 -> {
                    _setup.update { it.copy(errorMessage = "可复习词库数量不足，至少需要 3 个带释义的单词才能开始选择题复习。") }
                    return@launch
                }
                distinctMeaningCount(allWords) < 3 -> {
                    _setup.update { it.copy(errorMessage = "词库释义不足，至少需要 3 个不同释义才能开始选择题复习。") }
                    return@launch
                }
                count == null || count <= 0 -> {
                    _setup.update { it.copy(errorMessage = "复习数量必须大于 0。") }
                    return@launch
                }
                count > reviewableTotal -> {
                    _setup.update { it.copy(errorMessage = "复习数量不能大于当前带释义的单词数。") }
                    return@launch
                }
            }
            val reviewCount = count ?: return@launch
            val active = reviews.startSession(reviewCount)
            sessionId = active.sessionId
            reviewWords = active.words
            allWordsCache = allWords
            _summary.value = active.summary
            choiceRoundWords = active.words
            choiceWrongWords = mutableListOf()
            _choice.value = ChoiceState(roundIndex = 1, totalInRound = active.words.size)
            loadChoiceQuestion()
            onChoiceReady()
        }
    }

    fun answerChoice(option: String) {
        val state = _choice.value
        val question = state.currentQuestion ?: return
        if (state.isAnswered) return
        val correctAnswer = question.word.meaningText.trim()
        val correct = option == correctAnswer
        _choice.update {
            it.copy(
                selectedOption = option,
                isAnswered = true,
                isCorrect = correct,
                correctAnswer = correctAnswer
            )
        }
        if (!correct) {
            choiceWrongWords += question.word
            _summary.update { it.copy(choiceMistakes = it.choiceMistakes + 1) }
        }
        val summaryAfterAnswer = _summary.value
        val wrongIdsAfterAnswer = choiceWrongWords.map { it.id }
        viewModelScope.launch {
            reviews.saveAnswer(sessionId, question.word.id, STAGE_CHOICE, state.roundIndex, correct, option, correctAnswer)
            saveChoiceResumePoint(state, wrongIdsAfterAnswer, summaryAfterAnswer)
        }
    }

    fun nextChoice(onSpellingReady: () -> Unit) {
        val state = _choice.value
        if (!state.isAnswered) return
        if (state.currentIndex + 1 < choiceRoundWords.size) {
            _choice.update { it.copy(currentIndex = it.currentIndex + 1) }
            loadChoiceQuestion()
            return
        }
        if (choiceWrongWords.isNotEmpty()) {
            choiceRoundWords = choiceWrongWords.toList()
            choiceWrongWords = mutableListOf()
            _choice.value = ChoiceState(
                roundIndex = state.roundIndex + 1,
                currentIndex = 0,
                totalInRound = choiceRoundWords.size
            )
            loadChoiceQuestion()
            return
        }
        _summary.update { it.copy(choiceRounds = state.roundIndex) }
        viewModelScope.launch { reviews.saveSessionState(sessionId, STAGE_SPELLING, _summary.value) }
        startSpelling(onSpellingReady)
    }

    private fun loadChoiceQuestion(): Boolean {
        val state = _choice.value
        val word = choiceRoundWords.getOrNull(state.currentIndex)
        val question = word?.let { ChoiceOptionGenerator.makeQuestion(it, allWordsCache) }
        _choice.update {
            it.copy(
                currentQuestion = question,
                selectedOption = null,
                isAnswered = false,
                isCorrect = null,
                correctAnswer = null,
                totalInRound = choiceRoundWords.size
            )
        }
        return question != null
    }

    private fun startSpelling(onSpellingReady: () -> Unit) {
        spellingRoundWords = reviewWords.filterNot { it.spellingDisabled }
        spellingWrongWords = mutableListOf()
        if (spellingRoundWords.isEmpty()) {
            finishReview()
            onSpellingReady()
            return
        }
        _spelling.value = SpellingState(roundIndex = 1, totalInRound = spellingRoundWords.size)
        viewModelScope.launch {
            reviews.saveProgress(
                sessionId,
                STAGE_SPELLING,
                ReviewRepository.SavedProgress(1, 0, spellingRoundWords.map { it.id }, emptyList())
            )
        }
        loadSpellingQuestion()
        onSpellingReady()
    }

    fun updateSpellingInput(input: String) {
        _spelling.update { it.copy(input = input) }
    }

    fun submitSpelling() {
        val state = _spelling.value
        val question = state.currentQuestion ?: return
        if (state.isSubmitted) return
        val answer = WordNormalizer.normalizeTerm(state.input)
        val correct = answer == question.word.normalizedTerm
        _spelling.update {
            it.copy(isSubmitted = true, isCorrect = correct, correctTerm = question.word.displayTerm)
        }
        if (!correct) {
            spellingWrongWords += question.word
            _summary.update { it.copy(spellingMistakes = it.spellingMistakes + 1) }
        }
        val summaryAfterAnswer = _summary.value
        val wrongIdsAfterAnswer = spellingWrongWords.map { it.id }
        viewModelScope.launch {
            reviews.saveAnswer(sessionId, question.word.id, STAGE_SPELLING, state.roundIndex, correct, state.input, question.word.displayTerm)
            saveSpellingResumePoint(state, wrongIdsAfterAnswer, summaryAfterAnswer)
        }
    }

    fun disableCurrentSpelling() {
        val question = _spelling.value.currentQuestion ?: return
        viewModelScope.launch {
            words.setSpellingDisabled(question.word.id, true)
        }
        spellingWrongWords.removeAll { it.id == question.word.id }
    }

    fun nextSpelling(onDone: () -> Unit) {
        val state = _spelling.value
        if (!state.isSubmitted) return
        if (state.currentIndex + 1 < spellingRoundWords.size) {
            _spelling.update { it.copy(currentIndex = it.currentIndex + 1) }
            loadSpellingQuestion()
            return
        }
        if (spellingWrongWords.isNotEmpty()) {
            spellingRoundWords = spellingWrongWords.toList()
            spellingWrongWords = mutableListOf()
            _spelling.value = SpellingState(
                roundIndex = state.roundIndex + 1,
                currentIndex = 0,
                totalInRound = spellingRoundWords.size
            )
            loadSpellingQuestion()
            return
        }
        _summary.update { it.copy(spellingRounds = state.roundIndex) }
        finishReview()
        onDone()
    }

    private fun loadSpellingQuestion() {
        val state = _spelling.value
        val word = spellingRoundWords.getOrNull(state.currentIndex)
        _spelling.update {
            it.copy(
                currentQuestion = word?.let(::SpellingQuestion),
                input = "",
                isSubmitted = false,
                isCorrect = null,
                correctTerm = null,
                totalInRound = spellingRoundWords.size
            )
        }
    }

    private fun finishReview() {
        viewModelScope.launch { reviews.finishSession(sessionId, _summary.value) }
    }

    private suspend fun loadActiveSession(active: ReviewRepository.ActiveSession): Boolean {
        sessionId = active.sessionId
        reviewWords = active.words
        allWordsCache = words.allWords()
        _summary.value = active.summary
        val choiceProgress = active.choiceProgress ?: ReviewRepository.SavedProgress(1, 0, active.words.map { it.id }, emptyList())
        choiceRoundWords = choiceProgress.roundWordIds.toReviewWords(active.words)
        choiceWrongWords = choiceProgress.wrongWordIds.toReviewWords(active.words).toMutableList()
        _choice.value = ChoiceState(
            roundIndex = choiceProgress.roundIndex,
            currentIndex = choiceProgress.currentIndex.coerceAtMost((choiceRoundWords.size - 1).coerceAtLeast(0)),
            totalInRound = choiceRoundWords.size
        )
        val hasChoiceQuestion = loadChoiceQuestion()
        if (active.currentStage == STAGE_CHOICE && !hasChoiceQuestion) return false

        val spellingProgress = active.spellingProgress
        if (spellingProgress != null) {
            spellingRoundWords = spellingProgress.roundWordIds.toReviewWords(active.words)
            spellingWrongWords = spellingProgress.wrongWordIds.toReviewWords(active.words).toMutableList()
            _spelling.value = SpellingState(
                roundIndex = spellingProgress.roundIndex,
                currentIndex = spellingProgress.currentIndex.coerceAtMost((spellingRoundWords.size - 1).coerceAtLeast(0)),
                totalInRound = spellingRoundWords.size
            )
            loadSpellingQuestion()
        } else {
            spellingRoundWords = active.words.filterNot { it.spellingDisabled }
            spellingWrongWords = mutableListOf()
            _spelling.value = SpellingState(roundIndex = 1, totalInRound = spellingRoundWords.size)
            loadSpellingQuestion()
        }
        return true
    }

    private suspend fun saveChoiceResumePoint(
        answeredState: ChoiceState,
        wrongIdsAfterAnswer: List<Long>,
        summaryAfterAnswer: ReviewSummary
    ) {
        when {
            answeredState.currentIndex + 1 < choiceRoundWords.size -> {
                reviews.saveProgress(
                    sessionId,
                    STAGE_CHOICE,
                    ReviewRepository.SavedProgress(
                        answeredState.roundIndex,
                        answeredState.currentIndex + 1,
                        choiceRoundWords.map { it.id },
                        wrongIdsAfterAnswer
                    )
                )
            }
            wrongIdsAfterAnswer.isNotEmpty() -> {
                reviews.saveProgress(
                    sessionId,
                    STAGE_CHOICE,
                    ReviewRepository.SavedProgress(answeredState.roundIndex + 1, 0, wrongIdsAfterAnswer, emptyList())
                )
            }
            else -> {
                val nextSummary = summaryAfterAnswer.copy(choiceRounds = answeredState.roundIndex)
                _summary.value = nextSummary
                val spellingIds = reviewWords.filterNot { it.spellingDisabled }.map { it.id }
                if (spellingIds.isEmpty()) {
                    reviews.finishSession(sessionId, nextSummary)
                } else {
                    reviews.saveProgress(sessionId, STAGE_SPELLING, ReviewRepository.SavedProgress(1, 0, spellingIds, emptyList()))
                    reviews.saveSessionState(sessionId, STAGE_SPELLING, nextSummary)
                }
            }
        }
    }

    private suspend fun saveSpellingResumePoint(
        answeredState: SpellingState,
        wrongIdsAfterAnswer: List<Long>,
        summaryAfterAnswer: ReviewSummary
    ) {
        when {
            answeredState.currentIndex + 1 < spellingRoundWords.size -> {
                reviews.saveProgress(
                    sessionId,
                    STAGE_SPELLING,
                    ReviewRepository.SavedProgress(
                        answeredState.roundIndex,
                        answeredState.currentIndex + 1,
                        spellingRoundWords.map { it.id },
                        wrongIdsAfterAnswer
                    )
                )
            }
            wrongIdsAfterAnswer.isNotEmpty() -> {
                reviews.saveProgress(
                    sessionId,
                    STAGE_SPELLING,
                    ReviewRepository.SavedProgress(answeredState.roundIndex + 1, 0, wrongIdsAfterAnswer, emptyList())
                )
            }
            else -> {
                val nextSummary = summaryAfterAnswer.copy(spellingRounds = answeredState.roundIndex)
                _summary.value = nextSummary
                reviews.finishSession(sessionId, nextSummary)
            }
        }
    }

    private fun List<Long>.toReviewWords(source: List<ReviewWord>): List<ReviewWord> {
        val byId = source.associateBy { it.id }
        return mapNotNull { byId[it] }
    }

    private fun filterWords(words: List<WordEntity>, query: String): List<WordEntity> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return words
        return words.filter { it.normalizedTerm.contains(q) || it.meaningText.contains(query.trim()) }
    }

    private fun distinctMeaningCount(words: List<WordEntity>): Int =
        words.map { it.meaningText.trim() }.filter { it.isNotBlank() }.distinct().size

    private fun invalidChoiceSessionMessage(): String =
        "这次未完成复习无法生成选择题。请先跳过它，确认词库至少有 3 个不同释义后重新开始。"
}
