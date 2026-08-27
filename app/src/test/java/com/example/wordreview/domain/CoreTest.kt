package com.example.wordreview.domain

import com.example.wordreview.data.local.entity.LibraryWordEntry
import com.example.wordreview.data.local.entity.MemoryStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            MemoryStateEntity(wordId = 1, type = MEMORY_RECOGNITION, dueAt = now + 100_000),
            MemoryStateEntity(wordId = 2, type = MEMORY_RECOGNITION, dueAt = 0, lapseCount = 2)
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

        assertEquals(setOf("影响", "结果", "喜爱"), question.options.toSet())
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
        assertEquals(setOf("形式", "来自", "农场"), question.options.toSet())
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

        assertEquals(setOf("接受", "除外", "期待"), question.options.toSet())
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
