package com.ouor.pocketboard.core.ink.segment

import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.ink.RecognitionOutcome
import com.ouor.pocketboard.core.ink.SegmentationMode
import com.ouor.pocketboard.core.ink.box
import com.ouor.pocketboard.core.ink.line
import com.ouor.pocketboard.core.ink.rules.InkConfig
import com.ouor.pocketboard.core.ink.rules.defaultInkRules
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkPipelineTest {
    private data class Call(val lang: InkLanguage, val strokes: List<InkStroke>, val pre: String)

    private val calls = mutableListOf<Call>()
    private var answer: (Call) -> List<String> = { emptyList() }
    private val engine = InkEngine { lang, strokes, pre, _ -> Call(lang, strokes, pre).also { calls += it }.let(answer) }
    private val pipeline = InkPipeline(engine, InkConfig(), defaultInkRules())

    private fun stroke(s: InkStroke, id: Long = 0) = InkItem.Stroke(id, s)

    // 같은 자리에 1초 간격으로 겹쳐 쓴 두 글자
    private val first = box(0f, 0f, 100f, 0)
    private val second = box(0f, 0f, 100f, 1000)

    @Test
    fun words_spaces_preContext() = runTest {
        answer = { c -> if (c.lang == InkLanguage.KO) listOf(if (c.pre.endsWith(" ")) "에" else "안녕") else emptyList() }
        val out = pipeline.recognize(listOf(stroke(first), InkItem.Space, stroke(second)), "abc", SegmentationMode.RULES)

        assertEquals("안녕 에", (out as RecognitionOutcome.Text).text)
        assertEquals(listOf("abc", "abc", "abc안녕 ", "abc안녕 "), calls.map { it.pre })
        assertTrue("candidate/ko-hangul" in out.ruleIds)
    }

    @Test
    fun allWordsFail_isEmpty() = runTest {
        assertEquals(RecognitionOutcome.Empty, pipeline.recognize(listOf(stroke(first)), "", SegmentationMode.RULES))
    }

    @Test
    fun rules_relayoutsOverlap_raw_doesNot() = runTest {
        pipeline.recognize(listOf(stroke(first), stroke(second)), "", SegmentationMode.RULES)
        assertEquals(120f, calls.first().strokes[1].bounds().left)

        calls.clear()
        pipeline.recognize(listOf(stroke(first), stroke(second)), "", SegmentationMode.RAW)
        assertEquals(listOf(first, second), calls.first().strokes)
    }

    // 현장 "이": ㅣ 가 ㅇ 오른쪽으로 간격(글자 크기×0.3)보다 떨어져 시작해 right-of 로 끊긴다
    private val ieung = line(0f, 0f, 60f, 100f, 0)
    private val bar = line(110f, 0f, 110f, 100f, 300)

    @Test
    fun rules_rightOfVowel_mergedBackIntoSyllable() = runTest {
        answer = { c -> if (c.lang == InkLanguage.KO && c.strokes == listOf(ieung, bar)) listOf("이") else emptyList() }
        val out = pipeline.recognize(listOf(stroke(ieung), stroke(bar)), "", SegmentationMode.RULES)

        assertEquals("이", (out as RecognitionOutcome.Text).text)
        assertEquals(3, calls.size)   // 합침 확인 1 + ko + en
        assertTrue("merge/single-syllable" in out.ruleIds)
    }

    @Test
    fun rules_fieldCase_longPauseOverIeung_isNextChar() = runTest {
        // ㅇ, ㅣ(right-of), 1.5초 쉬고 ㅇ 자리에 다음 글자 첫 획 → [ㅇㅣ] [다음] 으로 나뉘어 ㅣ 오른쪽으로 재배치
        val next = line(10f, 20f, 50f, 80f, 400 + 1500)
        answer = { c ->
            when {
                c.lang != InkLanguage.KO -> emptyList()
                c.strokes == listOf(ieung, bar) -> listOf("이")
                else -> listOf("이게")
            }
        }
        val out = pipeline.recognize(listOf(stroke(ieung), stroke(bar), stroke(next)), "", SegmentationMode.RULES)

        assertEquals("이게", (out as RecognitionOutcome.Text).text)
        assertTrue(out.ruleIds.toString(), "segment/long-pause" in out.ruleIds && "merge/single-syllable" in out.ruleIds)
        assertTrue(calls.last().strokes[2].bounds().left > bar.bounds().right)
    }

    @Test
    fun hypothesis_mergesWhenSingleSyllable() = runTest {
        // 합쳐 인식하면 음절 1개 → 한 글자로 되돌려 재배치하지 않는다
        answer = { c -> if (c.lang == InkLanguage.KO) listOf("안") else emptyList() }
        val out = pipeline.recognize(listOf(stroke(first), stroke(second)), "", SegmentationMode.HYPOTHESIS)

        assertEquals(3, calls.size)   // 가설 1 + ko + en
        assertEquals(listOf(first, second), calls.last().strokes)
        assertTrue("merge/single-syllable" in (out as RecognitionOutcome.Text).ruleIds)
    }
}
