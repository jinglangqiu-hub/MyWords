package com.example.wordreview.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.wordreview.data.local.AppDatabase
import com.example.wordreview.data.local.entity.ImportBatchEntity
import com.example.wordreview.data.local.entity.LibraryWordEntry
import com.example.wordreview.data.local.entity.MemoryStateEntity
import com.example.wordreview.data.local.entity.WordLibrarySummary
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HomeState(
    val wordCount: Int = 0,
    val libraryCount: Int = 0,
    val latestImport: ImportBatchEntity? = null,
    val showUnfinishedReviewDialog: Boolean = false,
    val reviewErrorMessage: String? = null,
    val memoryResetMessage: String? = null,
    val isResettingMemory: Boolean = false
)

data class ImportState(
    val libraryId: Long = 0,
    val libraryName: String = "",
    val isImporting: Boolean = false,
    val result: ImportResult? = null,
    val errorMessage: String? = null
)

data class LibraryListState(
    val libraries: List<WordLibrarySummary> = emptyList(),
    val nameInput: String = "",
    val editingId: Long? = null,
    val message: String? = null
)

data class LibraryState(
    val libraryId: Long = 0,
    val libraryName: String = "",
    val query: String = "",
    val words: List<LibraryWordEntry> = emptyList()
)

data class DetailState(
    val libraryId: Long = 0,
    val word: LibraryWordEntry? = null,
    val recognition: MemoryStateEntity? = null,
    val spelling: MemoryStateEntity? = null,
    val editText: String = "",
    val message: String? = null
)

data class ReviewSetupState(
    val libraries: List<WordLibrarySummary> = emptyList(),
    val selectedLibraryId: Long = 0,
    val wordCount: Int = 0,
    val plannedCountInput: String = "30",
    val errorMessage: String? = null,
    val isStarting: Boolean = false
)

data class ChoiceState(
    val roundIndex: Int = 1,
    val currentIndex: Int = 0,
    val totalInRound: Int = 0,
    val currentQuestion: ChoiceQuestion? = null,
    val selectedOption: String? = null,
    val isAnswered: Boolean = false,
    val isCorrect: Boolean? = null,
    val correctAnswer: String? = null,
    val isPersisting: Boolean = false,
    val persistenceError: String? = null
)

data class SpellingState(
    val roundIndex: Int = 1,
    val currentIndex: Int = 0,
    val totalInRound: Int = 0,
    val currentQuestion: SpellingQuestion? = null,
    val input: String = "",
    val isSubmitted: Boolean = false,
    val isCorrect: Boolean? = null,
    val correctTerm: String? = null,
    val isPersisting: Boolean = false,
    val persistenceError: String? = null,
    val statusMessage: String? = null
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

    private val _libraryList = MutableStateFlow(LibraryListState())
    val libraryList: StateFlow<LibraryListState> = _libraryList.asStateFlow()

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
    private var allWordsCache: List<LibraryWordEntry> = emptyList()
    private var choiceDistractorCache: Map<Long, List<String>> = emptyMap()
    private var libraryWordsJob: Job? = null
    private val preferences = application.getSharedPreferences("word_review_preferences", 0)
    private var choiceRoundWords: List<ReviewWord> = emptyList()
    private var choiceWrongWords: MutableList<ReviewWord> = mutableListOf()
    private var spellingRoundWords: List<ReviewWord> = emptyList()
    private var spellingWrongWords: MutableList<ReviewWord> = mutableListOf()

    init {
        viewModelScope.launch {
            words.observeLibraries().collectLatest { libraries ->
                _libraryList.update { it.copy(libraries = libraries) }
                _home.update { it.copy(libraryCount = libraries.size, wordCount = libraries.sumOf { item -> item.wordCount }) }
                val selected = selectValidLibrary(libraries, _setup.value.selectedLibraryId)
                _setup.update {
                    it.copy(
                        libraries = libraries,
                        selectedLibraryId = selected,
                        wordCount = libraries.firstOrNull { item -> item.id == selected }?.wordCount ?: 0
                    )
                }
            }
        }
        refreshHome()
    }

    fun refreshHome() {
        viewModelScope.launch {
            _home.update { it.copy(latestImport = words.latestImport()) }
        }
    }

    fun openReview(onSetup: () -> Unit) {
        viewModelScope.launch {
            if (reviews.activeSession() == null) {
                _home.update { it.copy(reviewErrorMessage = null) }
                prepareReviewSetup()
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

    fun openImport(libraryId: Long) {
        viewModelScope.launch {
            val library = words.getLibrary(libraryId) ?: return@launch
            _importState.value = ImportState(libraryId = library.id, libraryName = library.name)
        }
    }

    fun resetReviewMemory() {
        if (_home.value.isResettingMemory) return
        _home.update { it.copy(isResettingMemory = true, memoryResetMessage = null) }
        viewModelScope.launch {
            runCatching { reviews.resetMemory() }
                .onSuccess {
                    clearReviewSessionState()
                    _home.update {
                        it.copy(
                            showUnfinishedReviewDialog = false,
                            reviewErrorMessage = null,
                            memoryResetMessage = "复习记忆已重置，所有单词将按首次学习处理。",
                            isResettingMemory = false
                        )
                    }
                }
                .onFailure { error ->
                    _home.update {
                        it.copy(
                            memoryResetMessage = "重置失败：${error.message ?: "未知错误"}",
                            isResettingMemory = false
                        )
                    }
                }
        }
    }

    fun importCsv(uri: Uri) {
        val current = _importState.value
        if (current.libraryId == 0L || current.isImporting) return
        _importState.value = current.copy(isImporting = true, result = null, errorMessage = null)
        viewModelScope.launch {
            runCatching { imports.importCsv(current.libraryId, uri) }
                .onSuccess { result ->
                    _importState.value = current.copy(result = result)
                    refreshHome()
                }
                .onFailure { error ->
                    _importState.value = current.copy(errorMessage = "导入失败：${error.message ?: "请选择有效的 CSV 文件。"}")
                    error.printStackTrace()
                }
        }
    }

    fun updateLibraryNameInput(value: String) {
        _libraryList.update { it.copy(nameInput = value, message = null) }
    }

    fun beginRenameLibrary(library: WordLibrarySummary) {
        _libraryList.update { it.copy(editingId = library.id, nameInput = library.name, message = null) }
    }

    fun cancelLibraryEdit() {
        _libraryList.update { it.copy(editingId = null, nameInput = "", message = null) }
    }

    fun saveLibrary() {
        val state = _libraryList.value
        viewModelScope.launch {
            runCatching {
                if (state.editingId == null) words.createLibrary(state.nameInput)
                else words.renameLibrary(state.editingId, state.nameInput)
            }.onSuccess {
                _libraryList.update { it.copy(editingId = null, nameInput = "", message = "已保存") }
            }.onFailure { error ->
                _libraryList.update { it.copy(message = error.message ?: "保存失败") }
            }
        }
    }

    fun deleteLibrary(libraryId: Long) {
        viewModelScope.launch {
            runCatching { words.deleteLibrary(libraryId) }
                .onSuccess { _libraryList.update { it.copy(message = "词库已删除") } }
                .onFailure { error -> _libraryList.update { it.copy(message = error.message ?: "删除失败") } }
        }
    }

    fun loadLibrary(libraryId: Long) {
        libraryWordsJob?.cancel()
        libraryWordsJob = viewModelScope.launch {
            val name = words.getLibrary(libraryId)?.name ?: "词库"
            words.observeWords(libraryId).collectLatest { list ->
                allWordsCache = list
                _library.update {
                    it.copy(
                        libraryId = libraryId,
                        libraryName = name,
                        words = filterWords(list, it.query)
                    )
                }
            }
        }
    }

    fun updateSearch(query: String) {
        _library.update { it.copy(query = query, words = filterWords(allWordsCache, query)) }
    }

    fun loadDetail(libraryId: Long, wordId: Long) {
        viewModelScope.launch {
            refreshDetail(libraryId, wordId)
        }
    }

    fun updateDetailEdit(text: String) {
        _detail.update { it.copy(editText = text, message = null) }
    }

    fun saveMeaning() {
        val state = _detail.value
        val word = state.word ?: return
        viewModelScope.launch {
            runCatching { words.updateMeaning(state.libraryId, word.wordId, state.editText) }
                .onSuccess { refreshDetail(state.libraryId, word.wordId, "释义已保存") }
                .onFailure { error -> _detail.update { it.copy(message = "保存失败：${error.message ?: "未知错误"}") } }
        }
    }

    fun setSpellingDisabled(disabled: Boolean) {
        val state = _detail.value
        val word = state.word ?: return
        viewModelScope.launch {
            runCatching { words.setSpellingDisabled(word.wordId, disabled) }
                .onSuccess { refreshDetail(state.libraryId, word.wordId) }
                .onFailure { error -> _detail.update { it.copy(message = "保存失败：${error.message ?: "未知错误"}") } }
        }
    }

    fun prepareReviewSetup() {
        viewModelScope.launch {
            val libraries = _libraryList.value.libraries
            val selected = selectValidLibrary(libraries, _setup.value.selectedLibraryId)
            _setup.update {
                it.copy(
                    libraries = libraries,
                    selectedLibraryId = selected,
                    wordCount = libraries.firstOrNull { item -> item.id == selected }?.wordCount ?: 0,
                    errorMessage = null
                )
            }
        }
    }

    fun selectReviewLibrary(libraryId: Long) {
        val library = _setup.value.libraries.firstOrNull { it.id == libraryId } ?: return
        preferences.edit().putLong("last_library_id", libraryId).apply()
        _setup.update { it.copy(selectedLibraryId = libraryId, wordCount = library.wordCount, errorMessage = null) }
    }

    fun updatePlannedCount(input: String) {
        _setup.update { it.copy(plannedCountInput = input, errorMessage = null) }
    }

    fun startReview(onChoiceReady: () -> Unit) {
        if (_setup.value.isStarting) return
        _setup.update { it.copy(isStarting = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val count = _setup.value.plannedCountInput.toIntOrNull()
                val libraryId = _setup.value.selectedLibraryId
                if (libraryId == 0L) {
                    _setup.update { it.copy(errorMessage = "请先选择一个词库。") }
                    return@launch
                }
                val allWords = words.allWords(libraryId)
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
                val active = reviews.startSession(libraryId, reviewCount)
                sessionId = active.sessionId
                reviewWords = active.words
                allWordsCache = allWords
                choiceDistractorCache = withContext(Dispatchers.Default) {
                    ChoiceOptionGenerator.prepareDistractors(active.words, allWords)
                }
                _summary.value = active.summary
                choiceRoundWords = active.words
                choiceWrongWords = mutableListOf()
                _choice.value = ChoiceState(roundIndex = 1, totalInRound = active.words.size)
                loadChoiceQuestion()
                onChoiceReady()
            } catch (error: Exception) {
                _setup.update { it.copy(errorMessage = "开始复习失败：${error.message ?: "未知错误"}") }
            } finally {
                _setup.update { it.copy(isStarting = false) }
            }
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
                correctAnswer = correctAnswer,
                isPersisting = true,
                persistenceError = null
            )
        }
        if (!correct) {
            choiceWrongWords += question.word
            _summary.update { it.copy(choiceMistakes = it.choiceMistakes + 1) }
        }
        val summaryAfterAnswer = _summary.value
        val wrongIdsAfterAnswer = choiceWrongWords.map { it.id }
        viewModelScope.launch {
            runCatching {
                reviews.saveAnswer(sessionId, question.word.id, STAGE_CHOICE, state.roundIndex, correct, option, correctAnswer)
                saveChoiceResumePoint(state, wrongIdsAfterAnswer, summaryAfterAnswer)
            }.onSuccess {
                _choice.update { it.copy(isPersisting = false) }
            }.onFailure { error ->
                _choice.update {
                    it.copy(isPersisting = false, persistenceError = "进度保存失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun nextChoice(onSpellingReady: () -> Unit) {
        val state = _choice.value
        if (!state.isAnswered || state.isPersisting) return
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
        startSpelling(onSpellingReady)
    }

    private fun loadChoiceQuestion(): Boolean {
        val state = _choice.value
        val word = choiceRoundWords.getOrNull(state.currentIndex)
        val question = word?.let {
            ChoiceOptionGenerator.makePreparedQuestion(it, choiceDistractorCache[it.id].orEmpty())
        }
        _choice.update {
            it.copy(
                currentQuestion = question,
                selectedOption = null,
                isAnswered = false,
                isCorrect = null,
                correctAnswer = null,
                isPersisting = false,
                persistenceError = null,
                totalInRound = choiceRoundWords.size
            )
        }
        return question != null
    }

    private fun startSpelling(onSpellingReady: () -> Unit) {
        spellingRoundWords = reviewWords.filterNot { it.spellingDisabled }
        spellingWrongWords = mutableListOf()
        if (spellingRoundWords.isEmpty()) {
            _spelling.value = SpellingState(isPersisting = true, statusMessage = "正在保存复习结果…")
            viewModelScope.launch {
                runCatching { reviews.finishSession(sessionId, _summary.value) }
                    .onSuccess {
                        _spelling.update { it.copy(isPersisting = false, statusMessage = "复习结果已保存。") }
                    }
                    .onFailure { error ->
                        _spelling.update {
                            it.copy(
                                isPersisting = false,
                                persistenceError = "结果保存失败：${error.message ?: "未知错误"}",
                                statusMessage = null
                            )
                        }
                    }
            }
            onSpellingReady()
            return
        }
        _spelling.value = SpellingState(roundIndex = 1, totalInRound = spellingRoundWords.size)
        loadSpellingQuestion()
        onSpellingReady()
    }

    fun updateSpellingInput(input: String) {
        _spelling.update { it.copy(input = input) }
    }

    fun submitSpelling() {
        val state = _spelling.value
        val question = state.currentQuestion ?: return
        if (state.isSubmitted || state.isPersisting) return
        val answer = WordNormalizer.normalizeTerm(state.input)
        val correct = answer == question.word.normalizedTerm
        _spelling.update {
            it.copy(
                isSubmitted = true,
                isCorrect = correct,
                correctTerm = question.word.displayTerm,
                isPersisting = true,
                persistenceError = null,
                statusMessage = null
            )
        }
        if (!correct) {
            spellingWrongWords += question.word
            _summary.update { it.copy(spellingMistakes = it.spellingMistakes + 1) }
        }
        val summaryAfterAnswer = _summary.value
        val wrongIdsAfterAnswer = spellingWrongWords.map { it.id }
        viewModelScope.launch {
            runCatching {
                reviews.saveAnswer(sessionId, question.word.id, STAGE_SPELLING, state.roundIndex, correct, state.input, question.word.displayTerm)
                saveSpellingResumePoint(state, wrongIdsAfterAnswer, summaryAfterAnswer)
            }.onSuccess {
                _spelling.update { it.copy(isPersisting = false) }
            }.onFailure { error ->
                _spelling.update {
                    it.copy(isPersisting = false, persistenceError = "进度保存失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun disableCurrentSpelling() {
        val state = _spelling.value
        val question = state.currentQuestion ?: return
        if (state.isSubmitted || state.isPersisting) return
        spellingWrongWords.removeAll { it.id == question.word.id }
        val wrongIdsAfterAnswer = spellingWrongWords.map { it.id }
        val summaryAfterAnswer = _summary.value
        _spelling.update {
            it.copy(
                isSubmitted = true,
                isPersisting = true,
                persistenceError = null,
                statusMessage = "已设为不再拼写，点击下一题继续。"
            )
        }
        viewModelScope.launch {
            runCatching {
                words.setSpellingDisabled(question.word.id, true)
                saveSpellingResumePoint(state, wrongIdsAfterAnswer, summaryAfterAnswer)
            }.onSuccess {
                _spelling.update { it.copy(isPersisting = false) }
            }.onFailure { error ->
                _spelling.update {
                    it.copy(isPersisting = false, persistenceError = "设置保存失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun nextSpelling(onDone: () -> Unit) {
        val state = _spelling.value
        if (!state.isSubmitted || state.isPersisting) return
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
                isPersisting = false,
                persistenceError = null,
                statusMessage = null,
                totalInRound = spellingRoundWords.size
            )
        }
    }

    private suspend fun loadActiveSession(active: ReviewRepository.ActiveSession): Boolean {
        sessionId = active.sessionId
        reviewWords = active.words
        allWordsCache = words.allWords(active.libraryId)
        choiceDistractorCache = withContext(Dispatchers.Default) {
            ChoiceOptionGenerator.prepareDistractors(active.words, allWordsCache)
        }
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

    private fun filterWords(words: List<LibraryWordEntry>, query: String): List<LibraryWordEntry> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return words
        return words.filter { it.normalizedTerm.contains(q) || it.meaningText.contains(query.trim()) }
    }

    private fun distinctMeaningCount(words: List<LibraryWordEntry>): Int =
        words.map { it.meaningText.trim() }.filter { it.isNotBlank() }.distinct().size

    private fun selectValidLibrary(libraries: List<WordLibrarySummary>, currentId: Long): Long {
        if (libraries.any { it.id == currentId }) return currentId
        val saved = preferences.getLong("last_library_id", 0L)
        return libraries.firstOrNull { it.id == saved }?.id ?: libraries.firstOrNull()?.id ?: 0L
    }

    private suspend fun refreshDetail(libraryId: Long, wordId: Long, message: String? = null) {
        val word = words.getWord(libraryId, wordId)
        val states = if (word != null) words.memoryStates(wordId) else null
        _detail.value = DetailState(
            libraryId = libraryId,
            word = word,
            recognition = states?.first,
            spelling = states?.second,
            editText = word?.meaningText.orEmpty(),
            message = message
        )
    }

    private fun clearReviewSessionState() {
        sessionId = 0L
        reviewWords = emptyList()
        choiceRoundWords = emptyList()
        choiceWrongWords = mutableListOf()
        spellingRoundWords = emptyList()
        spellingWrongWords = mutableListOf()
        choiceDistractorCache = emptyMap()
        _choice.value = ChoiceState()
        _spelling.value = SpellingState()
        _summary.value = ReviewSummary(0, 0, 0, 0, 0)
    }

    private fun invalidChoiceSessionMessage(): String =
        "这次未完成复习无法生成选择题。请先跳过它，确认词库至少有 3 个不同释义后重新开始。"
}
