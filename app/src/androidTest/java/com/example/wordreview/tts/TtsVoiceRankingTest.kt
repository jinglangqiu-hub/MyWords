package com.example.wordreview.tts

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TtsVoiceRankingTest {
    @Test
    fun embeddedRankingExcludesNetworkUninstalledAndNonEnglishVoices() {
        val offline = voice("offline-en", Locale.US, Voice.QUALITY_HIGH, false)
        val network = voice("network-en", Locale.US, Voice.QUALITY_VERY_HIGH, true)
        val missing = voice(
            "missing-en",
            Locale.US,
            Voice.QUALITY_VERY_HIGH,
            false,
            setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        )
        val chinese = voice("offline-zh", Locale.CHINA, Voice.QUALITY_VERY_HIGH, false)

        assertEquals(listOf(offline), rankEmbeddedEnglishVoices(listOf(network, missing, chinese, offline)))
    }

    @Test
    fun allVoiceFallbackAllowsNetworkButPrefersInstalledOfflineVoice() {
        val network = voice("network-en", Locale.US, Voice.QUALITY_VERY_HIGH, true)
        val offline = voice("offline-en", Locale.UK, Voice.QUALITY_NORMAL, false)
        val result = rankAllInstalledEnglishVoices(listOf(network, offline))

        assertEquals(offline, result.first())
        assertTrue(network in result)
    }

    private fun voice(
        name: String,
        locale: Locale,
        quality: Int,
        networkRequired: Boolean,
        features: Set<String> = emptySet()
    ) = Voice(name, locale, quality, Voice.LATENCY_NORMAL, networkRequired, features)
}
