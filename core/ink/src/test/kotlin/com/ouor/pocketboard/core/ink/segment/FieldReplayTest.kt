package com.ouor.pocketboard.core.ink.segment

import com.ouor.pocketboard.core.common.ink.InkItem
import com.ouor.pocketboard.core.common.ink.InkPoint
import com.ouor.pocketboard.core.common.ink.InkStroke
import com.ouor.pocketboard.core.ink.InkReport
import com.ouor.pocketboard.core.ink.rules.InkConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 현장 devlog 의 "ink" 줄(architecture.md §11)을 그대로 재현한다. 파일은 src/test/resources/field/. */
class FieldReplayTest {
    /** DevLogEntry.Ink 의 필요한 부분(:app 형식, 모르는 키는 무시). */
    @Serializable
    private class FieldInk(val strokes: List<List<List<Float>>>, val spaces: List<Int>, val report: InkReport) {
        fun items(): List<InkItem> = buildList {
            strokes.forEachIndexed { i, s ->
                repeat(spaces.count { it == i }) { add(InkItem.Space) }
                add(InkItem.Stroke(i.toLong(), InkStroke(s.map { InkPoint(it[0], it[1], it[2].toLong()) })))
            }
            repeat(spaces.count { it == strokes.size }) { add(InkItem.Space) }
        }
    }

    private fun load(name: String): FieldInk =
        Json { ignoreUnknownKeys = true }.decodeFromString(FieldInk.serializer(), javaClass.getResource("/field/$name")!!.readText())

    /** 녹화 당시 분할(S5 왼쪽 위 되돌아감·겹친 음절 나누기 없음)로 다시 돌린다: 그 뒤 단계(Split·Text)를 같은 인식기 답으로 확인. */
    private suspend fun replay(name: String): Pair<InkReport, RecordedInkEngine> {
        val f = load(name)
        val report = f.report.copy(config = f.report.config.copy(topLeftCornerRatio = 0f, stackedMinStrokes = 0))
        val engine = RecordedInkEngine(f.items(), report)
        return replayInk(f.items(), report, engine) to engine
    }

    /**
     * 현 기본값 회귀: 사례마다 기기(실제 ML Kit, FieldEvalTest dump)에서 녹화한 보고서(field/recorded/)의 인식기 답으로 다시 돌려
     * 같은 결과가 나와야 한다. miss 가 나면 분할·재배치가 바뀐 것: 기기에서 FieldEvalTest 로 CER 을 다시 재고 녹화를 갈아 끼운다.
     */
    @Test
    fun allCases_currentDefaults_reproduceDeviceRecording() = runTest {
        val cases = javaClass.getResource("/field/cases.tsv")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(15, cases.size)
        for (line in cases) {
            val file = line.substringBefore('\t')
            val items = load(file).items()
            val recorded = Json.decodeFromString(InkReport.serializer(), javaClass.getResource("/field/recorded/$file")!!.readText())
            assertEquals(file, InkConfig(), recorded.config)
            val engine = RecordedInkEngine(items, recorded)
            val again = replayInk(items, recorded, engine)
            assertEquals(file, 0, engine.misses)
            assertEquals(file, recorded.text, again.text)
            assertEquals(file, recorded.words.map { it.clusters }, again.words.map { it.clusters })
        }
    }

    @Test
    fun stackedSyllables_fieldCases_recoverDroppedSyllables() = runTest {
        // 현장 3차: 2~3음절이 한 칸에 겹친 묶음. 6획 이상이면 나눌 자리를 ML Kit 으로 찾는다
        // 626969: "이면"(12획)→"면" 이던 것이 "사람이면". 672370: "담원"(6획)·"하면"(8획) 되살림. 691193: "버림"(6획)→"림" 이던 것이 "어김"
        val expected = mapOf(
            "ink-1793166626969.json" to "대한 민국사람이면 10일공",
            "ink-1793166672370.json" to "요즘이단에전화 하면상담원연결안되고 챗물로만문하고",
            "ink-1793166691193.json" to "끊어김",
        )
        for ((file, text) in expected) {
            val items = load(file).items()
            val recorded = Json.decodeFromString(InkReport.serializer(), javaClass.getResource("/field/recorded/$file")!!.readText())
            val engine = RecordedInkEngine(items, recorded)
            val r = replayInk(items, recorded, engine)
            assertEquals(file, text, r.text)
            assertEquals(file, 0, engine.misses)
            assertTrue(file, "split/stacked-syllables" in r.ruleIds)
            val stacked = r.words.single().splits.filter { it.ruleId == "split/stacked-syllables" }
            assertTrue(file, stacked.any { it.parts != null })
            assertTrue(file, stacked.sumOf { it.tries.size } <= InkConfig().maxSplitCalls)
        }
    }

    @Test
    fun singles_stackedSyllables_neverSplitsOneSyllable() = runTest {
        // 사용자가 따로 쓴 6~9획 표준 음절 10자 × 2바퀴(field/singles.tsv). 기기 기본값(6획)으로 녹화한 인식기 답으로 다시 돌린다
        val singles = javaClass.getResource("/field/singles.tsv")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(20, singles.size)
        var fired = 0
        for (line in singles) {
            val file = line.substringBefore('\t')
            val items = load(file).items()
            val recorded = Json.decodeFromString(InkReport.serializer(), javaClass.getResource("/field/recorded/$file")!!.readText())
            assertEquals(file, InkConfig(), recorded.config)
            val engine = RecordedInkEngine(items, recorded)
            val r = replayInk(items, recorded, engine)
            assertEquals(file, 0, engine.misses)
            assertEquals(file, recorded.text, r.text)
            val word = r.words.single()
            assertEquals(file, 1, word.clusters.size)                       // 한 음절 = 한 묶음 그대로
            assertTrue(file, "split/stacked-syllables" !in r.ruleIds)
            val stacked = word.splits.filter { it.ruleId == "split/stacked-syllables" }
            for (s in stacked) {
                assertEquals(file, null, s.parts)
                // 자른 두 조각을 나란히 읽은 1순위가 모두 음절 1개(2~3개가 아님)여서 안 나눴다
                assertTrue("$file ${s.tries}", s.tries.isNotEmpty() && s.tries.all { it.reading?.length == 1 })
                // 시험 호출의 실제 녹화 답과 같은지(가정이 아니라 녹화 내용 확인)
                val tryCalls = word.calls.filter { c -> c.language == "ko" && c.strokes.size == word.strokes.size && c.dx.any { it != 0f } }
                assertEquals(file, s.tries.map { it.reading }, tryCalls.map { it.candidates.firstOrNull()?.trim() })
            }
            if (stacked.isNotEmpty()) fired++
        }
        assertEquals(7, fired)   // 6~7획 묶음 7개(값×2·앉·했·많·쉽·뷁)에서 확인을 불렀고 하나도 안 나눴다
    }

    @Test
    fun stackedSyllables_realSingleSyllable_kept() = runTest {
        // 691193 의 "끊"(6획, 한 칸): 자를 자리 3곳 모두 "끊" 한 음절로 읽혀 나누지 않는다
        val file = "ink-1793166691193.json"
        val items = load(file).items()
        val recorded = Json.decodeFromString(InkReport.serializer(), javaClass.getResource("/field/recorded/$file")!!.readText())
        val r = replayInk(items, recorded, RecordedInkEngine(items, recorded))
        val kept = r.words.single().splits.single { it.ruleId == "split/stacked-syllables" && it.parts == null }
        assertEquals(listOf("끊", "끊", "끊"), kept.tries.map { it.reading })
        assertTrue(r.text.startsWith("끊"))
    }

    @Test
    fun kkkk_overlapped8_splitPartsCountEvenIfReReadDropsOne() = runTest {
        // 현장: 8획 → ㅋ 조각 4개를 하나씩 확인했는데 재배치 재인식이 "ㅋㅋㅋ"(하나 빠짐)
        val (r, engine) = replay("kkkk-overlapped-8.json")
        assertEquals("ㅋㅋㅋㅋ", r.text)
        assertEquals(4, r.words.single().clusters.size)
        assertEquals(0, engine.misses)
    }

    @Test
    fun leadingSpaceFromPreContext_isTrimmed() = runTest {
        // 현장: ko 1순위 " 테스트"(앞 문맥 때문에 앞에 공백) → 호스트에 공백이 하나 더 갔다
        val (r, engine) = replay("test-leading-space.json")
        assertEquals("테스트", r.text)
        assertTrue(r.ruleIds.toString(), "text/trim-leading" in r.ruleIds)
        assertEquals(0, engine.misses)
    }
}
