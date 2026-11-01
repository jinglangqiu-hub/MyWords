package com.example.wordreview.domain

import com.example.wordreview.data.local.entity.LibraryWordEntry
import com.example.wordreview.data.local.entity.MemoryStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CoreTest {
    @Test
    fun csvParser_supportsBomHeaderQuotedCommaAndReportsBrokenQuotes() {
        val content = "source language,target language,source text,target text\n" +
            "英语,中文（简体）,auction,\"拍卖, 竞拍\"\n"
        val decoded = CsvParser.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + content.toByteArray())
        val parsed = CsvParser.parse(decoded.text)

        assertEquals("UTF-8 BOM", decoded.encodingName)
        assertTrue(CsvParser.isRecognizedHeader(parsed.rows.first().fields))
        assertEquals("拍卖, 竞拍", parsed.rows[1].fields[3])

        val broken = CsvParser.parse("英语,中文,word,\"未闭合")
        assertEquals("引号未闭合", broken.issues.single().reason)
        assertEquals(1, broken.issues.single().lineNumber)
    }

    @Test
    fun csvDecoder_supportsUtf16AndGb18030Fallback() {
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "英语,中文,word,释义".toByteArray(Charsets.UTF_16LE)
        assertEquals("UTF-16LE", CsvParser.decode(utf16).encodingName)

        val gb = "英语,中文,word,释义".toByteArray(charset("GB18030"))
        val decodedGb = CsvParser.decode(gb)
        assertEquals("GB18030", decodedGb.encodingName)
        assertTrue(decodedGb.text.contains("释义"))
    }

    @Test
    fun schedulerUsesSharedMemoryStateForLibraryCandidates() {
        val now = 10_000L
        val words = listOf(
            entry(1, "alpha", 1),
            entry(2, "beta", 2)
        )
        val states = listOf(
            MemoryStateEntity(wordId = 1, type = MEMORY_RECOGNITION, dueAt = now + 100_000, lastReviewedAt = 1),
            MemoryStateEntity(wordId = 1, type = MEMORY_SPELLING, dueAt = now + 100_000, lastReviewedAt = 1),
            MemoryStateEntity(wordId = 2, type = MEMORY_RECOGNITION, dueAt = 0, lapseCount = 2, lastReviewedAt = 1)
        )

        assertEquals(2L, ReviewScheduler.pick(words, states, 1, now).single().id)
    }

    @Test
    fun choiceOptionsPreferVisuallySimilarWords() {
        val target = ReviewWord(1, "affect", "affect", "影响", false)
        val words = listOf(
            entry(1, "affect", 1, "影响"),
            entry(2, "effect", 2, "结果"),
            entry(3, "affection", 3, "喜爱"),
            entry(4, "banana", 4, "香蕉"),
            entry(5, "zoology", 5, "动物学")
        )

        val question = ChoiceOptionGenerator.makeQuestion(target, words)!!

        assertTrue(question.options.any { it.meaning == "结果" })
        assertFalse(question.options.any { it.displayTerm == "affection" })
        assertEquals(3, question.options.size)
    }

    @Test
    fun choiceOptionsTreatTransposedLettersAsConfusableAndRemainDistinct() {
        val target = ReviewWord(1, "form", "form", "形式", false)
        val words = listOf(
            entry(1, "form", 1, "形式"),
            entry(2, "from", 2, "来自"),
            entry(3, "farm", 3, "农场"),
            entry(4, "table", 4, "桌子"),
            entry(5, "formal", 5, "农场")
        )

        val question = ChoiceOptionGenerator.makeQuestion(target, words)!!

        assertEquals(3, question.options.distinct().size)
        assertEquals(setOf("形式", "来自", "农场"), question.options.map { it.meaning }.toSet())
    }

    @Test
    fun preparedChoiceOptionsCanBeReusedWithoutRescanningTheLibrary() {
        val target = ReviewWord(1, "accept", "accept", "接受", false)
        val words = listOf(
            entry(1, "accept", 1, "接受"),
            entry(2, "except", 2, "除外"),
            entry(3, "expect", 3, "期待"),
            entry(4, "banana", 4, "香蕉")
        )

        val prepared = ChoiceOptionGenerator.prepareDistractors(listOf(target), words)
        val question = ChoiceOptionGenerator.makePreparedQuestion(target, prepared.getValue(target.id))!!

        assertEquals(setOf("接受", "除外", "期待"), question.options.map { it.meaning }.toSet())
    }

    @Test
    fun schedulerDoesNotFillTheSessionWithNotYetDueWords() {
        val states = listOf(MEMORY_RECOGNITION, MEMORY_SPELLING).map {
            MemoryStateEntity(wordId = 1, type = it, dueAt = 20_000, lastReviewedAt = 100)
        }
        assertEquals(listOf(2L), ReviewScheduler.pick(listOf(entry(1, "apple", 1), entry(2, "pear", 2)), states, 2, 10_000).map { it.id })
        assertTrue(ReviewScheduler.pick(listOf(entry(1, "apple", 1)), states, 1, 10_000).isEmpty())
    }

    @Test
    fun schedulerRotatesOldLapsesAndUsesSpellingDueDate() {
        val states = listOf(
            MemoryStateEntity(wordId = 1, type = MEMORY_RECOGNITION, dueAt = 0, lapseCount = 100, lastReviewedAt = 900),
            MemoryStateEntity(wordId = 1, type = MEMORY_SPELLING, dueAt = 0, lastReviewedAt = 900),
            MemoryStateEntity(wordId = 2, type = MEMORY_RECOGNITION, dueAt = 20_000, lastReviewedAt = 100),
            MemoryStateEntity(wordId = 2, type = MEMORY_SPELLING, dueAt = 5_000, lastReviewedAt = 100)
        )
        assertEquals(2L, ReviewScheduler.pick(listOf(entry(1, "alpha", 1), entry(2, "beta", 2)), states, 1, 10_000).single().id)
    }

    @Test
    fun schedulerIgnoresDisabledSpellingAndImportedUnreviewedStates() {
        val word = entry(1, "apple", 1).copy(spellingDisabled = true)
        val states = listOf(
            MemoryStateEntity(wordId = 1, type = MEMORY_RECOGNITION, dueAt = 20_000, lastReviewedAt = 100),
            MemoryStateEntity(wordId = 1, type = MEMORY_SPELLING, dueAt = 0)
        )
        assertTrue(ReviewScheduler.pick(listOf(word), states, 1, 10_000).isEmpty())
    }

    @Test
    fun choiceOptionsExcludeInflectionsDerivationsAndOverlappingSenses() {
        val target = ReviewWord(1, "create", "create", "v. 创造；创建", false)
        val words = listOf(
            entry(1, "create", 1, target.meaningText),
            entry(2, "creation", 2, "n. 创造物"),
            entry(3, "creative", 3, "adj. 有创造力的"),
            entry(4, "created", 4, "已创建"),
            entry(5, "invent", 5, "v. 发明；创造"),
            entry(6, "crate", 6, "板条箱"),
            entry(7, "crane", 7, "起重机")
        )
        val question = ChoiceOptionGenerator.makeQuestion(target, words)!!
        assertEquals(setOf("create", "crate", "crane"), question.options.map { it.displayTerm }.toSet())
    }

    @Test
    fun choiceOptionsNormalizePartsOfSpeechPunctuationAndChineseSuffixes() {
        val target = ReviewWord(1, "quick", "quick", "adj. 快速的", false)
        val words = listOf(
            entry(1, "quick", 1, target.meaningText),
            entry(2, "rapid", 2, "快速"),
            entry(3, "quickly", 3, "adv. 迅速地"),
            entry(4, "quack", 4, "嘎嘎叫"),
            entry(5, "brick", 5, "砖块")
        )
        val question = ChoiceOptionGenerator.makeQuestion(target, words)!!
        assertEquals(setOf("quick", "quack", "brick"), question.options.map { it.displayTerm }.toSet())
    }

    @Test
    fun safeOptionsKeepTheirSourceWordsAndAllowOneDistractorWithoutUnsafeFallback() {
        val target = ReviewWord(1, "help", "help", "帮助", false)
        val words = listOf(entry(1, "help", 1, "帮助"), entry(2, "helpful", 2, "有帮助的"), entry(3, "kelp", 3, "海带"))
        val question = ChoiceOptionGenerator.makeQuestion(target, words)!!
        assertEquals(2, question.options.size)
        assertEquals("kelp", question.options.single { it.meaning == "海带" }.displayTerm)
        assertNull(ChoiceOptionGenerator.makeQuestion(target, words.take(2)))
    }

    @Test
    fun spellingRetryQueueDeduplicatesAndStopsAfterTwoReinforcementRounds() {
        assertEquals(listOf(1L, 2L), SpellingRetryPolicy.nextRound(listOf(1L, 1L, 2L), 1))
        assertEquals(listOf(2L), SpellingRetryPolicy.nextRound(listOf(2L), 2))
        assertTrue(SpellingRetryPolicy.nextRound(listOf(2L), 3).isEmpty())
        assertTrue(SpellingRetryPolicy.nextRound(listOf(2L), 10).isEmpty())
        assertTrue(SpellingRetryPolicy.nextRound(emptyList(), 1).isEmpty())
    }

    private fun entry(id: Long, term: String, updatedAt: Long, meaning: String = "释义$id") = LibraryWordEntry(
        wordId = id,
        normalizedTerm = term,
        displayTerm = term,
        sourceLang = "英语",
        targetLang = "中文",
        meaningText = meaning,
        spellingDisabled = false,
        addedAt = updatedAt,
        updatedAt = updatedAt
    )
}
