package com.example.wordreview.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class TtsStatus {
    INITIALIZING,
    READY,
    LANGUAGE_UNAVAILABLE,
    FAILED
}

class TtsManager(context: Context) {
    private var tts: TextToSpeech? = null
    private val ttsContext: Context = context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow(TtsStatus.INITIALIZING)
    val status: StateFlow<TtsStatus> = _status.asStateFlow()

    init {
        initialize()
    }

    fun reinitialize() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        initialize()
    }

    private fun initialize() {
        _status.value = TtsStatus.INITIALIZING
        tts = TextToSpeech(ttsContext) { status ->
            mainHandler.post { handleInit(status) }
        }
    }

    private fun handleInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            _status.value = TtsStatus.FAILED
            return
        }
        val languageResult = setEnglishLanguage()
        _status.value = when (languageResult) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> TtsStatus.LANGUAGE_UNAVAILABLE
            null -> TtsStatus.FAILED
            else -> TtsStatus.READY
        }
    }

    private fun setEnglishLanguage(): Int? {
        val engine = tts ?: return null
        val locales = listOf(Locale.US, Locale.UK, Locale.ENGLISH)
        var lastResult: Int? = null
        locales.forEach { locale ->
            val result = engine.setLanguage(locale)
            lastResult = result
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                return result
            }
        }
        return lastResult
    }

    fun speak(text: String): Boolean {
        if (_status.value != TtsStatus.READY) return false
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "word_${System.currentTimeMillis()}")
        return result == TextToSpeech.SUCCESS
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        _status.value = TtsStatus.FAILED
    }
}
