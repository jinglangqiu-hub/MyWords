package com.example.wordreview.tts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class TtsStatus {
    INITIALIZING, READY, SPEAKING, NO_ENGINE, LANGUAGE_UNAVAILABLE, SYNTHESIS_FAILED, FAILED
}

data class TtsDiagnostics(
    val engineName: String = "",
    val voiceName: String = "",
    val localeTag: String = "",
    val networkRequired: Boolean? = null,
    val languageResult: Int? = null,
    val lastErrorCode: Int? = null,
    val compatibilityMode: Boolean = false,
    val installedEnginePackages: List<String> = emptyList(),
    val enabledEnginePackages: List<String> = emptyList()
) {
    fun description(): String {
        val location = when (networkRequired) {
            true -> "网络语音"
            false -> "离线语音"
            null -> "系统语音"
        }
        val mode = if (compatibilityMode) "兼容模式" else ""
        val engineDetection = when {
            enabledEnginePackages.isNotEmpty() -> "检测到 ${enabledEnginePackages.size} 个可用引擎"
            installedEnginePackages.isNotEmpty() -> "已安装但未启用：${installedEnginePackages.joinToString()}"
            else -> "未检测到 TTS 引擎"
        }
        return listOf(engineName, voiceName, localeTag, location, mode, engineDetection)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }
}

private data class TtsEngineInventory(
    val installedPackages: List<String>,
    val enabledPackages: List<String>,
    val querySucceeded: Boolean
)

private fun detectTtsEngines(context: Context): TtsEngineInventory {
    val packageManager = context.packageManager
    val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
    return runCatching {
        val installed = packageManager.queryTtsServices(intent, PackageManager.MATCH_DISABLED_COMPONENTS.toLong())
        val enabled = packageManager.queryTtsServices(intent, 0L)
        TtsEngineInventory(installed, enabled, true)
    }.getOrElse { TtsEngineInventory(emptyList(), emptyList(), false) }
}

private fun PackageManager.queryTtsServices(intent: Intent, flags: Long): List<String> {
    val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(flags))
    } else {
        @Suppress("DEPRECATION")
        queryIntentServices(intent, flags.toInt())
    }
    return services.mapNotNull { it.serviceInfo?.packageName }.distinct().sorted()
}

fun rankEmbeddedEnglishVoices(voices: Collection<Voice>): List<Voice> =
    voices
        .filter(::isInstalledEnglishVoice)
        .filterNot(Voice::isNetworkConnectionRequired)
        .sortedWith(voiceComparator())

fun rankAllInstalledEnglishVoices(voices: Collection<Voice>): List<Voice> =
    voices.filter(::isInstalledEnglishVoice).sortedWith(
        compareBy<Voice> { it.isNetworkConnectionRequired }
            .then(voiceComparator())
    )

private fun isInstalledEnglishVoice(voice: Voice): Boolean =
    voice.locale.language.equals("en", true) &&
        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()

private fun voiceComparator(): Comparator<Voice> =
    compareByDescending<Voice> { it.quality }
        .thenBy { it.latency }
        .thenBy { localePriority(it.locale) }
        .thenBy { it.name }

private fun localePriority(locale: Locale): Int = when (locale) {
    Locale.US -> 0
    Locale.UK -> 1
    Locale.ENGLISH -> 2
    else -> 3
}

class TtsManager(context: Context) {
    private val ttsContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestCounter = AtomicLong(0)
    private var tts: TextToSpeech? = null
    private var currentRequestId: String? = null
    private var pendingText: String? = null
    private var currentText: String? = null
    private var retryAllowed = true
    private var compatibilityMode = false
    private var initializationGeneration = 0L
    private var startTimeout: Runnable? = null

    private val _status = MutableStateFlow(TtsStatus.INITIALIZING)
    val status: StateFlow<TtsStatus> = _status.asStateFlow()
    private val _diagnostics = MutableStateFlow(TtsDiagnostics())
    val diagnostics: StateFlow<TtsDiagnostics> = _diagnostics.asStateFlow()

    init { initialize() }

    fun reinitialize() {
        pendingText = null
        retryAllowed = true
        compatibilityMode = false
        releaseEngine()
        initialize()
    }

    private fun initialize() {
        val generation = ++initializationGeneration
        _status.value = TtsStatus.INITIALIZING
        updateEngineInventory(detectTtsEngines(ttsContext), reset = true)
        tts = TextToSpeech(ttsContext) { result ->
            mainHandler.post {
                if (generation == initializationGeneration) handleInit(result)
            }
        }
    }

    private fun handleInit(result: Int) {
        val engine = tts
        if (result != TextToSpeech.SUCCESS || engine == null) {
            val inventory = detectTtsEngines(ttsContext)
            updateEngineInventory(inventory)
            _status.value = if (inventory.querySucceeded && inventory.enabledPackages.isEmpty()) {
                TtsStatus.NO_ENGINE
            } else {
                TtsStatus.FAILED
            }
            _diagnostics.value.updateEngineError(result)
            pendingText = null
            return
        }
        engine.setOnUtteranceProgressListener(createProgressListener())
        engine.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        val voices = runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())

        // Compatibility comes first: let the phone's selected TTS engine choose its
        // own default English voice. OEM engines do not always expose reliable voice flags.
        val baselineReady = setCompatibleEnglishLanguage(engine)
        val ready = if (baselineReady) {
            // Improve quality only when an installed embedded voice can be selected.
            // Failure here must not discard the working system-default baseline.
            if (!compatibilityMode) rankEmbeddedEnglishVoices(voices).firstOrNull()?.let { preferred ->
                if (engine.setVoice(preferred) == TextToSpeech.SUCCESS) updateDiagnostics(engine, preferred)
            }
            true
        } else {
            // Some engines report language availability incorrectly. Try their exposed
            // English voices directly, including network voices, before declaring failure.
            rankAllInstalledEnglishVoices(voices).any { candidate ->
                if (engine.setVoice(candidate) == TextToSpeech.SUCCESS) {
                    updateDiagnostics(engine, candidate)
                    true
                } else false
            }
        }

        if (!ready) {
            _status.value = TtsStatus.LANGUAGE_UNAVAILABLE
            pendingText = null
            return
        }
        if (_diagnostics.value.voiceName.isBlank()) updateDiagnostics(engine, engine.voice)
        _status.value = TtsStatus.READY
        pendingText?.also { text -> pendingText = null; speakInternal(text) }
    }

    private fun setCompatibleEnglishLanguage(engine: TextToSpeech): Boolean {
        for (locale in listOf(Locale.US, Locale.UK, Locale.ENGLISH)) {
            val availability = engine.isLanguageAvailable(locale)
            _diagnostics.value = _diagnostics.value.copy(
                engineName = engine.defaultEngine.orEmpty(),
                languageResult = availability
            )
            if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) continue
            val result = engine.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED && result != TextToSpeech.ERROR) {
                updateDiagnostics(engine, engine.voice, result)
                return true
            }
        }
        return false
    }

    private fun updateDiagnostics(engine: TextToSpeech, voice: Voice?, languageResult: Int? = _diagnostics.value.languageResult) {
        _diagnostics.value = _diagnostics.value.copy(
            engineName = engine.defaultEngine.orEmpty(),
            voiceName = voice?.name.orEmpty(),
            localeTag = voice?.locale?.toLanguageTag().orEmpty(),
            networkRequired = voice?.isNetworkConnectionRequired,
            languageResult = languageResult,
            lastErrorCode = null,
            compatibilityMode = compatibilityMode
        )
    }

    fun speak(text: String): Boolean {
        if (text.isBlank()) return false
        currentText = text
        retryAllowed = true
        return when (_status.value) {
            TtsStatus.INITIALIZING -> { pendingText = text; true }
            TtsStatus.READY, TtsStatus.SPEAKING -> speakInternal(text)
            TtsStatus.SYNTHESIS_FAILED -> { compatibilityMode = true; retryOnce(text); true }
            else -> false
        }
    }

    private fun speakInternal(text: String): Boolean {
        cancelStartTimeout()
        currentText = text
        val requestId = "word_${requestCounter.incrementAndGet()}"
        currentRequestId = requestId
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, requestId) ?: TextToSpeech.ERROR_SERVICE
        if (result != TextToSpeech.SUCCESS) {
            handleQueueError(text, result)
            return _status.value == TtsStatus.INITIALIZING
        }
        // SUCCESS only means queued. Some engines silently drop the request;
        // recover once if no start/done/error callback arrives.
        startTimeout = Runnable {
            if (currentRequestId == requestId) {
                handleQueueError(text, TextToSpeech.ERROR_SERVICE)
            }
        }.also { mainHandler.postDelayed(it, 10_000L) }
        return true
    }

    private fun createProgressListener() = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = postForCurrent(utteranceId) {
            cancelStartTimeout()
            _status.value = TtsStatus.SPEAKING
        }
        override fun onDone(utteranceId: String) = postForCurrent(utteranceId) {
            cancelStartTimeout()
            currentRequestId = null
            _status.value = TtsStatus.READY
        }
        override fun onStop(utteranceId: String, interrupted: Boolean) = postForCurrent(utteranceId) {
            cancelStartTimeout()
            currentText?.let { handleQueueError(it, TextToSpeech.ERROR_OUTPUT) }
        }
        @Deprecated("Deprecated Android callback")
        override fun onError(utteranceId: String) = handleSynthesisError(utteranceId, TextToSpeech.ERROR)
        override fun onError(utteranceId: String, errorCode: Int) = handleSynthesisError(utteranceId, errorCode)
    }

    private fun handleSynthesisError(utteranceId: String, errorCode: Int) = postForCurrent(utteranceId) {
        cancelStartTimeout()
        _diagnostics.value = _diagnostics.value.copy(lastErrorCode = errorCode)
        _status.value = TtsStatus.SYNTHESIS_FAILED
        when {
            !compatibilityMode -> {
                compatibilityMode = true
                currentText?.let(::retryOnce)
            }
            isTransientError(errorCode) -> currentText?.let(::retryOnce)
        }
    }

    private fun handleQueueError(text: String, errorCode: Int) {
        _diagnostics.value = _diagnostics.value.copy(lastErrorCode = errorCode)
        _status.value = TtsStatus.SYNTHESIS_FAILED
        compatibilityMode = true
        retryOnce(text)
    }

    private fun isTransientError(errorCode: Int): Boolean = errorCode in setOf(
        TextToSpeech.ERROR,
        TextToSpeech.ERROR_SERVICE,
        TextToSpeech.ERROR_OUTPUT,
        TextToSpeech.ERROR_SYNTHESIS
    )

    private fun retryOnce(text: String) {
        if (!retryAllowed) { _status.value = TtsStatus.SYNTHESIS_FAILED; return }
        retryAllowed = false
        pendingText = text
        releaseEngine()
        initialize()
    }

    private fun postForCurrent(utteranceId: String, action: () -> Unit) {
        mainHandler.post { if (utteranceId == currentRequestId) action() }
    }

    private fun TtsDiagnostics.updateEngineError(errorCode: Int) {
        _diagnostics.value = copy(lastErrorCode = errorCode)
    }

    private fun updateEngineInventory(inventory: TtsEngineInventory, reset: Boolean = false) {
        val current = if (reset) TtsDiagnostics() else _diagnostics.value
        _diagnostics.value = current.copy(
            installedEnginePackages = inventory.installedPackages,
            enabledEnginePackages = inventory.enabledPackages
        )
    }

    private fun releaseEngine() {
        cancelStartTimeout()
        currentRequestId = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun cancelStartTimeout() {
        startTimeout?.let(mainHandler::removeCallbacks)
        startTimeout = null
    }

    fun shutdown() {
        initializationGeneration++
        pendingText = null
        currentText = null
        releaseEngine()
        _status.value = TtsStatus.FAILED
    }
}

fun ttsErrorMessage(errorCode: Int?): String? = when (errorCode) {
    TextToSpeech.ERROR_NOT_INSTALLED_YET -> "英文语音数据尚未下载，请在系统语音设置中安装后重试。"
    TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> "当前语音需要网络，但连接或语音服务暂时不可用。"
    TextToSpeech.ERROR_SERVICE -> "系统 TTS 服务已断开，请重新检测或切换语音引擎。"
    TextToSpeech.ERROR_OUTPUT -> "系统无法播放语音，请检查媒体音量和音频输出设备。"
    TextToSpeech.ERROR_INVALID_REQUEST -> "TTS 引擎拒绝了本次发音请求。"
    TextToSpeech.ERROR_SYNTHESIS -> "TTS 引擎无法合成这个单词。"
    TextToSpeech.ERROR -> "TTS 引擎没有提供详细错误码；应用已尝试切换兼容模式。若仍无声，请更换系统默认语音或检查英文语音包。"
    else -> null
}
