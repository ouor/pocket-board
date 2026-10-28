package com.ouor.pocketboard.data

import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.common.ink.InkPoint
import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.ink.InkReport
import com.ouor.pocketboard.core.ink.RecognitionOutcome
import com.ouor.pocketboard.core.ink.SegmentationMode
import com.ouor.pocketboard.core.ink.rules.InkConfig
import com.ouor.pocketboard.core.ink.rules.defaultInkRules
import com.ouor.pocketboard.core.ink.segment.InkEngine
import com.ouor.pocketboard.core.ink.segment.InkLanguage
import com.ouor.pocketboard.core.ink.segment.InkPipeline
import com.ouor.pocketboard.core.ink.segment.RecordedInkEngine
import com.ouor.pocketboard.core.ink.segment.replayInk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class InkRecordTest {
    // ink.md §3.2 예: ㅇ, ㅣ(right-of → 합침), 1.5초 뒤 다음 글자(long-pause), 스페이스, 점 한 획
    private val ieung = line(0f, 0f, 60f, 100f, 10_000)
    private val bar = line(110f, 0f, 110f, 100f, 10_300)
    private val next = line(10f, 20f, 50f, 80f, 11_900)
    private val dot = InkStroke(listOf(InkPoint(5.25f, 7.75f, 13_000)))
    private val items = listOf(InkItem.Stroke(11, ieung), InkItem.Stroke(12, bar), InkItem.Stroke(13, next), InkItem.Space, InkItem.Stroke(14, dot))

    private val engine = InkEngine { lang, strokes, _, _ ->
        when {
            lang != InkLanguage.KO -> listOf("o<l>")
            strokes == listOf(ieung, bar) -> listOf("이", "ol")
            strokes.size == 3 -> listOf("이게")
            else -> listOf("&")
        }
    }

    private suspend fun record(): Pair<RecognitionOutcome, InkReport> {
        var report: InkReport? = null
        val config = InkConfig(rightOfGapRatio = 0.3f) // right-of 합침 확인까지 기록되게 S2 를 옛 값(0.3)으로
        val out = InkPipeline(engine, config, defaultInkRules(config)).recognize(items, "앞\"<", SegmentationMode.RULES) { report = it }
        return out to report!!
    }

    @Test
    fun svg_isWellFormed_threePathsPerStroke_textEscaped_spacesVisible() = runTest {
        val (_, r) = record()
        val result = "Done(<sent> & \"ok\")"
        val svg = InkSvg.render(DevLogEntry.Ink.of(1L, items, r), result)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg.byteInputStream()) // 깨진 XML 이면 예외
        assertEquals(4 * 3, doc.getElementsByTagName("path").length) // 인식기 입력 띠 + 묶음 칸 + 원래
        val texts = (0 until doc.getElementsByTagName("text").length).map { doc.getElementsByTagName("text").item(it).textContent }
        assertTrue(texts.toString(), texts.any { it == "send: $result" })
        assertTrue(texts.toString(), texts.any { it.contains("o<l>") })
        assertTrue(texts.toString(), "#1 long-pause" in texts) // 묶음 칸 아래 시작 규칙
        assertTrue(texts.toString(), "merged right-of@1" in texts) // ㅇ|ㅣ 합침
        assertTrue(texts.toString(), "text: \"이게␣o<l>\"" in texts) // 공백은 ␣
    }

    @Test
    fun inkEntry_jsonRoundTrip_rebuildsItems_replayReproducesText() = runTest {
        val (out, r) = record()
        val entry = DevLogEntry.Ink.of(42L, items, r)
        assertEquals(listOf(3), entry.spaces)

        val line = DevLog.DEVLOG_JSON.encodeToString(DevLogEntry.serializer(), entry)
        val withNewField = line.replaceFirst("{", "{\"future\":1,")
        val saved = DevLog.DEVLOG_JSON.decodeFromString(DevLogEntry.serializer(), withNewField) as DevLogEntry.Ink
        assertEquals(entry, saved)

        val rebuilt = saved.items()
        assertEquals(items.map { it is InkItem.Space }, rebuilt.map { it is InkItem.Space })
        val replayEngine = RecordedInkEngine(rebuilt, saved.report)
        val again = replayInk(rebuilt, saved.report, replayEngine)
        assertEquals(0, replayEngine.misses)
        assertEquals((out as RecognitionOutcome.Text).text, again.text)
        assertEquals(r.words.map { it.clusters }, again.words.map { it.clusters })
    }

    @Test
    fun svg_showsStackedSyllablesSplit() = runTest {
        // 한 칸에 세로획 10개(겹친 음절): 두 조각이 "가나"로 읽혀 나뉜다 → 묶음 칸 아래·규칙 줄에 규칙 id
        val strokes = List(10) { InkItem.Stroke(it.toLong(), line(it * 10f, 0f, it * 10f, 100f, it * 120L)) }
        val config = InkConfig(splitStrokeRatio = 5f)
        var report: InkReport? = null
        InkPipeline({ lang, s, _, _ -> if (lang == InkLanguage.KO && s.size == 10) listOf("가나") else emptyList() }, config, defaultInkRules(config))
            .recognize(strokes, "", SegmentationMode.RULES) { report = it }
        val svg = InkSvg.render(DevLogEntry.Ink.of(1L, strokes, report!!), "ok")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg.byteInputStream())
        val texts = (0 until doc.getElementsByTagName("text").length).map { doc.getElementsByTagName("text").item(it).textContent }
        assertTrue(texts.toString(), "split stacked-syllables 가나 1/2" in texts && "split stacked-syllables 가나 2/2" in texts)
        assertTrue(texts.toString(), texts.any { it.startsWith("rules:") && "split/stacked-syllables" in it })
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, t0: Long) =
        InkStroke((0..10).map { InkPoint(x0 + (x1 - x0) * it / 10, y0 + (y1 - y0) * it / 10, t0 + it * 10L) })
}
