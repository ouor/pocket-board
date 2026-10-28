package com.ouor.pocketboard.core.ink.segment

import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.ink.RecognitionOutcome
import com.ouor.pocketboard.core.ink.SegmentationMode
import com.ouor.pocketboard.core.ink.line
import com.ouor.pocketboard.core.ink.rules.InkConfig
import com.ouor.pocketboard.core.ink.rules.defaultInkRules
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 현장 버그: 같은 자리에 빨리(쉼 100~250ms) 겹쳐 쓴 "ㅋㅋㅋㅋ" → "ㅋ".
 * 가짜 엔진은 ML Kit 의 관찰된 동작을 흉내 낸다: x 로 붙은 획들을 한 글자 자리로 보고, 한 자리에 같은 글자가
 * 여러 번 겹쳐 있으면 그 글자 하나로 읽는다. 획은 시작 시각으로 원래 글자를 찾는다(재배치는 시각을 안 바꾼다).
 */
class OverlapSplitTest {
    /** 글자 하나: 획 모양(0..100 칸, 왼쪽 x 오프셋을 받음)과 앞 k획까지의 읽기. */
    private class Glyph(val readings: List<String>, val shapes: List<(Float, Long) -> InkStroke>)

    private fun g(vararg readings: String, shapes: List<(Float, Long) -> InkStroke>) = Glyph(readings.toList(), shapes)
    private fun seg(x0: Float, y0: Float, x1: Float, y1: Float): (Float, Long) -> InkStroke =
        { ox, t -> line(ox + x0, y0, ox + x1, y1, t, 120) }

    private val KIEUK = g("ㄱ", "ㅋ", shapes = listOf(seg(10f, 10f, 90f, 90f), seg(15f, 50f, 80f, 50f)))
    private val GA = g("ㄱ", "기", "가", shapes = listOf(seg(5f, 20f, 60f, 70f), seg(60f, 5f, 60f, 95f), seg(60f, 50f, 90f, 50f)))
    private val HAN = g("ㅡ", "ㅜ", "ㅎ", "히", "하", "한", shapes = listOf(
        seg(20f, 5f, 40f, 5f), seg(5f, 20f, 55f, 20f), seg(15f, 30f, 45f, 55f),
        seg(60f, 5f, 60f, 70f), seg(60f, 35f, 85f, 35f), seg(10f, 75f, 80f, 95f),
    ))
    private val NYEONG = g("ㄴ", "ㄴ", "녀", "녀", "녕", shapes = listOf(
        seg(5f, 5f, 40f, 40f), seg(40f, 20f, 60f, 20f), seg(40f, 40f, 60f, 40f), seg(60f, 0f, 60f, 60f), seg(20f, 70f, 70f, 95f),
    ))
    private val BWELK = g(*Array(12) { "?" }, "뷁", shapes = listOf(
        seg(5f, 5f, 5f, 30f), seg(35f, 5f, 35f, 30f), seg(5f, 15f, 35f, 15f), seg(5f, 30f, 35f, 30f),   // ㅂ
        seg(5f, 40f, 55f, 40f), seg(30f, 40f, 30f, 55f),                                                 // ㅜ
        seg(55f, 30f, 62f, 30f), seg(62f, 5f, 62f, 60f), seg(72f, 5f, 72f, 60f),                         // ㅔ
        seg(5f, 65f, 40f, 65f), seg(40f, 65f, 5f, 80f), seg(5f, 80f, 40f, 95f),                          // ㄹ
        seg(45f, 65f, 85f, 95f),                                                                         // ㄱ
    ))
    private val H = g("l", "h", shapes = listOf(seg(0f, 0f, 0f, 90f), seg(0f, 45f, 30f, 90f)))
    private val I = g("i", "i", shapes = listOf(seg(45f, 45f, 45f, 90f), seg(45f, 30f, 45f, 30f)))

    private data class Mark(val char: Int, val glyph: Glyph, val index: Int)

    private val marks = HashMap<Long, Mark>()
    private val calls = mutableListOf<List<InkStroke>>()
    private val logs = mutableListOf<String>()

    /** 글자들을 [xs] 자리에 차례로 쓴다. 획 사이 쉼은 글자 안이든 사이든 100~250ms(현장처럼 구분 안 됨). */
    private fun write(glyphs: List<Glyph>, xs: List<Float>): List<InkItem> {
        val pauses = longArrayOf(150, 220, 120, 250, 180, 100, 200)
        var t = 0L
        var n = 0
        return glyphs.flatMapIndexed { c, glyph ->
            glyph.shapes.mapIndexed { i, shape ->
                val s = shape(xs[c], t)
                marks[t] = Mark(c, glyph, i)
                t += 120 + pauses[n++ % pauses.size]
                InkItem.Stroke(n.toLong(), s)
            }
        }
    }

    private val engine = InkEngine { lang, strokes, _, _ ->
        calls += strokes
        // x 로 10dp 안에 붙은 획끼리 한 자리
        val groups = ArrayList<MutableList<InkStroke>>()
        var right = Float.NEGATIVE_INFINITY
        for (s in strokes.sortedBy { it.bounds().left }) {
            if (groups.isEmpty() || s.bounds().left > right + 10f) groups += ArrayList<InkStroke>()
            groups.last() += s
            right = maxOf(if (groups.last().size == 1) Float.NEGATIVE_INFINITY else right, s.bounds().right)
        }
        val text = groups.joinToString("") { group ->
            val byChar = group.map { marks.getValue(it.points.first().t) }.groupBy { it.char }.values
            val glyph = byChar.first().first().glyph
            val prefixes = byChar.map { m -> m.map { it.index }.sorted().let { if (it == it.indices.toList()) it.size else -1 } }
            when {
                byChar.any { it.first().glyph !== glyph } || -1 in prefixes -> "?"
                byChar.size == 1 -> glyph.readings[prefixes[0] - 1]
                glyph.readings.size in prefixes -> glyph.readings.last()   // 겹친 같은 글자 → 하나로 읽음(현장)
                else -> "?"
            }
        }
        if (lang == InkLanguage.EN && text.any { it.code >= 128 }) emptyList() else listOf(text)
    }

    private fun pipeline(config: InkConfig = InkConfig()) = InkPipeline(engine, config, defaultInkRules(config)) { logs += it }

    private suspend fun run(glyphs: List<Glyph>, xs: List<Float>, config: InkConfig = InkConfig()) =
        pipeline(config).recognize(write(glyphs, xs), "", SegmentationMode.RULES) as RecognitionOutcome.Text

    @Test
    fun overlapped_kk_fast() = runTest {
        val out = run(List(2) { KIEUK }, List(2) { 0f })
        assertEquals("ㅋㅋ", out.text)
        assertTrue("split/stroke-excess" in out.ruleIds)
        assertEquals(2 + 3 + 2, calls.size)   // 첫 인식 + 자리 찾기(ㄱ, ㅋ, ㅋ) + 재인식
    }

    @Test
    fun overlapped_kkkk_fast() = runTest {
        val out = run(List(4) { KIEUK }, List(4) { 0f })
        assertEquals("ㅋㅋㅋㅋ", out.text)
        assertEquals(2 + 5 + 2, calls.size)
        assertTrue(logs.single(), logs.single().startsWith("rules / n=8 | 0 N first | 1 S in=1.00 p="))
        assertTrue(logs.single(), logs.single().endsWith("| split#0 ㅋ x4 calls=5"))
    }

    @Test
    fun overlapped_kkkk_overBudget_keepsFirstReading() = runTest {
        assertEquals("ㅋ", run(List(4) { KIEUK }, List(4) { 0f }, InkConfig(maxSplitCalls = 3)).text)
        assertEquals(2 + 3, calls.size)
    }

    @Test
    fun sideBySide_kkkk_noExtraCalls() = runTest {
        val out = run(List(4) { KIEUK }, listOf(0f, 130f, 260f, 390f))
        assertEquals("ㅋㅋㅋㅋ", out.text)
        assertTrue("segment/right-of" in out.ruleIds)
        assertEquals(2, calls.size)
    }

    @Test
    fun single_k() = runTest {
        assertEquals("ㅋ", run(listOf(KIEUK), listOf(0f)).text)
        assertEquals(2, calls.size)
    }

    @Test
    fun multiStrokeSyllables_inPlace_unchanged() = runTest {
        for ((glyph, text) in listOf(GA to "가", HAN to "한", NYEONG to "녕", BWELK to "뷁")) {
            calls.clear()
            val out = run(listOf(glyph), listOf(0f))
            assertEquals(text, out.text)
            assertTrue(text, "split/stroke-excess" !in out.ruleIds)
            assertEquals(text, 2, calls.size)
        }
    }

    @Test
    fun englishWord_continuous_unchanged() = runTest {
        val out = run(listOf(H, I), listOf(0f, 0f))
        assertEquals("hi", out.text)
        assertEquals(2, calls.size)
    }
}
