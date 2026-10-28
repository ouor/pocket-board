package com.ouor.pocketboard.core.ink.segment

import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.ink.InkReport
import com.ouor.pocketboard.core.ink.RecognitionOutcome
import com.ouor.pocketboard.core.ink.SegmentationMode
import com.ouor.pocketboard.core.ink.line
import com.ouor.pocketboard.core.ink.rules.InkConfig
import com.ouor.pocketboard.core.ink.rules.defaultInkRules
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InkReportTest {
    // 현장 "이게" + 스페이스 + "가": ㅇ, ㅣ(right-of → 합침), 1.5초 뒤 ㅇ 자리에 다음 글자(long-pause), 스페이스, 한 획
    private val ieung = line(0f, 0f, 60f, 100f, 0)
    private val bar = line(110f, 0f, 110f, 100f, 300)
    private val next = line(10f, 20f, 50f, 80f, 1900)
    private val ga = line(0f, 0f, 50f, 50f, 3000)
    private val items = listOf(InkItem.Stroke(1, ieung), InkItem.Stroke(2, bar), InkItem.Stroke(3, next), InkItem.Space, InkItem.Stroke(4, ga))

    private val engine = InkEngine { lang, strokes, _, _ ->
        when {
            lang != InkLanguage.KO -> listOf("ol")
            strokes == listOf(ieung, bar) -> listOf("이", "ol")
            strokes.size == 3 -> listOf("이게")
            else -> listOf("가")
        }
    }

    private suspend fun record(): Pair<RecognitionOutcome, InkReport> {
        var report: InkReport? = null
        // right-of 합침 확인까지 기록되게 S2 를 옛 값(0.3)으로
        val config = InkConfig(rightOfGapRatio = 0.3f)
        val out = InkPipeline(engine, config, defaultInkRules(config)).recognize(items, "앞", SegmentationMode.RULES) { report = it }
        return out to report!!
    }

    @Test
    fun report_describesSegmentationMergeAndFinalRead() = runTest {
        val (out, r) = record()
        assertEquals("이게 가", (out as RecognitionOutcome.Text).text)
        assertEquals(out.text, r.text)
        assertEquals(out.ruleIds, r.ruleIds)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3)), r.words.map { it.strokes })

        val w = r.words[0]
        assertEquals(listOf("segment/first", "segment/right-of", "segment/long-pause"), w.segments.map { it.ruleId })
        assertNull(w.segments[0].pauseMs)
        assertEquals(1500L, w.segments[2].pauseMs)
        assertEquals(listOf(listOf(0, 1), listOf(2)), w.clusters)
        assertEquals(1, w.merges.single().cluster)
        assertEquals(listOf("이", "ol"), w.merges.single().ko)
        assertEquals(true, w.merges.single().merged)
        assertEquals(listOf(0f, 0f, 120f), w.finalDx)   // 셋째 획만 ㅣ 오른쪽으로 재배치
        assertEquals(listOf("이게"), w.ko)
        assertEquals("candidate/ko-hangul", w.candidateRuleId)
        assertEquals("앞이게 ", r.words[1].calls.first().preContext)
    }

    @Test
    fun report_jsonRoundTrip_thenReplay_reproducesDecisions() = runTest {
        val (_, r) = record()
        val saved = Json.decodeFromString(InkReport.serializer(), Json.encodeToString(InkReport.serializer(), r))
        assertEquals(r, saved)

        val engine = RecordedInkEngine(items, saved)
        val again = replayInk(items, saved, engine)
        assertEquals(0, engine.misses)
        assertEquals(r, again)
    }

    @Test
    fun raw_reportsWholeWordAsOneCluster() = runTest {
        var r: InkReport? = null
        InkPipeline(engine, InkConfig(), defaultInkRules()).recognize(items, "", SegmentationMode.RAW) { r = it }
        val w = r!!.words[0]
        assertEquals("raw", r!!.mode)
        assertEquals(emptyList<Any>(), w.segments)
        assertEquals(listOf(listOf(0, 1, 2)), w.clusters)
        assertEquals(listOf(0f, 0f, 0f), w.finalDx)
        assertEquals(2, w.calls.size)
    }
}
